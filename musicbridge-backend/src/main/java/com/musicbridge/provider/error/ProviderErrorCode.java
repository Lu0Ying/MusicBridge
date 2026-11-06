package com.musicbridge.provider.error;

/**
 * 报告 8.5 的 8 类归一化错误码。
 * <p>判定需结合 HTTP 状态码与 Provider 返回体错误码，并保留 providerErrorCode。</p>
 */
public enum ProviderErrorCode {
    /** 缺 key、endpoint 非法等配置问题：停用并告警，不重试、不切换 */
    CONFIG_ERROR,
    /** 401/token 过期/签名错误：停用，不重试、不切换 */
    AUTH_INVALID,
    /** 403：区分功能级（可切换）与账号级（整 Provider 停用） */
    PERMISSION_DENIED,
    /** 配额/额度用尽：不重试，预算允许时可切换 */
    QUOTA_EXCEEDED,
    /** 429 或明确限流：按 Retry-After 重试一次，超过阈值可有条件切换 */
    RATE_LIMITED,
    /** 5xx、超时、DNS：指数退避重试，耗尽后切换并熔断 */
    TEMPORARY_UNAVAILABLE,
    /** 输入类型/时长/格式不支持：不重试，直接换下一个 Provider */
    UNSUPPORTED_INPUT,
    /** 200 但空结果：不重试，成本允许时切换，写负缓存 */
    NO_MATCH
}
