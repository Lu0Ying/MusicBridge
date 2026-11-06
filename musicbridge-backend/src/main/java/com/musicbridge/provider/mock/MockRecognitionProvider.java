package com.musicbridge.provider.mock;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.recognition.DefaultRecognitionCapabilities;
import com.musicbridge.provider.recognition.RecognitionCapabilities;
import com.musicbridge.provider.recognition.RecognitionCandidate;
import com.musicbridge.provider.recognition.RecognitionInputType;
import com.musicbridge.provider.recognition.RecognitionProvider;
import com.musicbridge.provider.recognition.RecognitionRequest;
import com.musicbridge.provider.recognition.RecognitionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mock 识别 Provider：仅用于开发、测试与契约测试，生产配置禁止启用。
 * <p>固定样本覆盖 RAW_AUDIO 与 AUDIO_FINGERPRINT 双输入路由。</p>
 */
@Component
public class MockRecognitionProvider implements RecognitionProvider {

    public static final String PROVIDER_ID = "mock-recognition";

    private static final RecognitionCapabilities CAPABILITIES = new DefaultRecognitionCapabilities(
            Set.of(RecognitionInputType.RAW_AUDIO, RecognitionInputType.AUDIO_FINGERPRINT),
            true, true, false);

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.RECOGNITION;
    }

    @Override
    public RecognitionCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public int maxAudioDurationSeconds() {
        return 12;
    }

    @Override
    public RecognitionResult recognize(RecognitionRequest request, RequestContext ctx) {
        if (request == null || request.inputType() == null) {
            throw ProviderException.unsupportedInput(id(), "缺少 inputType");
        }
        if (request.inputType() == RecognitionInputType.RAW_AUDIO
                && (request.clipId() == null || request.clipId().isBlank())) {
            throw ProviderException.unsupportedInput(id(), "RAW_AUDIO 缺少 clipId");
        }
        if (request.inputType() == RecognitionInputType.AUDIO_FINGERPRINT
                && (request.fingerprint() == null || request.fingerprint().isBlank()
                || request.audioDuration() == null)) {
            throw ProviderException.unsupportedInput(id(), "AUDIO_FINGERPRINT 缺少 fingerprint 或时长");
        }
        RecognitionCandidate candidate = new RecognitionCandidate(
                "晴天",
                List.of("周杰伦"),
                "叶惠美",
                Duration.ofSeconds(269),
                LocalDate.of(2003, 7, 31),
                0.92,
                Map.of("MBID", "mock-mbid-0001", "ISRC", "MOCK-ISRC-0001"),
                "mock://recognition/0001");
        return new RecognitionResult(List.of(candidate), id(), Instant.now());
    }

    @Override
    public ProviderConfigStatus checkConfig() {
        return ProviderConfigStatus.ok();
    }

    @Override
    public ProviderHealth probeHealth() {
        return ProviderHealth.ok("Mock Provider 不发起外部调用");
    }
}
