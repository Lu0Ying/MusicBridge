package com.musicbridge.provider.recognition;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.error.ProviderException;

/**
 * 识别 Provider 契约（报告 8.2）。
 */
public interface RecognitionProvider extends MusicProvider {

    RecognitionCapabilities capabilities();

    /** 不支持就不调用，避免“失败一次再切换” */
    default boolean supports(RecognitionInputType inputType) {
        return capabilities().inputTypes().contains(inputType);
    }

    default boolean supportsLiveAudio() {
        return false;
    }

    default int maxAudioDurationSeconds() {
        return 12;
    }

    /**
     * 执行识别。实现内部只做字段映射、错误归一、限流与重试所需的最小逻辑，
     * 选择、降级、熔断与计量由 ProviderManager 负责。
     */
    RecognitionResult recognize(RecognitionRequest request, RequestContext ctx) throws ProviderException;
}
