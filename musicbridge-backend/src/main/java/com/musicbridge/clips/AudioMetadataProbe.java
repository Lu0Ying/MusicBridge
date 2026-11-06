package com.musicbridge.clips;

import java.nio.file.Path;

/**
 * 音频时长探测能力：业务层只依赖本接口，不直接使用 jaudiotagger。
 *
 * <p>已知限制：探测读取容器/元数据中记录的时长，不等于完整解码验证，
 * 不能保证抵御专门伪造时长字段的文件。</p>
 */
public interface AudioMetadataProbe {

    /**
     * 读取音频实际时长（毫秒）。
     *
     * @throws AudioProbeException 无法可靠解析时长或音频流异常
     */
    long probeDurationMs(Path file);

    /** 探测失败：时长缺失、为 0 或结构无法解析 */
    class AudioProbeException extends RuntimeException {

        public AudioProbeException(String message) {
            super(message);
        }

        public AudioProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
