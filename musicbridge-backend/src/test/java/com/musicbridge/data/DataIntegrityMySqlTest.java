package com.musicbridge.data;

import com.musicbridge.lyrics.LyricsConsistencyService;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.web.InvalidRequestException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-06（歌词跨表一致性）与 D-10（external_ref 存在性与孤儿清理）的 MySQL 集成测试。
 * 依赖 MB_RUN_MYSQL_IT=true 与 MYSQL_TEST_* 环境变量，使用独立测试库。
 */
@EnabledIfEnvironmentVariable(named = "MB_RUN_MYSQL_IT", matches = "true")
class DataIntegrityMySqlTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static LyricsConsistencyService lyricsService;
    private static ExternalRefService externalRefService;

    @BeforeAll
    static void prepareDatabase() {
        String url = requireEnv("MYSQL_TEST_URL");
        String user = requireEnv("MYSQL_TEST_USER");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");

        Flyway flyway = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        dataSource = new DriverManagerDataSource(url, user, password);
        jdbcTemplate = new JdbcTemplate(dataSource);
        lyricsService = new LyricsConsistencyService(jdbcTemplate,
                new DataSourceTransactionManager(dataSource));
        externalRefService = new ExternalRefService(jdbcTemplate,
                new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM external_ref");
        jdbcTemplate.update("DELETE FROM lyric_line");
        jdbcTemplate.update("DELETE FROM lyrics");
        jdbcTemplate.update("DELETE FROM recording");
        jdbcTemplate.update("DELETE FROM work");
        jdbcTemplate.update("INSERT INTO work (id, title, source_provider, retrieved_at) "
                + "VALUES ('work-1', '晴天', 'LOCAL', NOW(3))");
        jdbcTemplate.update("INSERT INTO recording (id, work_id, title, source_provider, "
                + "source_record_id, retrieved_at) VALUES ('rec-a', 'work-1', '晴天', 'LOCAL', 'local-1', NOW(3))");
    }

    // ------------------------------------------------------------ D-06

    @Test
    void plainTextAloneIsConsistent() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics",
                "第一句\n第二句", List.of());
        lyricsService.assertConsistent(lyricsId);
        assertTrue(lyricsService.findInconsistent().isEmpty());
    }

    @Test
    void lyricLinesAloneAreConsistent() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics", null,
                List.of(new LyricsConsistencyService.LyricLineInput(0, 0, 1000, "第一句"),
                        new LyricsConsistencyService.LyricLineInput(1, 1000, 2000, "第二句")));
        assertEquals(2, countLines(lyricsId));
        assertTrue(lyricsService.findInconsistent().isEmpty());
    }

    @Test
    void neitherPlainTextNorLinesIsRejectedAndRolledBack() {
        assertThrows(InvalidRequestException.class, () -> lyricsService.createLyrics(
                "rec-a", "ORIGINAL", "zh", "mock-lyrics", null, List.of()));
        assertEquals(0, countLyrics(), "校验失败必须回滚插入");
    }

    @Test
    void clearingPlainTextWithoutLinesIsRejected() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics",
                "原文", List.of());
        assertThrows(InvalidRequestException.class,
                () -> lyricsService.updatePlainText(lyricsId, null));
        assertEquals("原文", jdbcTemplate.queryForObject(
                "SELECT plain_text FROM lyrics WHERE id = ?", String.class, lyricsId));
    }

    @Test
    void deletingLastLineWithoutPlainTextIsRejected() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics", null,
                List.of(new LyricsConsistencyService.LyricLineInput(0, 0, 1000, "唯一一句")));
        String lineId = jdbcTemplate.queryForObject(
                "SELECT id FROM lyric_line WHERE lyrics_id = ?", String.class, lyricsId);

        assertThrows(InvalidRequestException.class, () -> lyricsService.deleteLine(lineId));
        assertEquals(1, countLines(lyricsId), "拒绝后必须回滚删除");
    }

    @Test
    void deletingLineIsAllowedWhenPlainTextExists() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics",
                "原文", List.of(new LyricsConsistencyService.LyricLineInput(0, 0, 1000, "第一句")));
        String lineId = jdbcTemplate.queryForObject(
                "SELECT id FROM lyric_line WHERE lyrics_id = ?", String.class, lyricsId);

        lyricsService.deleteLine(lineId);
        assertEquals(0, countLines(lyricsId));
    }

    @Test
    void inconsistentExistingDataIsDetectedAndRepairable() {
        String lyricsId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO lyrics (id, recording_id, kind, language, provider_id, plain_text) "
                + "VALUES (?, 'rec-a', 'ORIGINAL', 'zh', 'broken', NULL)", lyricsId);

        assertEquals(List.of(lyricsId), lyricsService.findInconsistent());
        lyricsService.addLine(lyricsId, 0, 0, 1000, "修复");
        assertTrue(lyricsService.findInconsistent().isEmpty());
    }

    @Test
    void deletingLyricsCascadesLines() {
        String lyricsId = lyricsService.createLyrics("rec-a", "ORIGINAL", "zh", "mock-lyrics", null,
                List.of(new LyricsConsistencyService.LyricLineInput(0, 0, 1000, "第一句")));
        jdbcTemplate.update("DELETE FROM lyrics WHERE id = ?", lyricsId);
        assertEquals(0, countLines(lyricsId), "主表删除后不得留下孤儿歌词行");
        assertFalse(lyricsService.findInconsistent().contains(lyricsId));
    }

    // ------------------------------------------------------------ D-10

    @Test
    void registersRefForExistingEntity() {
        String refId = externalRefService.register("RECORDING", "rec-a", "ISRC", "ISRC-1", "MOCK", true);
        assertFalse(refId.isBlank());
        assertEquals(1, externalRefService.countEntityRefs("RECORDING", "rec-a"));
    }

    @Test
    void rejectsRefForMissingEntity() {
        assertThrows(ResourceNotFoundException.class,
                () -> externalRefService.register("RECORDING", "missing", "ISRC", "ISRC-2", "MOCK", false));
        assertEquals(0, externalRefService.countEntityRefs("RECORDING", "missing"));
    }

    @Test
    void rejectsUnsupportedEntityOrIdType() {
        assertThrows(InvalidRequestException.class,
                () -> externalRefService.register("PLAYLIST", "rec-a", "ISRC", "ISRC-3", "MOCK", false));
        assertThrows(InvalidRequestException.class,
                () -> externalRefService.register("RECORDING", "rec-a", "UNKNOWN", "X-1", "MOCK", false));
    }

    @Test
    void rejectsDuplicateExternalIdentifier() {
        externalRefService.register("RECORDING", "rec-a", "ISRC", "ISRC-DUP", "MOCK", true);
        assertThrows(InvalidRequestException.class,
                () -> externalRefService.register("RECORDING", "rec-a", "ISRC", "ISRC-DUP", "MOCK", false));
    }

    @Test
    void cleanupRemovesOnlyOrphansAndIsIdempotent() {
        String valid = externalRefService.register("RECORDING", "rec-a", "MBID", "mb-1", "MOCK", false);
        jdbcTemplate.update("INSERT INTO external_ref (id, entity_type, entity_id, id_type, id_value, "
                        + "source_provider) VALUES (?, 'RECORDING', 'rec-gone', 'ISRC', 'ISRC-GONE', 'MOCK')",
                "orphan-1");
        jdbcTemplate.update("INSERT INTO external_ref (id, entity_type, entity_id, id_type, id_value, "
                        + "source_provider) VALUES (?, 'WORK', 'work-gone', 'MBID', 'mb-gone', 'MOCK')",
                "orphan-2");

        assertEquals(List.of("orphan-1", "orphan-2"),
                externalRefService.findOrphanIds().stream().sorted().toList());
        assertEquals(2, externalRefService.cleanupOrphans());
        assertEquals(1, externalRefService.countEntityRefs("RECORDING", "rec-a"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_ref WHERE id = ?", Long.class, valid));
        assertEquals(0, externalRefService.cleanupOrphans(), "重复清理必须幂等");
    }

    // ------------------------------------------------------------ helpers

    private long countLines(String lyricsId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lyric_line WHERE lyrics_id = ?", Long.class, lyricsId);
        return count == null ? 0 : count;
    }

    private long countLyrics() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lyrics", Long.class);
        return count == null ? 0 : count;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }
}
