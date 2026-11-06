-- Phase 1.2：片段元数据（音频文件本体落盘，数据库只保留相对定位信息）
-- 冻结契约见 research/musicbridge-phase1/CLIPS_CONTRACT.md

CREATE TABLE clip (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    format VARCHAR(8) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    -- 文件实际字节数（不含 multipart 开销）
    size_bytes BIGINT NOT NULL,
    -- 服务端实测时长（毫秒），客户端提示值不落库
    duration_ms BIGINT NOT NULL,
    file_name VARCHAR(120) NULL,
    -- 仅存 clipId.ext，绝对路径由配置的根目录拼装，禁止落库
    storage_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'STORED',
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_clip_user (user_id, expires_at),
    KEY idx_clip_cleanup (status, expires_at),
    CONSTRAINT clip_status_chk CHECK (status IN ('STORED', 'EXPIRED')),
    CONSTRAINT clip_size_chk CHECK (size_bytes > 0),
    CONSTRAINT clip_duration_chk CHECK (duration_ms > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
