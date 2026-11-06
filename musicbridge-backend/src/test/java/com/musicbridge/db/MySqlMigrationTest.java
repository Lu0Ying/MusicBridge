package com.musicbridge.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 8.x 迁移集成测试。
 * <p>默认跳过，仅当显式设置 MB_RUN_MYSQL_IT=true 且提供 MYSQL_TEST_URL/USER/PASSWORD 时执行；
 * 使用独立测试库，测试开始会 clean，禁止指向正式库。</p>
 */
@EnabledIfEnvironmentVariable(named = "MB_RUN_MYSQL_IT", matches = "true")
class MySqlMigrationTest {

    private static final List<String> CORE_TABLES = List.of(
            "work", "recording", "release", "release_track", "music_resource", "lyrics",
            "lyric_line", "external_ref", "upload_consent", "field_provenance",
            "recording_relation", "local_media", "user_library", "playlist",
            "playlist_item", "music_event", "provider_call_log", "user_session");

    @Test
    void migratesEmptySchemaAndKeepsDataOnRerun() throws Exception {
        String url = requireEnv("MYSQL_TEST_URL");
        String user = requireEnv("MYSQL_TEST_USER");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        MigrateResult first = flyway.migrate();
        assertTrue(first.migrationsExecuted >= 3, "至少执行 V1/V2/V3 三个迁移");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String table : CORE_TABLES) {
                assertTrue(tableExists(connection, table), "缺少表: " + table);
            }
            insertLibrarySample(connection);
        }

        MigrateResult second = flyway.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行脚本");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals(1, countRows(connection, "work"), "重复迁移后原有数据必须保留");
            assertEquals(1, countRows(connection, "recording"), "重复迁移后原有数据必须保留");
        }
    }

    @Test
    void enforcesCoreConstraints() throws Exception {
        String url = requireEnv("MYSQL_TEST_URL");
        String user = requireEnv("MYSQL_TEST_USER");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            ensureBaseEntities(connection);
            String relationSql = "INSERT INTO recording_relation "
                    + "(id, source_recording_id, target_recording_id, relation_scope) "
                    + "VALUES (?, ?, ?, 'SAME_WORK_DIFFERENT_RECORDING')";
            insert(connection, relationSql, "rel-1", "rec-a", "rec-b");

            // A→B 与 B→A 必须被规范序唯一索引判定为重复
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insert(connection, relationSql, "rel-2", "rec-b", "rec-a"));
            assertTrue(duplicate.getMessage() != null && duplicate.getMessage().contains("Duplicate"));

            // MySQL 8.0.16+ 强制 CHECK 约束
            String invalidVersionSql = "INSERT INTO recording "
                    + "(id, work_id, title, version_type, source_provider, source_record_id, retrieved_at) "
                    + "VALUES (?, ?, ?, 'INVALID', 'MOCK', 'mock-rec', NOW(3))";
            SQLException invalidVersion = assertThrows(SQLException.class,
                    () -> insert(connection, invalidVersionSql, "rec-invalid", "work-1", "bad"));
            assertTrue(invalidVersion.getMessage() != null
                    && invalidVersion.getMessage().contains("recording_version_chk"));
        }
    }

    @Test
    void enforcesRemainingCoreConstraints() throws Exception {
        String url = requireEnv("MYSQL_TEST_URL");
        String user = requireEnv("MYSQL_TEST_USER");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        migrateClean(url, user, password);

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            insertLibrarySample(connection);

            // work_source_chk: 非 LOCAL 来源必须提供 source_record_id
            SQLException missingSource = assertThrows(SQLException.class,
                    () -> insert(connection,
                            "INSERT INTO work (id, title, source_provider, retrieved_at) "
                                    + "VALUES (?, ?, 'MOCK', NOW(3))",
                            "work-bad", "缺少来源"));
            assertTrue(messageContains(missingSource, "work_source_chk"), missingSource.getMessage());

            // D-17: mb_work_id 允许多行 NULL，非空值重复被拒绝
            insertWork(connection, "work-null-a", "A", null);
            insertWork(connection, "work-null-b", "B", null);
            insertWork(connection, "work-mb-a", "C", "mb-work-1");
            SQLException duplicateMb = assertThrows(SQLException.class,
                    () -> insertWork(connection, "work-mb-b", "D", "mb-work-1"));
            assertTrue(messageContains(duplicateMb, "Duplicate"), duplicateMb.getMessage());

            // lyric_line_time_chk: end_ms 必须 >= start_ms
            insert(connection,
                    "INSERT INTO lyrics (id, recording_id, kind, language, provider_id, plain_text) "
                            + "VALUES (?, ?, 'ORIGINAL', 'zh', 'mock-lyrics', '歌词')",
                    "lyrics-1", "rec-a");
            SQLException badLineTime = assertThrows(SQLException.class,
                    () -> insert(connection,
                            "INSERT INTO lyric_line (id, lyrics_id, line_no, start_ms, end_ms, text) "
                                    + "VALUES (?, ?, 0, 500, 100, 'x')",
                            "line-bad", "lyrics-1"));
            assertTrue(messageContains(badLineTime, "lyric_line_time_chk"), badLineTime.getMessage());

            // recording_relation_no_self: 禁止自指
            SQLException selfRelation = assertThrows(SQLException.class,
                    () -> insert(connection,
                            "INSERT INTO recording_relation (id, source_recording_id, target_recording_id) "
                                    + "VALUES (?, 'rec-a', 'rec-a')",
                            "rel-self"));
            assertTrue(messageContains(selfRelation, "recording_relation_no_self"),
                    selfRelation.getMessage());

            // music_event_type_chk 与 payload JSON 默认值
            SQLException badEventType = assertThrows(SQLException.class,
                    () -> insert(connection,
                            "INSERT INTO music_event (id, user_id, event_type, occurred_at) "
                                    + "VALUES (?, 'user-1', 'BAD', NOW(3))",
                            "event-bad"));
            assertTrue(messageContains(badEventType, "music_event_type_chk"), badEventType.getMessage());

            insert(connection,
                    "INSERT INTO music_event (id, user_id, event_type, occurred_at) "
                            + "VALUES (?, 'user-1', 'RECOGNIZED', NOW(3))",
                    "event-1");
            assertEquals("{}", queryString(connection, "SELECT payload FROM music_event WHERE id = 'event-1'"),
                    "payload 应使用 JSON_OBJECT() 默认值");

            // D-17: dedupe_key 允许多行 NULL
            insert(connection,
                    "INSERT INTO music_event (id, user_id, event_type, occurred_at, dedupe_key) "
                            + "VALUES (?, 'user-1', 'CONFIRMED', NOW(3), NULL)",
                    "event-null-a");
            insert(connection,
                    "INSERT INTO music_event (id, user_id, event_type, occurred_at, dedupe_key) "
                            + "VALUES (?, 'user-1', 'CONFIRMED', NOW(3), NULL)",
                    "event-null-b");
        }
    }

    @Test
    void schemaUsesMysqlConventionsAndKeyIndexes() throws Exception {
        String url = requireEnv("MYSQL_TEST_URL");
        String user = requireEnv("MYSQL_TEST_USER");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        migrateClean(url, user, password);

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Map<String, String> expectedIndexes = Map.of(
                    "idx_recording_work", "recording",
                    "idx_local_hash", "local_media",
                    "idx_local_fingerprint", "local_media",
                    "idx_external_ref_value", "external_ref",
                    "idx_consent_user", "upload_consent",
                    "idx_lyrics_recording_kind", "lyrics",
                    "idx_relation_target", "recording_relation",
                    "idx_event_user_time", "music_event");
            for (Map.Entry<String, String> entry : expectedIndexes.entrySet()) {
                assertTrue(indexExists(connection, entry.getValue(), entry.getKey()),
                        "缺少索引: " + entry.getValue() + "." + entry.getKey());
            }

            // D-13: 旧命名 track 表不存在
            assertFalse(tableExists(connection, "track"), "不应存在 track 表");

            // D-18: 全部核心表 InnoDB + utf8mb4
            for (String table : CORE_TABLES) {
                assertEquals("InnoDB", tableAttribute(connection, table, "engine"), "引擎不正确: " + table);
                String collation = tableAttribute(connection, table, "table_collation");
                assertTrue(collation != null && collation.startsWith("utf8mb4"),
                        "字符集不正确: " + table + " -> " + collation);
            }
            assertEquals("ascii_bin", columnCollation(connection, "work", "id"));
            assertEquals("ascii_bin", columnCollation(connection, "provider_call_log", "trace_id"));
            assertEquals("ascii_bin", columnCollation(connection, "user_session", "token_hash"));
            assertEquals("utf8mb4_0900_as_cs", columnCollation(connection, "external_ref", "id_value"));
            assertEquals("utf8mb4_0900_as_cs", columnCollation(connection, "local_media", "file_path"));
        }
    }

    // ---------------------------------------------------------------- 辅助

    private void migrateClean(String url, String user, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private boolean messageContains(SQLException error, String text) {
        return error.getMessage() != null && error.getMessage().contains(text);
    }

    private void insertWork(Connection connection, String id, String title, String mbWorkId) throws SQLException {
        insert(connection,
                "INSERT INTO work (id, title, mb_work_id, source_provider, source_record_id, retrieved_at) "
                        + "VALUES (?, ?, ?, 'MOCK', ?, NOW(3))",
                id, title, mbWorkId, "mock-work-" + id);
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String tableAttribute(Connection connection, String table, String attribute) throws SQLException {
        String sql = "SELECT " + attribute + " FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String columnCollation(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT collation_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量: " + name);
        }
        return value;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void insertLibrarySample(Connection connection) throws SQLException {
        insert(connection, "INSERT INTO work (id, title, source_provider, source_record_id, retrieved_at) "
                + "VALUES (?, ?, 'MOCK', 'mock-work', NOW(3))", "work-1", "晴天");
        insert(connection, "INSERT INTO recording (id, work_id, title, source_provider, source_record_id, retrieved_at) "
                + "VALUES (?, ?, ?, 'MOCK', 'mock-rec', NOW(3))", "rec-a", "work-1", "晴天");
    }

    private void ensureBaseEntities(Connection connection) throws SQLException {
        insert(connection, "INSERT INTO work (id, title, source_provider, source_record_id, retrieved_at) "
                + "VALUES (?, ?, 'MOCK', 'mock-work', NOW(3))", "work-1", "晴天");
        insert(connection, "INSERT INTO recording (id, work_id, title, source_provider, source_record_id, retrieved_at) "
                + "VALUES (?, ?, ?, 'MOCK', 'mock-rec-a', NOW(3))", "rec-a", "work-1", "晴天");
        insert(connection, "INSERT INTO recording (id, work_id, title, source_provider, source_record_id, retrieved_at) "
                + "VALUES (?, ?, ?, 'MOCK', 'mock-rec-b', NOW(3))", "rec-b", "work-1", "晴天（Live）");
    }

    private void insert(Connection connection, String sql, String... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
