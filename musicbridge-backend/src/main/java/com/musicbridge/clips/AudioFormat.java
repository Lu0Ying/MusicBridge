package com.musicbridge.clips;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 已冻结的音频格式：扩展名、允许的 MIME 与容器魔数判定。
 *
 * <p>魔数判定用于服务端内容校验，避免只信任扩展名与 MIME。判定口径见
 * {@code research/musicbridge-phase1/CLIPS_CONTRACT.md}。</p>
 */
public enum AudioFormat {

    MP3(".mp3", "audio/mpeg", Set.of("audio/mpeg")),
    M4A(".m4a", "audio/mp4", Set.of("audio/mp4", "audio/x-m4a")),
    WAV(".wav", "audio/wav", Set.of("audio/wav", "audio/x-wav", "audio/wave")),
    FLAC(".flac", "audio/flac", Set.of("audio/flac", "audio/x-flac")),
    OGG(".ogg", "audio/ogg", Set.of("audio/ogg", "application/ogg"));

    private final String extension;
    private final String canonicalContentType;
    private final Set<String> allowedContentTypes;

    AudioFormat(String extension, String canonicalContentType, Set<String> allowedContentTypes) {
        this.extension = extension;
        this.canonicalContentType = canonicalContentType;
        this.allowedContentTypes = allowedContentTypes;
    }

    public String extension() {
        return extension;
    }

    public String canonicalContentType() {
        return canonicalContentType;
    }

    /**
     * MIME 校验：空值或 {@code application/octet-stream} 视为客户端未知，不参与判定；
     * 其余必须命中该格式的白名单。
     */
    public boolean acceptsContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if ("application/octet-stream".equals(normalized)) {
            return true;
        }
        return allowedContentTypes.contains(normalized);
    }

    public static Optional<AudioFormat> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 按容器特征识别实际格式；无法识别（含独立 AAC）返回空。
     */
    public static Optional<AudioFormat> detect(byte[] head) {
        if (head == null || head.length < 12) {
            return Optional.empty();
        }
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return Optional.of(MP3);
        }
        int first = head[0] & 0xFF;
        int second = head[1] & 0xFF;
        if (first == 0xFF && (second & 0xE0) == 0xE0) {
            // MP3 帧同步：版本位不能是保留值 01，层位不能是保留值 00（00 对应 ADTS/AAC）
            int version = (second >> 3) & 0x03;
            int layer = (second >> 1) & 0x03;
            if (version != 0x01 && layer != 0x00) {
                return Optional.of(MP3);
            }
            return Optional.empty();
        }
        if (matches(head, 0, "RIFF") && matches(head, 8, "WAVE")) {
            return Optional.of(WAV);
        }
        if (matches(head, 0, "fLaC")) {
            return Optional.of(FLAC);
        }
        if (matches(head, 0, "OggS")) {
            return Optional.of(OGG);
        }
        if (matches(head, 4, "ftyp") && isM4aBrand(head)) {
            return Optional.of(M4A);
        }
        return Optional.empty();
    }

    private static boolean isM4aBrand(byte[] head) {
        return matches(head, 8, "M4A ")
                || matches(head, 8, "M4B ")
                || matches(head, 8, "mp42")
                || matches(head, 8, "isom")
                || matches(head, 8, "iso2");
    }

    private static boolean matches(byte[] data, int offset, String text) {
        if (data.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (data[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
