package com.musicbridge.provider.mock;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.translation.TranslationProvider;
import com.musicbridge.provider.translation.TranslationRequest;
import com.musicbridge.provider.translation.TranslationResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mock 翻译 Provider：保持行数与行号，仅用于契约测试。
 */
@Component
public class MockTranslationProvider implements TranslationProvider {

    public static final String PROVIDER_ID = "mock-translation";

    private static final Map<String, String> DICTIONARY = Map.of(
            "你好，世界", "Hello, world",
            "晴天", "Sunny Day");

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.TRANSLATION;
    }

    @Override
    public TranslationResult translate(TranslationRequest request, RequestContext ctx) {
        List<String> translated = new ArrayList<>();
        for (String line : request.lines()) {
            translated.add(DICTIONARY.getOrDefault(line, "[en] " + line));
        }
        return new TranslationResult(translated, request.sourceLanguage(), request.targetLanguage(),
                id(), new SourceRef(id(), "mock-translation-0001", "MOCK", Instant.now()));
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
