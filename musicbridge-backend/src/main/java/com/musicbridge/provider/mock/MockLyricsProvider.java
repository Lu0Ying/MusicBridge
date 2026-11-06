package com.musicbridge.provider.mock;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.lyrics.LyricLineDTO;
import com.musicbridge.provider.lyrics.LyricsDTO;
import com.musicbridge.provider.lyrics.LyricsKind;
import com.musicbridge.provider.lyrics.LyricsProvider;
import com.musicbridge.provider.lyrics.LyricsQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mock 歌词 Provider：普通与同步歌词共用同一 DTO。
 */
@Component
public class MockLyricsProvider implements LyricsProvider {

    public static final String PROVIDER_ID = "mock-lyrics";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.LYRICS;
    }

    @Override
    public LyricsDTO fetch(LyricsQuery query, RequestContext ctx) {
        SourceRef source = new SourceRef(id(), "mock-lyrics-0001", "MOCK", Instant.now());
        if (query != null && query.preferSynced()) {
            List<LyricLineDTO> lines = List.of(
                    new LyricLineDTO(1, 0, 3000, "故事的小黄花"),
                    new LyricLineDTO(2, 3000, 6500, "从出生那年就飘着"));
            return new LyricsDTO("mock-lyrics-id-0001", null, LyricsKind.ORIGINAL,
                    query.language() == null ? "zh" : query.language(), null, lines, source);
        }
        String language = query == null || query.language() == null ? "zh" : query.language();
        return new LyricsDTO("mock-lyrics-id-0001", null, LyricsKind.ORIGINAL, language,
                "故事的小黄花\n从出生那年就飘着", List.of(), source);
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
