package com.musicbridge.provider.mock;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.transliteration.TransliterationProvider;
import com.musicbridge.provider.transliteration.TransliterationRequest;
import com.musicbridge.provider.transliteration.TransliterationResult;
import com.musicbridge.provider.transliteration.TransliterationScheme;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mock 音译 Provider：只转写读音，不改变语义；仅用于契约测试。
 */
@Component
public class MockTransliterationProvider implements TransliterationProvider {

    public static final String PROVIDER_ID = "mock-transliteration";

    private static final Map<String, String> PINYIN = Map.of(
            "晴天", "qing tian",
            "你好", "ni hao");

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.TRANSLITERATION;
    }

    @Override
    public TransliterationResult transliterate(TransliterationRequest request, RequestContext ctx) {
        List<String> lines = new ArrayList<>();
        for (String line : request.lines()) {
            if (request.scheme() == TransliterationScheme.PINYIN && PINYIN.containsKey(line)) {
                lines.add(PINYIN.get(line));
            } else {
                lines.add("[" + request.scheme().name().toLowerCase() + "] " + line);
            }
        }
        return new TransliterationResult(lines, request.scheme(), id(),
                new SourceRef(id(), "mock-transliteration-0001", "MOCK", Instant.now()));
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
