package com.musicbridge.security;

import com.musicbridge.config.FragmentProperties;
import com.musicbridge.web.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地临时片段管理：按 用户（哈希目录）/ 授权 两级隔离，撤销授权时清理对应目录。
 *
 * <p>上传中的片段登记为 active，清理时跳过，等上传结束再次清理即可删除；
 * 单个文件删除失败只记录到结果，不阻断授权撤销。</p>
 */
@Service
public class TempFragmentService implements FragmentCleaner {

    private static final Logger log = LoggerFactory.getLogger(TempFragmentService.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Path root;
    private final Set<Path> active = ConcurrentHashMap.newKeySet();

    public TempFragmentService(FragmentProperties properties) {
        this.root = properties.getTempRoot().toAbsolutePath().normalize();
    }

    /** 声明一个即将写入的上传片段，返回其受控路径（避免路径穿越） */
    public Path beginUpload(String userId, String consentId, String fileName) {
        Path path = resolve(userId, consentId, fileName);
        active.add(path);
        return path;
    }

    /** 上传结束（成功或失败）后解除在途标记；失败后可直接清理 */
    public void completeUpload(Path path) {
        active.remove(path.toAbsolutePath().normalize());
    }

    @Override
    public CleanupResult cleanupForConsent(String userId, String consentId) {
        Path dir = userDir(userId).resolve(consentId);
        if (!Files.isDirectory(dir)) {
            return CleanupResult.empty();
        }
        int deleted = 0;
        int skippedActive = 0;
        List<String> failures = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                Path normalized = entry.toAbsolutePath().normalize();
                if (active.contains(normalized)) {
                    skippedActive++;
                    continue;
                }
                try {
                    if (Files.deleteIfExists(entry)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    failures.add(entry.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            failures.add("目录不可读: " + e.getMessage());
        }
        if (skippedActive == 0) {
            try {
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                log.debug("清理授权目录失败: {}", dir, e);
            }
        }
        if (!failures.isEmpty()) {
            log.warn("授权撤销清理存在失败项 user={} consent={} failures={}", userId, consentId, failures);
        }
        return new CleanupResult(deleted, skippedActive, failures);
    }

    public Set<Path> activeFragments() {
        return Set.copyOf(active);
    }

    private Path resolve(String userId, String consentId, String fileName) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidRequestException("userId 不能为空");
        }
        if (consentId == null || !UUID.matcher(consentId).matches()) {
            throw new InvalidRequestException("consentId 格式非法");
        }
        if (fileName == null || !SAFE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
            throw new InvalidRequestException("文件名非法");
        }
        Path dir = userDir(userId).resolve(consentId);
        Path path = dir.resolve(fileName).toAbsolutePath().normalize();
        if (!path.startsWith(dir.toAbsolutePath().normalize())) {
            throw new InvalidRequestException("文件路径越界");
        }
        return path;
    }

    private Path userDir(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidRequestException("userId 不能为空");
        }
        return root.resolve(hash(userId));
    }

    private String hash(String userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(value.length * 2);
            for (byte b : value) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
