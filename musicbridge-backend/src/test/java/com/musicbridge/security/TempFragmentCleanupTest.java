package com.musicbridge.security;

import com.musicbridge.config.FragmentProperties;
import com.musicbridge.web.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-07：授权撤销后的本地临时片段清理。
 *
 * <p>覆盖用户隔离、在途上传跳过与二次清理、非法文件名拒绝，以及撤销失败补偿不阻断撤销。</p>
 */
class TempFragmentCleanupTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private TempFragmentService fragments;

    @BeforeEach
    void setUp() {
        FragmentProperties properties = new FragmentProperties();
        properties.setTempRoot(tempDir);
        fragments = new TempFragmentService(properties);
    }

    @Test
    void cleanupRemovesOwnerFragmentsAndKeepsOtherUsers() throws IOException {
        String consentId = UUID.randomUUID().toString();
        Path ownerA = writeFragment("user-a", consentId, "a-1.raw");
        Path ownerB = writeFragment("user-a", consentId, "a-2.raw");
        Path other = writeFragment("user-b", consentId, "b-1.raw");

        FragmentCleaner.CleanupResult result = fragments.cleanupForConsent("user-a", consentId);

        assertEquals(2, result.deleted());
        assertEquals(0, result.skippedActive());
        assertTrue(result.failures().isEmpty());
        assertFalse(Files.exists(ownerA));
        assertFalse(Files.exists(ownerB));
        assertTrue(Files.exists(other), "不得删除其他用户的同名授权目录");
        assertTrue(Files.exists(other.getParent()), "其他用户目录必须保留");
    }

    @Test
    void activeFragmentIsSkippedThenRemovedAfterUploadCompletes() throws IOException {
        String consentId = UUID.randomUUID().toString();
        Path uploading = fragments.beginUpload("user-a", consentId, "in-flight.raw");
        Files.createDirectories(uploading.getParent());
        Files.writeString(uploading, "streaming", StandardCharsets.UTF_8);
        Path finished = writeFragment("user-a", consentId, "done.raw");

        FragmentCleaner.CleanupResult first = fragments.cleanupForConsent("user-a", consentId);

        assertEquals(1, first.deleted(), "已完成的片段应被删除");
        assertEquals(1, first.skippedActive(), "在途上传必须跳过");
        assertTrue(Files.exists(uploading));
        assertFalse(Files.exists(finished));
        assertTrue(Files.exists(uploading.getParent()), "仍有在途片段时不得删除目录");

        fragments.completeUpload(uploading);
        FragmentCleaner.CleanupResult second = fragments.cleanupForConsent("user-a", consentId);

        assertEquals(1, second.deleted(), "上传结束后再次清理应删除残留");
        assertEquals(0, second.skippedActive());
        assertFalse(Files.exists(uploading));
        assertFalse(Files.exists(uploading.getParent()), "全部清理后应移除授权目录");
    }

    @Test
    void cleanupOfUnknownConsentIsNoOp() {
        FragmentCleaner.CleanupResult result = fragments.cleanupForConsent("user-a", UUID.randomUUID().toString());

        assertEquals(0, result.deleted());
        assertEquals(0, result.skippedActive());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void rejectsPathTraversalAndInvalidInput() {
        String consentId = UUID.randomUUID().toString();

        assertThrows(InvalidRequestException.class,
                () -> fragments.beginUpload("user-a", consentId, "../evil.raw"));
        assertThrows(InvalidRequestException.class,
                () -> fragments.beginUpload("user-a", consentId, "a..b.raw"));
        assertThrows(InvalidRequestException.class,
                () -> fragments.beginUpload("user-a", "not-a-uuid", "ok.raw"));
        assertThrows(InvalidRequestException.class,
                () -> fragments.beginUpload(" ", consentId, "ok.raw"));
        assertThrows(InvalidRequestException.class,
                () -> fragments.beginUpload("user-a", consentId, "bad/name.raw"));
    }

    @Test
    void revokeTriggersCleanupOnlyForOwner() throws IOException {
        InMemoryConsentRepository repository = new InMemoryConsentRepository();
        ConsentService service = new ConsentService(repository, fixedClock(), fragments);

        String ownerConsent = service.create("user-a",
                new ConsentService.ConsentCreateRequest("RAW_AUDIO", "recognition", 1, null)).id();
        String otherConsent = service.create("user-b",
                new ConsentService.ConsentCreateRequest("RAW_AUDIO", "recognition", 1, null)).id();

        Path ownerFragment = writeFragment("user-a", ownerConsent, "upload.raw");
        Path otherFragment = writeFragment("user-b", otherConsent, "upload.raw");

        ConsentService.ConsentDTO revoked = service.revoke("user-a", ownerConsent);

        assertFalse(revoked.active());
        assertNotNull(revoked.revokedAt());
        assertFalse(Files.exists(ownerFragment), "撤销后应清理本人片段");
        assertTrue(Files.exists(otherFragment), "不得误删其他用户片段");
    }

    @Test
    void cleanupFailureDoesNotRollbackRevocation() throws IOException {
        InMemoryConsentRepository repository = new InMemoryConsentRepository();
        FragmentCleaner failing = (userId, consentId) -> {
            throw new IllegalStateException("磁盘不可写");
        };
        ConsentService service = new ConsentService(repository, fixedClock(), failing);

        String consentId = service.create("user-a",
                new ConsentService.ConsentCreateRequest("RAW_AUDIO", "recognition", 1, null)).id();
        writeFragment("user-a", consentId, "upload.raw");

        ConsentService.ConsentDTO revoked = service.revoke("user-a", consentId);

        assertFalse(revoked.active(), "清理失败不得回滚撤销");
        assertNotNull(revoked.revokedAt());
        assertTrue(repository.findByIdAndUserId(consentId, "user-a").orElseThrow().revokedAt() != null);
    }

    private Path writeFragment(String userId, String consentId, String fileName) throws IOException {
        Path path = fragments.beginUpload(userId, consentId, fileName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "fragment", StandardCharsets.UTF_8);
        fragments.completeUpload(path);
        return path;
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class InMemoryConsentRepository implements ConsentRepository {

        private final Map<String, UploadConsentRecord> byId = new LinkedHashMap<>();

        @Override
        public void insert(UploadConsentRecord consent) {
            byId.put(consent.id(), consent);
        }

        @Override
        public Optional<UploadConsentRecord> findByIdAndUserId(String id, String userId) {
            UploadConsentRecord consent = byId.get(id);
            return consent != null && consent.userId().equals(userId) ? Optional.of(consent) : Optional.empty();
        }

        @Override
        public List<UploadConsentRecord> findByUserId(String userId) {
            return byId.values().stream().filter(consent -> consent.userId().equals(userId)).toList();
        }

        @Override
        public boolean revoke(String id, String userId, Instant now) {
            UploadConsentRecord consent = byId.get(id);
            if (consent == null || !consent.userId().equals(userId) || consent.revokedAt() != null) {
                return false;
            }
            byId.put(id, new UploadConsentRecord(consent.id(), consent.userId(), consent.scope(),
                    consent.purpose(), consent.grantedAt(), now, consent.expiresAt(), consent.retentionDays()));
            return true;
        }
    }
}
