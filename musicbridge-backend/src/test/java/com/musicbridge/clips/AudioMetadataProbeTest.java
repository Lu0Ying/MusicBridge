package com.musicbridge.clips;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实样本测试：五种格式的时长识别、VBR、损坏与截断文件。
 *
 * <p>样本全部由 {@link AudioSampleFactory} 在测试内合成，不含下载文件。</p>
 */
class AudioMetadataProbeTest {

    @TempDir
    Path tempDir;

    private final AudioMetadataProbe probe = new JaudiotaggerAudioMetadataProbe();

    @Test
    void readsWavDuration() throws IOException {
        long duration = probe(write("sample.wav", AudioSampleFactory.wav(1_200)));
        assertEquals(1_200, duration, 60);
    }

    @Test
    void readsMp3CbrDuration() throws IOException {
        long duration = probe(write("sample.mp3", AudioSampleFactory.mp3Cbr(42, 44_100)));
        // 42 帧 × 1152 / 44100 ≈ 1097 ms
        assertTrue(duration >= 1_000 && duration <= 1_200, "实际时长 " + duration);
    }

    @Test
    void readsMp3VbrDuration() throws IOException {
        long duration = probe(write("vbr.mp3", AudioSampleFactory.mp3Vbr(40, 44_100)));
        // Xing 帧数 40 × 1152 / 44100 ≈ 1045 ms
        assertTrue(duration >= 950 && duration <= 1_150, "实际时长 " + duration);
    }

    @Test
    void readsFlacDuration() throws IOException {
        long duration = probe(write("sample.flac", AudioSampleFactory.flac(44_100L, 44_100)));
        assertEquals(1_000, duration, 60);
    }

    @Test
    void readsOggDuration() throws IOException {
        long duration = probe(write("sample.ogg", AudioSampleFactory.ogg(44_100L, 44_100)));
        assertEquals(1_000, duration, 60);
    }

    @Test
    void readsM4aDuration() throws IOException {
        long duration = probe(write("sample.m4a", AudioSampleFactory.m4a(1_000)));
        assertEquals(1_000, duration, 60);
    }

    @Test
    void rejectsCorruptContainer() throws IOException {
        byte[] corrupt = {'f', 'L', 'a', 'C', (byte) 0x80, 0x00, 0x00, 34, 1, 2, 3};
        Path file = write("corrupt.flac", corrupt);
        assertThrows(AudioMetadataProbe.AudioProbeException.class, () -> probe.probeDurationMs(file));
    }

    @Test
    void rejectsTruncatedM4a() throws IOException {
        byte[] truncated = AudioSampleFactory.truncated(AudioSampleFactory.m4a(1_000), 40);
        Path file = write("truncated.m4a", truncated);
        assertThrows(AudioMetadataProbe.AudioProbeException.class, () -> probe.probeDurationMs(file));
    }

    private long probe(Path file) {
        return probe.probeDurationMs(file);
    }

    private Path write(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }
}
