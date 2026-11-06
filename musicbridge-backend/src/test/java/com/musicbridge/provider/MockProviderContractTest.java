package com.musicbridge.provider;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.error.ProviderErrorCode;
import com.musicbridge.provider.error.ProviderException;
import com.musicbridge.provider.lyrics.LyricsDTO;
import com.musicbridge.provider.lyrics.LyricsQuery;
import com.musicbridge.provider.metadata.MetadataQuery;
import com.musicbridge.provider.mock.MockLyricsProvider;
import com.musicbridge.provider.mock.MockMetadataProvider;
import com.musicbridge.provider.mock.MockRecognitionProvider;
import com.musicbridge.provider.mock.MockTranslationProvider;
import com.musicbridge.provider.mock.MockTransliterationProvider;
import com.musicbridge.provider.recognition.RecognitionCandidate;
import com.musicbridge.provider.recognition.RecognitionInputType;
import com.musicbridge.provider.recognition.RecognitionRequest;
import com.musicbridge.provider.recognition.RecognitionResult;
import com.musicbridge.provider.translation.TranslationRequest;
import com.musicbridge.provider.translation.TranslationResult;
import com.musicbridge.provider.transliteration.TransliterationRequest;
import com.musicbridge.provider.transliteration.TransliterationResult;
import com.musicbridge.provider.transliteration.TransliterationScheme;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock Provider 契约测试：校验统一 DTO 字段、输入类型支持与错误归一，不访问外部网络。
 */
class MockProviderContractTest {

    private final RequestContext ctx = RequestContext.create("test-user");

    @Test
    void recognitionSupportsBothInputTypes() {
        MockRecognitionProvider provider = new MockRecognitionProvider();
        assertEquals(ProviderCapability.RECOGNITION, provider.capability());
        assertTrue(provider.supports(RecognitionInputType.RAW_AUDIO));
        assertTrue(provider.supports(RecognitionInputType.AUDIO_FINGERPRINT));
        assertFalse(provider.supportsLiveAudio());
        assertTrue(provider.checkConfig().ready());
    }

    @Test
    void recognitionReturnsNormalizedCandidateWithoutRawResponse() {
        MockRecognitionProvider provider = new MockRecognitionProvider();
        RecognitionResult result = provider.recognize(
                new RecognitionRequest(RecognitionInputType.AUDIO_FINGERPRINT, null,
                        "AQADtEmi...fingerprint", Duration.ofSeconds(10), null, "zh"), ctx);

        assertTrue(result.hasMatch());
        assertEquals(MockRecognitionProvider.PROVIDER_ID, result.providerId());
        RecognitionCandidate candidate = result.candidates().get(0);
        assertEquals("晴天", candidate.title());
        assertEquals(List.of("周杰伦"), candidate.artists());
        assertEquals(0.92, candidate.confidence());
        assertTrue(candidate.externalIds().containsKey("MBID"));
        assertNotNull(candidate.candidateReleaseHint());
        assertTrue(candidate.debugReference().startsWith("mock://"));
    }

    @Test
    void rawAudioRequiresClipIdAndFingerprintRequiresFingerprint() {
        MockRecognitionProvider provider = new MockRecognitionProvider();
        ProviderException missingClip = assertThrows(ProviderException.class, () -> provider.recognize(
                new RecognitionRequest(RecognitionInputType.RAW_AUDIO, null, null,
                        Duration.ofSeconds(5), "consent-1", null), ctx));
        assertEquals(ProviderErrorCode.UNSUPPORTED_INPUT, missingClip.errorCode());

        ProviderException missingFingerprint = assertThrows(ProviderException.class, () -> provider.recognize(
                new RecognitionRequest(RecognitionInputType.AUDIO_FINGERPRINT, "clip-1", null,
                        Duration.ofSeconds(5), null, null), ctx));
        assertEquals(ProviderErrorCode.UNSUPPORTED_INPUT, missingFingerprint.errorCode());
    }

    @Test
    void metadataReturnsFourLayerRecordWithSource() {
        MockMetadataProvider provider = new MockMetadataProvider();
        var record = provider.lookup(MetadataQuery.byRecordingMbid("mock-recording-0001"), ctx);

        assertTrue(record.hasMatch());
        assertNotNull(record.work());
        assertNotNull(record.recording());
        assertEquals(1, record.releases().size());
        assertEquals(1, record.releaseTracks().size());
        assertEquals(record.work().mbid(), record.recording().workMbid());
        assertNotNull(record.source());
        assertEquals(MockMetadataProvider.PROVIDER_ID, record.source().provider());
    }

    @Test
    void lyricsSupportPlainAndSyncedShapes() {
        MockLyricsProvider provider = new MockLyricsProvider();
        LyricsDTO plain = provider.fetch(new LyricsQuery(null, "晴天", "周杰伦", null,
                Duration.ofSeconds(269), "zh", false), ctx);
        assertTrue(plain.hasMatch());
        assertTrue(plain.lines().isEmpty());
        assertEquals("zh", plain.language());

        LyricsDTO synced = provider.fetch(new LyricsQuery(null, "晴天", "周杰伦", null,
                Duration.ofSeconds(269), "zh", true), ctx);
        assertTrue(synced.hasMatch());
        assertEquals(2, synced.lines().size());
        assertEquals(0, synced.lines().get(0).startMs());
    }

    @Test
    void translationAndTransliterationKeepLineCount() {
        MockTranslationProvider translationProvider = new MockTranslationProvider();
        TranslationResult translation = translationProvider.translate(
                new TranslationRequest(List.of("你好，世界", "晴天"), "zh", "en", "mock-v1"), ctx);
        assertEquals(2, translation.lines().size());
        assertEquals("Hello, world", translation.lines().get(0));
        assertEquals("en", translation.targetLanguage());

        MockTransliterationProvider transliterationProvider = new MockTransliterationProvider();
        TransliterationResult transliteration = transliterationProvider.transliterate(
                new TransliterationRequest(List.of("晴天", "你好"), "zh", TransliterationScheme.PINYIN), ctx);
        assertEquals(2, transliteration.lines().size());
        assertEquals("qing tian", transliteration.lines().get(0));
        assertEquals(TransliterationScheme.PINYIN, transliteration.scheme());
    }
}
