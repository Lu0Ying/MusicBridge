package com.musicbridge.clips;

import com.musicbridge.security.AccessForbiddenException;
import com.musicbridge.security.ConsentService;
import com.musicbridge.security.TempFragmentService;
import com.musicbridge.security.UploadConsentRecord;
import com.musicbridge.web.InvalidRequestException;
import com.musicbridge.web.PayloadTooLargeException;
import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.web.UnsupportedMediaTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 片段上传、查询、删除与过期清理。
 *
 * <p>归属用户只来自调用方传入的认证上下文；所有跨用户访问统一按 404 处理。
 * 时长以服务端探测为权威值，客户端提示值仅用于一致性校验。</p>
 */
@Service
public class ClipService {

    private static final Logger log = LoggerFactory.getLogger(ClipService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int HEAD_BYTES = 32;

    private final ClipRepository repository;
    private final ConsentService consentService;
    private final TempFragmentService fragments;
    private final AudioMetadataProbe probe;
    private final ClipProperties properties;
    private final Clock clock;

    public ClipService(ClipRepository repository,
                       ConsentService consentService,
                       TempFragmentService fragments,
                       AudioMetadataProbe probe,
                       ClipProperties properties,
                       Clock clock) {
        this.repository = repository;
        this.consentService = consentService;
        this.fragments = fragments;
        this.probe = probe;
        this.properties = properties;
        this.clock = clock;
    }

    public ClipResponse create(String userId, MultipartFile file, String consentIdRaw, String formatRaw,
                               Long durationHint, String requestFileName) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("文件不能为空");
        }
        AudioFormat format = AudioFormat.parse(formatRaw)
                .orElseThrow(() -> new InvalidRequestException("format 仅支持 MP3/M4A/WAV/FLAC/OGG"));
        String consentId = requireUuid(consentIdRaw, "consentId 格式非法");
        String displayName = sanitizeFileName(requestFileName);

