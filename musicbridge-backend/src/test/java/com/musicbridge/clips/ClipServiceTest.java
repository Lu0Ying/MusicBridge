package com.musicbridge.clips;

import com.musicbridge.config.FragmentProperties;
import com.musicbridge.security.AccessForbiddenException;
import com.musicbridge.security.ConsentService;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.security.TempFragmentService;
import com.musicbridge.security.UploadConsentRecord;
import com.musicbridge.web.InvalidRequestException;
import com.musicbridge.web.PayloadTooLargeException;
import com.musicbridge.web.UnsupportedMediaTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `/clips` 服务层契约测试：五格式、大小/MIME/扩展名/魔数、时长边界、提示偏差、授权与清理。
 *
 * <p>音频样本由 {@link AudioSampleFactory} 在测试内合成，不下载外部文件。</p>
 */
class ClipServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String USER_ID = "user-a";
    private static final String CONSENT_ID = "8c1b3a8d-5b6e-4e7a-9c2d-0f1a2b3c4d5e";

    @TempDir
    Path tempRoot;

    private ClipRepository repository;
    private ConsentService consentService;
    private TempFragmentService fragments;
    private ClipService service;
    private ClipProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(ClipRepository.class);
        consentService = mock(ConsentService.class);
        FragmentProperties fragmentProperties = new FragmentProperties();
        fragmentProperties.setTempRoot(tempRoot);
        fragments = new TempFragmentService(fragmentProperties);
        properties = new ClipProperties();
        service = new ClipService(repository, consentService, fragments,
                new JaudiotaggerAudioMetadataProbe(), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(consentService.requireActive(USER_ID, CONSENT_ID)).thenReturn(consent(30));
    }

    @Test
    void uploadsAllSupportedFormats() {
        ClipResponse wav = upload(AudioSampleFactory.wav(1_000), "WAV", "a.wav", "audio/wav", null);
        assertEquals("WAV", wav.format());
        assertEquals("audio/wav", wav.contentType());
        assertEquals(1_000, wav.durationMs(), 60);

        ClipResponse mp3 = upload(AudioSampleFactory.mp3Cbr(42, 44_100), "MP3", "b.mp3", "audio/mpeg", null);
        assertEquals("MP3", mp3.format());
        assertTrue(mp3.durationMs() >= 1_000 && mp3.durationMs() <= 1_200, "MP3 时长 " + mp3.durationMs());

        ClipResponse vbr = upload(AudioSampleFactory.mp3Vbr(40, 44_100), "MP3", "vbr.mp3", "audio/mpeg", null);
        assertTrue(vbr.durationMs() >= 950 && vbr.durationMs() <= 1_150, "VBR 时长 " + vbr.durationMs());

        ClipResponse m4a = upload(AudioSampleFactory.m4a(1_000), "M4A", "c.m4a", "audio/mp4", null);
        assertEquals(1_000, m4a.durationMs(), 60);

        ClipResponse flac = upload(AudioSampleFactory.flac(44_100, 44_100), "FLAC", "d.flac", "audio/flac", null);
        assertEquals(1_000, flac.durationMs(), 60);

        ClipResponse ogg = upload(AudioSampleFactory.ogg(44_100, 44_100), "OGG", "e.ogg", "audio/ogg", null);
        assertEquals(1_000, ogg.durationMs(), 60);

        verify(repository, org.mockito.Mockito.times(6)).insert(any());
    }

    @Test
    void rejectsFileLargerThan25MbByActualBytes() {
        byte[] oversized = new byte[(int) properties.getMaxSizeBytes() + 1];
        oversized[0] = 'R';
        oversized[1] = 'I';
        oversized[2] = 'F';
        oversized[3] = 'F';
        oversized[8] = 'W';
        oversized[9] = 'A';
        oversized[10] = 'V';
        oversized[11] = 'E';
        MockMultipartFile file = new MockMultipartFile("file", "big.wav", "audio/wav", oversized);

        assertThrows(PayloadTooLargeException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "big.wav"));
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsExtensionThatDoesNotMatchDeclaredFormat() {
        MockMultipartFile wavContent = wavFile("fake.mp3", AudioSampleFactory.wav(1_000), "audio/mpeg");

        assertThrows(UnsupportedMediaTypeException.class,
                () -> service.create(USER_ID, wavContent, CONSENT_ID, "MP3", null, "fake.mp3"));
    }

    @Test
    void rejectsMimeThatDoesNotMatchDeclaredFormat() {
        MockMultipartFile file = wavFile("real.wav", AudioSampleFactory.wav(1_000), "audio/mpeg");

        assertThrows(UnsupportedMediaTypeException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "real.wav"));
    }

    @Test
    void rejectsMagicBytesThatDoNotMatchDeclaredFormat() {
        MockMultipartFile file = wavFile("lied.wav", AudioSampleFactory.flac(44_100, 44_100), "audio/wav");

        UnsupportedMediaTypeException exception = assertThrows(UnsupportedMediaTypeException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "lied.wav"));
        assertEquals("文件实际格式与声明不一致", exception.getMessage());
    }

    @Test
    void rejectsDurationBelowMinimum() {
        MockMultipartFile file = wavFile("short.wav", AudioSampleFactory.wav(400), "audio/wav");

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "short.wav"));
        assertEquals("音频时长过短", exception.getMessage());
    }

    @Test
    void acceptsExactlyFiveMinutes() {
        ClipResponse response = upload(AudioSampleFactory.wav(300_000), "WAV", "max.wav", "audio/wav", null);
        assertEquals(300_000, response.durationMs());
    }

    @Test
    void rejectsDurationAboveMaximum() {
        MockMultipartFile file = wavFile("long.wav", AudioSampleFactory.wav(301_000), "audio/wav");

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "long.wav"));
        assertEquals("音频时长超过 5 分钟", exception.getMessage());
    }

    @Test
    void acceptsClientHintWithinTwoSeconds() {
        ClipResponse response = upload(AudioSampleFactory.wav(1_000), "WAV", "hint.wav", "audio/wav", 3_000L);
        assertEquals(1_000, response.durationMs(), 60);
    }

    @Test
    void rejectsClientHintBeyondTwoSeconds() {
        MockMultipartFile file = wavFile("hint.wav", AudioSampleFactory.wav(1_000), "audio/wav");

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", 3_001L, "hint.wav"));
        assertEquals("客户端时长与实测时长偏差过大", exception.getMessage());
    }

    @Test
    void rejectsZeroRetentionConsent() {
        when(consentService.requireActive(USER_ID, CONSENT_ID)).thenReturn(consent(0));
        MockMultipartFile file = wavFile("a.wav", AudioSampleFactory.wav(1_000), "audio/wav");

        assertThrows(AccessForbiddenException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "a.wav"));
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsRevokedConsentBeforeWrite() throws IOException {
        when(consentService.requireActive(USER_ID, CONSENT_ID))
                .thenThrow(new AccessForbiddenException("上传授权已撤销"));
        MockMultipartFile file = wavFile("a.wav", AudioSampleFactory.wav(1_000), "audio/wav");

        assertThrows(AccessForbiddenException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "a.wav"));
        verify(repository, never()).insert(any());
        assertTrue(isDirectoryEmpty(tempRoot.resolve(hash(USER_ID))), "拒绝上传不得残留文件");
    }

    @Test
    void rechecksConsentAfterWriteToCloseRevocationRace() throws IOException {
        when(consentService.requireActive(USER_ID, CONSENT_ID))
                .thenReturn(consent(30))
                .thenThrow(new AccessForbiddenException("上传授权已撤销"));
        MockMultipartFile file = wavFile("a.wav", AudioSampleFactory.wav(1_000), "audio/wav");

        assertThrows(AccessForbiddenException.class,
                () -> service.create(USER_ID, file, CONSENT_ID, "WAV", null, "a.wav"));
        verify(repository, never()).insert(any());
        assertTrue(isDirectoryEmpty(tempRoot.resolve(hash(USER_ID))), "撤销竞态下不得残留文件");
    }

    @Test
    void getTreatsOtherUserClipAsNotFound() {
        String clipId = UUID.randomUUID().toString();
        when(repository.findByIdAndUserId(clipId, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(USER_ID, clipId));
    }

    @Test
    void getTreatsExpiredClipAsNotFound() {
        String clipId = UUID.randomUUID().toString();
        ClipRecord expired = record(clipId, NOW.minusSeconds(1));
        when(repository.findByIdAndUserId(clipId, USER_ID)).thenReturn(Optional.of(expired));

        assertThrows(ResourceNotFoundException.class, () -> service.get(USER_ID, clipId));
    }

    @Test
    void getTreatsExpiredStatusAsNotFound() {
        String clipId = UUID.randomUUID().toString();
        ClipRecord record = record(clipId, NOW.plusSeconds(86_400)).asExpired();
        when(repository.findByIdAndUserId(clipId, USER_ID)).thenReturn(Optional.of(record));

        assertThrows(ResourceNotFoundException.class, () -> service.get(USER_ID, clipId));
    }

    @Test
    void cleanupMarksExpiredAndDeletesFileIdempotently() throws IOException {
        String clipId = UUID.randomUUID().toString();
        ClipRecord record = record(clipId, NOW.minusSeconds(60));
        Path file = fragments.pathFor(USER_ID, CONSENT_ID, record.storageName());
        Files.createDirectories(file.getParent());
        Files.write(file, AudioSampleFactory.wav(1_000));

        when(repository.findNeedingCleanup(NOW, properties.getCleanupBatchSize()))
                .thenReturn(List.of(record))
                .thenReturn(List.of());
        when(repository.markExpired(clipId, NOW)).thenReturn(true);

        assertEquals(1, service.cleanupExpired());
        assertFalse(Files.exists(file), "到期文件必须被删除");
        assertEquals(0, service.cleanupExpired(), "重复清理必须幂等");
    }

    @Test
    void deleteIsOwnershipScopedAndIdempotent() throws IOException {
        String clipId = UUID.randomUUID().toString();
        ClipRecord record = record(clipId, NOW.plusSeconds(86_400));
        Path file = fragments.pathFor(USER_ID, CONSENT_ID, record.storageName());
        Files.createDirectories(file.getParent());
        Files.write(file, AudioSampleFactory.wav(1_000));

        when(repository.findByIdAndUserId(clipId, USER_ID))
                .thenReturn(Optional.of(record))
                .thenReturn(Optional.empty());
        when(repository.deleteByIdAndUserId(clipId, USER_ID)).thenReturn(true);

        assertTrue(service.delete(USER_ID, clipId));
        assertFalse(Files.exists(file));
        assertFalse(service.delete(USER_ID, clipId), "重复删除必须返回 false");
    }

    @Test
    void responseDoesNotExposeStoragePathOrOwner() {
        ClipResponse response = upload(AudioSampleFactory.wav(1_000), "WAV", "a.wav", "audio/wav", null);

        assertFalse(response.toString().contains(USER_ID));
        assertFalse(response.toString().contains(tempRoot.toString()));
        assertFalse(response.toString().contains("storageName"));
    }

    private ClipResponse upload(byte[] content, String format, String fileName, String contentType,
                                Long durationHint) {
        ArgumentCaptor<ClipRecord> inserted = ArgumentCaptor.forClass(ClipRecord.class);
        ClipResponse response = service.create(USER_ID, new MockMultipartFile("file", fileName, contentType, content),
                CONSENT_ID, format, durationHint, fileName);
        verify(repository, org.mockito.Mockito.atLeastOnce()).insert(inserted.capture());
        ClipRecord record = inserted.getValue();
        // 落盘文件真实存在，且响应中的时长与入库值一致
        Path stored = fragments.pathFor(USER_ID, CONSENT_ID, record.storageName());
        assertTrue(Files.exists(stored), "上传成功后应存在落盘文件");
        assertEquals(record.durationMs(), response.durationMs());
        return response;
    }

    private MockMultipartFile wavFile(String fileName, byte[] content, String contentType) {
        return new MockMultipartFile("file", fileName, contentType, content);
    }

    private UploadConsentRecord consent(int retentionDays) {
        return new UploadConsentRecord(CONSENT_ID, USER_ID, "RAW_AUDIO", "测试上传", NOW, null, null,
                retentionDays);
    }

    private ClipRecord record(String clipId, Instant expiresAt) {
        return new ClipRecord(clipId, USER_ID, CONSENT_ID, "WAV", "audio/wav", 8_000, 1_000,
                "a.wav", clipId + ".wav", ClipStatus.STORED, NOW.minusSeconds(3_600), expiresAt);
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (Stream<Path> entries = Files.walk(dir)) {
            return entries.noneMatch(path -> Files.isRegularFile(path));
        }
    }

    private String hash(String userId) {
        // 与 TempFragmentService 相同的目录规则，仅用于断言残留
        return fragments.pathFor(userId, CONSENT_ID, "probe.wav").getParent().getParent().getFileName().toString();
    }
}
