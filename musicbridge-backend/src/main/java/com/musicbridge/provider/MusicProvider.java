package com.musicbridge.provider;

import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;

/**
 * 所有 Provider 的公共契约。
 * <p>业务层只依赖能力接口与统一 DTO，不出现具体 Provider 分支判断。</p>
 */
public interface MusicProvider {

    /** Provider 稳定标识，配置与调用日志均以此为准 */
    String id();

    ProviderCapability capability();

    /**
     * 静态配置检查：仅检查 key/endpoint/必填配置，不发起外部请求。
     */
    ProviderConfigStatus checkConfig();

    /**
     * 主动探测：默认不参与识别主链路，仅管理端白名单按需调用。
     */
    ProviderHealth probeHealth();
}