        validateContentType(format, file.getContentType());
        validateFileNameExtension(displayName, format);
        validateDetectedFormat(file, format);

        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new PayloadTooLargeException("文件超过 25 MB 上限");
        }
        if (durationHint != null && (durationHint < properties.getMinDurationMs()
                || durationHint > properties.getMaxDurationMs())) {
            throw new InvalidRequestException("durationMs 超出允许范围");
        }

        UploadConsentRecord consent = requireUsableConsent(userId, consentId);

        UUID clipId = UUID.randomUUID();
        Path path = fragments.beginUpload(userId, consentId, clipId + format.extension());
        boolean stored = false;
        try {
            Files.createDirectories(path.getParent());
            file.transferTo(path);
            // 写入完成前再次校验授权，防止撤销竞态
            requireUsableConsent(userId, consentId);
            long durationMs = probeDuration(path);
            if (durationMs < properties.getMinDurationMs()) {
                throw new InvalidRequestException("音频时长过短");
            }
            if (durationMs > properties.getMaxDurationMs()) {
                throw new InvalidRequestException("音频时长超过 5 分钟");
            }
            if (durationHint != null
                    && Math.abs(durationHint - durationMs) > properties.getDurationToleranceMs()) {
                throw new InvalidRequestException("客户端时长与实测时长偏差过大");
            }

            Instant now = clock.instant();
            Instant expiresAt = now.plus(Duration.ofDays(consent.retentionDays()));
            ClipRecord record = new ClipRecord(clipId.toString(), userId, consentId, format.name(),
                    format.canonicalContentType(), file.getSize(), durationMs, displayName,
                    clipId + format.extension(), ClipStatus.STORED, now, expiresAt);
            repository.insert(record);
            stored = true;
            return toResponse(record);
        } catch (IOException e) {
            throw new InvalidRequestException("文件写入失败");
        } finally {
            fragments.completeUpload(path);
            if (!stored) {
                deleteQuietly(path);
            }
        }
    }

    public ClipResponse get(String userId, String clipIdRaw) {
        String clipId = requireUuid(clipIdRaw, "clipId 格式非法");
        ClipRecord record = repository.findByIdAndUserId(clipId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("片段不存在"));
        if (record.status() == ClipStatus.EXPIRED || !clock.instant().isBefore(record.expiresAt())) {
            throw new ResourceNotFoundException("片段不存在");
        }
        return toResponse(record);
    }

    /**
     * 单片段删除的服务层能力（不暴露 HTTP）：归属校验失败视为未删除，重复调用幂等。
     */
    public boolean delete(String userId, String clipIdRaw) {
        String clipId = requireUuid(clipIdRaw, "clipId 格式非法");
        Optional<ClipRecord> record = repository.findByIdAndUserId(clipId, userId);
        if (record.isEmpty() || !repository.deleteByIdAndUserId(clipId, userId)) {
            return false;
        }
        Path path = fragments.pathFor(userId, record.get().consentId(), record.get().storageName());
        deleteQuietly(path);
        removeEmptyParent(path);
        return true;
    }

    /**
     * 清理一批到期片段：标记 EXPIRED 并删除文件；单条失败不影响其余记录，可重复执行。
     */
    public int cleanupExpired() {
        Instant now = clock.instant();
        List<ClipRecord> candidates = repository.findNeedingCleanup(now, properties.getCleanupBatchSize());
        int processed = 0;
        for (ClipRecord record : candidates) {
            if (record.status() == ClipStatus.STORED && !repository.markExpired(record.id(), now)) {
                continue;
            }
            Path path = fragments.pathFor(record.userId(), record.consentId(), record.storageName());
            if (deleteQuietly(path)) {
                removeEmptyParent(path);
            }
            processed++;
        }
        return processed;
    }

    private long probeDuration(Path path) {
        try {
            return probe.probeDurationMs(path);
        } catch (AudioMetadataProbe.AudioProbeException e) {
            throw new UnsupportedMediaTypeException("无法解析音频时长");
        }
    }

    private UploadConsentRecord requireUsableConsent(String userId, String consentId) {
        UploadConsentRecord consent = consentService.requireActive(userId, consentId);
        if (!"RAW_AUDIO".equals(consent.scope())) {
            throw new AccessForbiddenException("授权范围不支持原始音频上传");
        }
        if (consent.retentionDays() < 1) {
            throw new AccessForbiddenException("授权保留期为 0，不允许上传");
        }
        return consent;
    }

    private void validateContentType(AudioFormat format, String contentType) {
        if (!format.acceptsContentType(contentType)) {
            throw new UnsupportedMediaTypeException("Content-Type 与声明格式不一致");
        }
    }

    private void validateFileNameExtension(String fileName, AudioFormat format) {
        if (fileName == null) {
            return;
        }
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(format.extension())) {
            throw new UnsupportedMediaTypeException("文件扩展名与声明格式不一致");
        }
    }

    private void validateDetectedFormat(MultipartFile file, AudioFormat declared) {
        byte[] head;
        try (InputStream input = file.getInputStream()) {
            head = input.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            throw new InvalidRequestException("文件无法读取");
        }
        AudioFormat detected = AudioFormat.detect(head)
                .orElseThrow(() -> new UnsupportedMediaTypeException("文件内容不是受支持的音频格式"));
        if (detected != declared) {
            throw new UnsupportedMediaTypeException("文件实际格式与声明不一致");
        }
    }

    private String requireUuid(String value, String message) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new InvalidRequestException(message);
        }
        return value;
    }

    /** 展示用文件名：去路径、去控制字符，超长拒绝；不允许参与落盘 */
    private String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (char ch : name.toCharArray()) {
            if (ch >= 0x20 && ch != 0x7F) {
                builder.append(ch);
            }
        }
        if (builder.length() > 120) {
            throw new InvalidRequestException("fileName 长度不能超过 120");
        }
        return builder.toString();
    }

    private boolean deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            log.warn("片段文件删除失败，交由后续清理重试: {}", e.getMessage());
            return false;
        }
    }

    /** 只尝试删除单层父目录；目录非空时静默跳过，不递归 */
    private void removeEmptyParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.deleteIfExists(parent);
        } catch (IOException e) {
            log.debug("片段目录非空或删除失败: {}", e.getMessage());
        }
    }

    private ClipResponse toResponse(ClipRecord record) {
        return new ClipResponse(UUID.fromString(record.id()), UUID.fromString(record.consentId()),
                record.format(), record.contentType(), record.sizeBytes(), (int) record.durationMs(),
                record.fileName(), record.createdAt(), record.expiresAt(), retentionDays(record),
                record.status());
    }

    private int retentionDays(ClipRecord record) {
        long seconds = Duration.between(record.createdAt(), record.expiresAt()).getSeconds();
        return (int) (seconds / 86_400);
    }
}
