package com.musicbridge.clips;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 片段元数据 JDBC 仓储。
 */
@Repository
public class JdbcClipRepository implements ClipRepository {

    private static final String COLUMNS = "id, user_id, consent_id, format, content_type, size_bytes, "
            + "duration_ms, file_name, storage_name, status, created_at, expires_at";

    private static final RowMapper<ClipRecord> ROW_MAPPER = JdbcClipRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public JdbcClipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(ClipRecord clip) {
        jdbcTemplate.update("INSERT INTO clip (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                clip.id(), clip.userId(), clip.consentId(), clip.format(), clip.contentType(),
                clip.sizeBytes(), clip.durationMs(), clip.fileName(), clip.storageName(),
                clip.status().name(), Timestamp.from(clip.createdAt()), Timestamp.from(clip.expiresAt()));
    }

    @Override
    public Optional<ClipRecord> findByIdAndUserId(String id, String userId) {
        List<ClipRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM clip WHERE id = ? AND user_id = ?",
                ROW_MAPPER, id, userId);
        return rows.stream().findFirst();
    }

    @Override
    public List<ClipRecord> findNeedingCleanup(Instant now, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM clip "
                        + "WHERE status = 'EXPIRED' OR (status = 'STORED' AND expires_at <= ?) "
                        + "ORDER BY expires_at ASC LIMIT ?",
                ROW_MAPPER, Timestamp.from(now), limit);
    }

    @Override
    public boolean markExpired(String id, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE clip SET status = 'EXPIRED' "
                        + "WHERE id = ? AND status = 'STORED' AND expires_at <= ?",
                id, Timestamp.from(now));
        return updated > 0;
    }

    @Override
    public boolean deleteByIdAndUserId(String id, String userId) {
        int deleted = jdbcTemplate.update("DELETE FROM clip WHERE id = ? AND user_id = ?", id, userId);
        return deleted > 0;
    }

    private static ClipRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ClipRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("consent_id"),
                rs.getString("format"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getLong("duration_ms"),
                rs.getString("file_name"),
                rs.getString("storage_name"),
                ClipStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }
}
