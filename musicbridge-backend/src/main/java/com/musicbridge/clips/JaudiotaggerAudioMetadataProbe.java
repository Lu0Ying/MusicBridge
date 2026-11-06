package com.musicbridge.clips;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 基于 jaudiotagger 的时长探测实现（支持 MP3/M4A/WAV/FLAC/OGG）。
 *
 * <p>只读取音频头中的时长，不做标签读写；读取结果异常（缺失、≤0、非有限值）一律视为探测失败。</p>
 */
@Component
public class JaudiotaggerAudioMetadataProbe implements AudioMetadataProbe {

    @Override
    public long probeDurationMs(Path file) {
        try {
            AudioFile audioFile = AudioFileIO.read(file.toFile());
            AudioHeader header = audioFile.getAudioHeader();
            if (header == null) {
                throw new AudioProbeException("缺少音频头信息");
            }
            double seconds = header.getPreciseTrackLength();
            if (!(seconds > 0) || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                throw new AudioProbeException("音频时长无法解析");
            }
            long durationMs = Math.round(seconds * 1000);
            if (durationMs <= 0) {
                throw new AudioProbeException("音频时长为 0");
            }
            return durationMs;
        } catch (AudioProbeException e) {
            throw e;
        } catch (Exception e) {
            // 不向上层暴露驱动/库细节，只保留可读原因
            throw new AudioProbeException("音频文件无法解析", e);
        }
    }
}
