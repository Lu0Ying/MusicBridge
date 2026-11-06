-- Provider 调用日志（报告 8.9，MySQL 8.x 口径）
-- 不记录密钥与音频内容；指纹只允许记录哈希前缀，debugReference 只指向受控调试存储。

CREATE TABLE provider_call_log (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider VARCHAR(32) NOT NULL,
    capability VARCHAR(24) NOT NULL,
    input_type VARCHAR(24) NULL,
    request_units INT NOT NULL DEFAULT 1,
    http_status INT NULL,
    provider_error_code VARCHAR(64) NULL,
    normalized_error_code VARCHAR(32) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    retry_after_ms INT NULL,
    latency_ms INT NOT NULL DEFAULT 0,
    from_provider VARCHAR(32) NULL,
    to_provider VARCHAR(32) NULL,
    switch_reason VARCHAR(128) NULL,
    estimated_cost DECIMAL(12, 6) NOT NULL DEFAULT 0,
    cache_hit TINYINT(1) NOT NULL DEFAULT 0,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_provider_call_log_provider_time (provider, occurred_at DESC),
    KEY idx_provider_call_log_trace (trace_id),
    CONSTRAINT provider_call_log_units_chk CHECK (request_units >= 0),
    CONSTRAINT provider_call_log_latency_chk CHECK (latency_ms >= 0),
    CONSTRAINT provider_call_log_cost_chk CHECK (estimated_cost >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
