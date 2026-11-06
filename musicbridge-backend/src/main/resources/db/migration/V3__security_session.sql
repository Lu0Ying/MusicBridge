-- 本机会话表：令牌只存 SHA-256 哈希，支持过期与主动撤销
CREATE TABLE user_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- 与 upload_consent 等业务表的 user_id 保持同类型，便于后续按用户查询
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_session_token (token_hash),
    KEY idx_session_user (user_id, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
