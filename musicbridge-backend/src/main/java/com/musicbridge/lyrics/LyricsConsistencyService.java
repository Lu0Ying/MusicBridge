package com.musicbridge.lyrics;

import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.web.InvalidRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * D-06：lyrics.plain_text 与 lyric_line 的跨表一致性。
 *
 * <p>数据库只能保证单行约束，跨表条件“plain_text 非空或至少一条 lyric_line”由该服务保证：
 * 新增/更新/删除都在同一事务内先写后校验，失败即回滚；异常存量可用 {@link #findInconsistent()} 找出。</p>
 */
@Service
public class LyricsConsistencyService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public LyricsConsistencyService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public String createLyrics(String recordingId, String kind, String language, String providerId,
                               String plainText, List<LyricLineInput> lines) {
        List<LyricLineInput> actualLines = lines == null ? List.of() : lines;
        return transactionTemplate.execute(status -> {
            String lyricsId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO lyrics (id, recording_id, kind, language, provider_id, plain_text) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    lyricsId, recordingId, kind, language, providerId, plainText);
            for (LyricLineInput line : actualLines) {
                insertLine(lyricsId, line);
            }
            assertConsistent(lyricsId);
            return lyricsId;
        });
    }

    public void addLine(String lyricsId, int lineNo, Integer startMs, Integer endMs, String text) {
        transactionTemplate.executeWithoutResult(status -> {
            insertLine(lyricsId, new LyricLineInput(lineNo, startMs, endMs, text));
            assertConsistent(lyricsId);
        });
    }

    public void updatePlainText(String lyricsId, String plainText) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update("UPDATE lyrics SET plain_text = ? WHERE id = ?",
                    plainText, lyricsId);
            if (updated == 0) {
                throw new ResourceNotFoundException("歌词不存在");
            }
            assertConsistent(lyricsId);
        });
    }

    public void deleteLine(String lineId) {
        transactionTemplate.executeWithoutResult(status -> {
            List<String> owners = jdbcTemplate.queryForList(
                    "SELECT lyrics_id FROM lyric_line WHERE id = ?", String.class, lineId);
            if (owners.isEmpty()) {
                throw new ResourceNotFoundException("歌词行不存在");
            }
            String lyricsId = owners.get(0);
            jdbcTemplate.update("DELETE FROM lyric_line WHERE id = ?", lineId);
            assertConsistent(lyricsId);
        });
    }

    /**
     * 校验一条歌词至少拥有一种内容；空白 plain_text 视为空。
     *
     * @throws ResourceNotFoundException 歌词不存在
     * @throws InvalidRequestException   两种内容同时为空
     */
    public void assertConsistent(String lyricsId) {
        List<String> plainTexts = jdbcTemplate.queryForList(
                "SELECT plain_text FROM lyrics WHERE id = ?", String.class, lyricsId);
        if (plainTexts.isEmpty()) {
            throw new ResourceNotFoundException("歌词不存在");
        }
        String plainText = plainTexts.get(0);
        Long lineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lyric_line WHERE lyrics_id = ?", Long.class, lyricsId);
        boolean hasText = plainText != null && !plainText.isBlank();
        if (!hasText && (lineCount == null || lineCount == 0)) {
            throw new InvalidRequestException("plain_text 与 lyric_line 不能同时为空");
        }
    }

    /** 扫描违反跨表不变量的歌词（异常存量），供修复任务使用 */
    public List<String> findInconsistent() {
        return jdbcTemplate.queryForList(
                "SELECT l.id FROM lyrics l "
                        + "WHERE (l.plain_text IS NULL OR length(trim(l.plain_text)) = 0) "
                        + "AND NOT EXISTS (SELECT 1 FROM lyric_line ll WHERE ll.lyrics_id = l.id)",
                String.class);
    }

    private void insertLine(String lyricsId, LyricLineInput line) {
        jdbcTemplate.update(
                "INSERT INTO lyric_line (id, lyrics_id, line_no, start_ms, end_ms, text) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), lyricsId,
                line.lineNo(), line.startMs(), line.endMs(), line.text());
    }

    /** 一行歌词的最小输入 */
    public record LyricLineInput(int lineNo, Integer startMs, Integer endMs, String text) {
    }
}
