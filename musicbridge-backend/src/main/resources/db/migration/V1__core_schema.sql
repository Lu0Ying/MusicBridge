-- MusicBridge Phase 0 核心数据模型（MySQL 8.x）
-- 依据调研报告第 9 部分：Work / Recording / Release / ReleaseTrack 四层建模，
-- 外部标识统一走 external_ref，同录音多发行通过 release_track -> release 查询表达。
-- MySQL 口径：UUID 统一 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin，时间 DATETIME(3)，JSON 使用 JSON 类型，utf8mb4 + InnoDB。

CREATE TABLE work (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(512) NOT NULL,
    mb_work_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    is_local_placeholder TINYINT(1) NOT NULL DEFAULT 0,
    language VARCHAR(16) NULL,
    source_provider VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(255) NULL,
    source_license VARCHAR(64) NULL,
    retrieved_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_work_mb_work_id (mb_work_id),
    -- 本地占位 Work 不强制外部 record id，其余外部数据必须提供
    CONSTRAINT work_source_chk CHECK (source_provider = 'LOCAL' OR source_record_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE recording (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(512) NOT NULL,
    artist_credits JSON NOT NULL DEFAULT (JSON_ARRAY()),
    duration_ms INT NULL,
    version_type VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL',
    mb_recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_provider VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(255) NOT NULL,
    source_license VARCHAR(64) NULL,
    retrieved_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_recording_mb_recording_id (mb_recording_id),
    KEY idx_recording_work (work_id),
    KEY idx_recording_title_artist (title),
    CONSTRAINT fk_recording_work FOREIGN KEY (work_id) REFERENCES work (id),
    CONSTRAINT recording_duration_chk CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT recording_version_chk
        CHECK (version_type IN ('ORIGINAL', 'COVER', 'LIVE', 'REMIX', 'INSTRUMENTAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- RELEASE 是 MySQL 8.0 保留字，表名统一使用反引号
CREATE TABLE `release` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mb_release_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    release_group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    title VARCHAR(512) NOT NULL,
    country VARCHAR(8) NULL,
    release_date DATE NULL,
    status VARCHAR(16) NULL,
    medium_format VARCHAR(32) NULL,
    source_provider VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(255) NOT NULL,
    source_license VARCHAR(64) NULL,
    retrieved_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_release_mb_release_id (mb_release_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE release_track (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    release_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    disc_number SMALLINT NOT NULL DEFAULT 1,
    track_number SMALLINT NOT NULL,
    position INT NOT NULL,
    duration_ms INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_release_track_position (release_id, disc_number, track_number),
    KEY idx_release_track_recording (recording_id),
    CONSTRAINT fk_release_track_release FOREIGN KEY (release_id) REFERENCES `release` (id) ON DELETE CASCADE,
    CONSTRAINT fk_release_track_recording FOREIGN KEY (recording_id) REFERENCES recording (id),
    CONSTRAINT release_track_number_chk CHECK (disc_number >= 1 AND track_number >= 1 AND position >= 0),
    CONSTRAINT release_track_duration_chk CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE music_resource (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NULL,
    url TEXT NULL,
    region VARCHAR(8) NULL,
    quality VARCHAR(32) NULL,
    source_provider VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(255) NOT NULL,
    source_license VARCHAR(64) NULL,
    retrieved_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_resource_recording (recording_id),
    CONSTRAINT fk_music_resource_recording FOREIGN KEY (recording_id) REFERENCES recording (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lyrics (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) NOT NULL,
    language VARCHAR(16) NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    plain_text TEXT NULL,
    source_url TEXT NULL,
    license_note TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lyrics_recording_kind_lang_provider (recording_id, kind, language, provider_id),
    KEY idx_lyrics_recording_kind (recording_id, kind),
    CONSTRAINT fk_lyrics_recording FOREIGN KEY (recording_id) REFERENCES recording (id),
    CONSTRAINT lyrics_kind_chk CHECK (kind IN ('ORIGINAL', 'TRANSLATION', 'TRANSLITERATION')),
    CONSTRAINT lyrics_plain_chk CHECK (plain_text IS NULL OR length(plain_text) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
-- “plain_text 非空或至少一条 lyric_line”为跨表条件，由应用层保证

CREATE TABLE lyric_line (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lyrics_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_no SMALLINT NOT NULL,
    start_ms INT NULL,
    end_ms INT NULL,
    text TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lyric_line_no (lyrics_id, line_no),
    CONSTRAINT fk_lyric_line_lyrics FOREIGN KEY (lyrics_id) REFERENCES lyrics (id) ON DELETE CASCADE,
    CONSTRAINT lyric_line_no_chk CHECK (line_no >= 0),
    CONSTRAINT lyric_line_time_chk CHECK (
        (start_ms IS NULL OR start_ms >= 0)
        AND (end_ms IS NULL OR end_ms >= 0)
        AND (start_ms IS NULL OR end_ms IS NULL OR end_ms >= start_ms)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE external_ref (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id_type VARCHAR(16) NOT NULL,
    -- 外部标识区分大小写（ISRC/MBID 等大小写不同即为不同值）
    id_value VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    source_provider VARCHAR(32) NOT NULL,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_external_ref_type_value (id_type, id_value),
    KEY idx_external_ref_value (id_value),
    CONSTRAINT external_ref_entity_chk
        CHECK (entity_type IN ('RECORDING', 'RELEASE', 'WORK', 'LYRICS')),
    CONSTRAINT external_ref_id_type_chk
        CHECK (id_type IN ('ISRC', 'MBID', 'SPOTIFY_ID', 'ITUNES_ID', 'ACOUSTID'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_consent (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(32) NOT NULL,
    purpose TEXT NOT NULL,
    granted_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    retention_days SMALLINT NOT NULL,
    client_info JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_consent_user (user_id),
    CONSTRAINT upload_consent_retention_chk CHECK (retention_days >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE field_provenance (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    source_provider VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(255) NOT NULL,
    source_license VARCHAR(64) NULL,
    retrieved_at DATETIME(3) NOT NULL,
    value_hash VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_field_provenance
        (entity_type, entity_id, field_name, source_provider, source_record_id),
    KEY idx_provenance_entity (entity_type, entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE recording_relation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_scope VARCHAR(48) NOT NULL DEFAULT 'SAME_WORK_DIFFERENT_RECORDING',
    relation_subtype VARCHAR(16) NULL,
    confidence DECIMAL(4, 3) NULL,
    source VARCHAR(32) NULL,
    confirmed_by_user TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- A→B 与 B→A 视为同一条边：用生成列做规范序唯一约束（MySQL 无部分索引）
    least_recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (LEAST(source_recording_id, target_recording_id)) STORED,
    greatest_recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
        (GREATEST(source_recording_id, target_recording_id)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_relation_canonical
        (least_recording_id, greatest_recording_id, relation_scope),
    KEY idx_relation_source (source_recording_id),
    KEY idx_relation_target (target_recording_id),
    CONSTRAINT fk_recording_relation_source
        FOREIGN KEY (source_recording_id) REFERENCES recording (id),
    CONSTRAINT fk_recording_relation_target
        FOREIGN KEY (target_recording_id) REFERENCES recording (id),
    CONSTRAINT recording_relation_scope_chk
        CHECK (relation_scope = 'SAME_WORK_DIFFERENT_RECORDING'),
    CONSTRAINT recording_relation_no_self
        CHECK (source_recording_id <> target_recording_id),
    CONSTRAINT recording_relation_subtype_chk CHECK (
        relation_subtype IS NULL
        OR relation_subtype IN ('COVER', 'LIVE', 'REMIX', 'INSTRUMENTAL')
    ),
    CONSTRAINT recording_relation_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE local_media (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- MySQL 组合唯一索引长度受限（utf8mb4 下 <= 3072 字节），路径列长度取 700
    -- 文件路径区分大小写，避免仅大小写不同的路径被合并
    file_path VARCHAR(700) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    file_hash VARCHAR(64) NULL,
    fingerprint TEXT NULL,
    format VARCHAR(16) NULL,
    duration_ms INT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_local_media_user_path (user_id, file_path),
    KEY idx_local_hash (file_hash),
    -- TEXT 不能整体建索引，使用前缀索引
    KEY idx_local_fingerprint (fingerprint(191)),
    KEY idx_local_media_recording (recording_id),
    CONSTRAINT fk_local_media_recording FOREIGN KEY (recording_id) REFERENCES recording (id),
    CONSTRAINT local_media_duration_chk CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
-- 同一 hash 的多条记录视为同一文件多副本，不自动删除任一路径

CREATE TABLE user_library (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    added_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    source VARCHAR(32) NULL,
    favorite TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_library (user_id, recording_id),
    KEY idx_library_user (user_id),
    KEY idx_library_recording (recording_id),
    CONSTRAINT fk_user_library_recording FOREIGN KEY (recording_id) REFERENCES recording (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Playlist 完整字段列入后续任务，第一版只保证 sort_key
CREATE TABLE playlist (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_playlist_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE playlist_item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    playlist_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sort_key VARCHAR(64) NOT NULL,
    added_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_playlist_item_sort (playlist_id, sort_key),
    KEY idx_playlist_item_recording (recording_id),
    CONSTRAINT fk_playlist_item_playlist
        FOREIGN KEY (playlist_id) REFERENCES playlist (id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_item_recording
        FOREIGN KEY (recording_id) REFERENCES recording (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE music_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    recording_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
    occurred_at DATETIME(3) NOT NULL,
    source VARCHAR(32) NULL,
    trace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dedupe_key VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_music_event_dedupe (dedupe_key),
    KEY idx_event_user_time (user_id, occurred_at DESC),
    KEY idx_event_recording (recording_id),
    CONSTRAINT fk_music_event_recording FOREIGN KEY (recording_id) REFERENCES recording (id),
    CONSTRAINT music_event_type_chk
        CHECK (event_type IN ('RECOGNIZED', 'CONFIRMED', 'ADDED_TO_LIBRARY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
