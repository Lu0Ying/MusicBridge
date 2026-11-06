package com.musicbridge.clips;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 测试用音频样本生成器：全部在测试内合成，不依赖外部下载或编码器。
 *
 * <p>生成的容器结构足以让 jaudiotagger 读取到容器/元数据中的时长；
 * 不代表完整可解码音频，仅用于契约校验测试。</p>
 */
final class AudioSampleFactory {

    private AudioSampleFactory() {
    }

    /** 8 kHz、8 bit、单声道 PCM WAV，数据字节 = 8 × 毫秒数 */
    static byte[] wav(int durationMs) {
        int dataSize = 8 * durationMs;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(buffer, "RIFF");
        buffer.putInt(36 + dataSize);
        putAscii(buffer, "WAVE");
        putAscii(buffer, "fmt ");
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(8_000);
        buffer.putInt(8_000);
        buffer.putShort((short) 1);
        buffer.putShort((short) 8);
        putAscii(buffer, "data");
        buffer.putInt(dataSize);
        for (int i = 0; i < dataSize; i++) {
            buffer.put((byte) (i % 64 < 32 ? 0x80 : 0x92));
        }
        return buffer.array();
    }

    /** FLAC：仅 STREAMINFO（totalSamples 决定时长），无音频帧 */
    static byte[] flac(long totalSamples, int sampleRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "fLaC");
        out.write(0x00); // metadata block，type 0 (STREAMINFO)，非最后一块
        out.write(0x00);
        out.write(0x00);
        out.write(34);
        ByteBuffer info = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN);
        info.putShort((short) 4096);
        info.putShort((short) 4096);
        info.put((byte) 0).put((byte) 0).put((byte) 0);
        info.put((byte) 0).put((byte) 0).put((byte) 0);
        long packed = ((long) sampleRate & 0xFFFFFL) << 44
                | 0L << 41
                | 15L << 36
                | (totalSamples & 0xFFFFFFFFFL);
        info.putLong(packed);
        info.put(new byte[16]); // MD5 占位
        out.writeBytes(info.array());
        // PADDING（最后一块）：jaudiotagger 要求文件大于 100 字节才会尝试解析
        out.write(0x81);
        out.write(0x00);
        out.write(0x02);
        out.write(0x00);
        out.writeBytes(new byte[512]);
        return out.toByteArray();
    }

    /** Ogg Vorbis：识别头页 + 注释页 + 结束页（granule = 总采样数） */
    static byte[] ogg(long totalSamples, int sampleRate) {
        int serial = 0x1234;
        byte[] idHeader = vorbisIdHeader(sampleRate);
        byte[] commentHeader = vorbisCommentHeader();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(oggPage(0x02, 0L, serial, 0, idHeader));
        out.writeBytes(oggPage(0x00, 0L, serial, 1, commentHeader));
        out.writeBytes(oggPage(0x04, totalSamples, serial, 2, new byte[0]));
        return out.toByteArray();
    }

    /** MP3 CBR（无 Xing）：帧数 × 帧时长 */
    static byte[] mp3Cbr(int frameCount, int sampleRate) {
        int bitrate = 128_000;
        int frameLength = (144 * bitrate) / sampleRate;
        int sampleRateIndex = sampleRate == 48_000 ? 1 : 0;
        byte[] frame = new byte[frameLength];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) (0x90 | (sampleRateIndex << 2));
        frame[3] = (byte) 0x64;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frameCount; i++) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** MP3 VBR：首帧含 Xing 帧数，后续帧码率交替 */
    static byte[] mp3Vbr(int frameCount, int sampleRate) {
        int sampleRateIndex = sampleRate == 48_000 ? 1 : 0;
        int bitrateIndex128 = 0x90;
        int bitrateIndex192 = 0xB0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frameCount; i++) {
            int bitrate = (i % 2 == 0) ? 128_000 : 192_000;
            int frameLength = (144 * bitrate) / sampleRate;
            byte[] frame = new byte[frameLength];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) ((i % 2 == 0 ? bitrateIndex128 : bitrateIndex192) | (sampleRateIndex << 2));
            frame[3] = (byte) 0x64;
            if (i == 0) {
                // MPEG1 立体声侧信息 32 字节，Xing 从第 36 字节开始
                int offset = 4 + 32;
                frame[offset] = 'X';
                frame[offset + 1] = 'i';
                frame[offset + 2] = 'n';
                frame[offset + 3] = 'g';
                frame[offset + 4] = 0;
                frame[offset + 5] = 0;
                frame[offset + 6] = 0;
                frame[offset + 7] = 0x01; // flags: frames
                writeIntBigEndian(frame, offset + 8, frameCount);
            }
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** M4A（ISO BMFF）：ftyp + moov(mvhd/trak(mdia(mdhd/hdlr/minf(stbl(stsd/stts/stsz/stco)))) + mdat */
    static byte[] m4a(int durationMs) {
        int timescale = 1000;
        byte[] ftyp = atom("ftyp", ascii("M4A "), int32(0), ascii("M4A "), ascii("isom"), ascii("mp42"));
        byte[] mvhd = mvhd(timescale, durationMs);
        byte[] tkhd = tkhd(durationMs);
        byte[] mdhd = mdhd(timescale, durationMs);
        byte[] hdlr = atom("hdlr", fullBox(0, 0), ascii("soun"), new byte[12], new byte[]{0});
        byte[] mp4a = mp4aEntry();
        byte[] stsd = atom("stsd", fullBox(0, 0), int32(1), mp4a);
        byte[] stts = atom("stts", fullBox(0, 0), int32(1), int32(1), int32(durationMs));
        byte[] stsz = atom("stsz", fullBox(0, 0), int32(0), int32(1), int32(75));
        byte[] stco = atom("stco", fullBox(0, 0), int32(1), int32(0));
        byte[] stbl = atom("stbl", stsd, stts, stsz, stco);
        // smhd 是 jaudiotagger 判定「音频轨」的必需标记，缺失会被当作非音频拒绝
        byte[] smhd = atom("smhd", fullBox(0, 0), new byte[2], new byte[2]);
        byte[] minf = atom("minf", smhd, stbl);
        byte[] mdia = atom("mdia", mdhd, hdlr, minf);
        byte[] trak = atom("trak", tkhd, mdia);
        byte[] moov = atom("moov", mvhd, trak);
        byte[] mdat = atom("mdat", new byte[75]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp);
        out.writeBytes(moov);
        out.writeBytes(mdat);
        return out.toByteArray();
    }

    /** 截断样本：保留头部但破坏长度/结构 */
    static byte[] truncated(byte[] source, int keepBytes) {
        byte[] result = new byte[Math.min(keepBytes, source.length)];
        System.arraycopy(source, 0, result, 0, result.length);
        return result;
    }

    private static byte[] vorbisIdHeader(int sampleRate) {
        ByteBuffer buffer = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x01);
        putAscii(buffer, "vorbis");
        buffer.putInt(0);
        buffer.put((byte) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put((byte) 0);
        buffer.put((byte) 1);
        return buffer.array();
    }

    private static byte[] vorbisCommentHeader() {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x03);
        putAscii(buffer, "vorbis");
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put((byte) 1);
        return buffer.array();
    }

    private static byte[] oggPage(int headerType, long granule, int serial, int sequence, byte[] payload) {
        int segments = payload.length == 0 ? 0 : (payload.length + 254) / 255;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "OggS");
        out.write(0);
        out.write(headerType);
        writeLongLittleEndian(out, granule);
        writeIntLittleEndian(out, serial);
        writeIntLittleEndian(out, sequence);
        writeIntLittleEndian(out, 0); // CRC 占位
        out.write(segments);
        int remaining = payload.length;
        for (int i = 0; i < segments; i++) {
            int size = Math.min(remaining, 255);
            out.write(size);
            remaining -= size;
        }
        out.writeBytes(payload);
        byte[] page = out.toByteArray();
        int crc = oggCrc(page);
        page[22] = (byte) (crc & 0xFF);
        page[23] = (byte) ((crc >> 8) & 0xFF);
        page[24] = (byte) ((crc >> 16) & 0xFF);
        page[25] = (byte) ((crc >> 24) & 0xFF);
        return page;
    }

    private static int oggCrc(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (value & 0xFF)) & 0xFF];
        }
        return crc;
    }

    private static final int[] CRC_TABLE = buildCrcTable();

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80000000) != 0 ? (value << 1) ^ 0x04C11DB7 : value << 1;
            }
            table[i] = value;
        }
        return table;
    }

    private static byte[] mvhd(int timescale, int duration) {
        ByteBuffer buffer = ByteBuffer.allocate(104).order(ByteOrder.BIG_ENDIAN);
        putAscii(buffer, "mvhd");
        buffer.put(fullBox(0, 0));
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(timescale);
        buffer.putInt(duration);
        buffer.putInt(0x00010000);
        buffer.putShort((short) 0x0100);
        buffer.put(new byte[10]);
        buffer.put(IDENTITY_MATRIX);
        buffer.put(new byte[24]);
        buffer.putInt(2);
        return withSize(buffer.array());
    }

    private static byte[] tkhd(int duration) {
        ByteBuffer buffer = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN);
        putAscii(buffer, "tkhd");
        buffer.put(fullBox(0, 0x000001));
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putInt(duration);
        buffer.put(new byte[8]);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.put(IDENTITY_MATRIX);
        buffer.putInt(0);
        buffer.putInt(0);
        return withSize(buffer.array());
    }

    private static byte[] mdhd(int timescale, int duration) {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        putAscii(buffer, "mdhd");
        buffer.put(fullBox(0, 0));
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(timescale);
        buffer.putInt(duration);
        buffer.putShort((short) 0x55C4);
        buffer.putShort((short) 0);
        return withSize(buffer.array());
    }

    private static byte[] mp4aEntry() {
        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[6]);
        buffer.putShort((short) 1);
        buffer.put(new byte[8]);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(44_100 << 16);
        // 真实 M4A 的 mp4a 在 28 字节音频描述后内嵌 esds，jaudiotagger 依赖它读取编码信息
        return atom("mp4a", buffer.array(), esds());
    }

    /** 最小可解析 ESDS：Section3（ES 描述）+ Section4（解码配置）+ Section5（音频配置） */
    private static byte[] esds() {
        ByteBuffer descriptors = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        descriptors.put((byte) 0x03).put((byte) 0x03).put(new byte[3]);
        descriptors.put((byte) 0x04).put((byte) 0x0F);
        descriptors.put((byte) 0x40);                       // MPEG-4 Audio
        descriptors.put((byte) 0x05);                       // stream type: audio
        descriptors.put(new byte[]{0x00, 0x00, 0x0F});      // buffer size
        descriptors.putInt(128_000);                        // max bitrate
        descriptors.putInt(128_000);                        // avg bitrate
        descriptors.put((byte) 0x05).put((byte) 0x02);
        descriptors.put((byte) 0x12);                       // profile: AAC-LC
        descriptors.put((byte) 0x10);                       // 2 声道
        return atom("esds", fullBox(0, 0), descriptors.array());
    }

    private static final byte[] IDENTITY_MATRIX = {
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0, 0, 0
    };

    private static byte[] atom(String type, byte[]... children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, type);
        for (byte[] child : children) {
            out.writeBytes(child);
        }
        return withSize(out.toByteArray());
    }

    private static byte[] withSize(byte[] body) {
        byte[] result = new byte[body.length + 4];
        writeIntBigEndian(result, 0, body.length + 4);
        System.arraycopy(body, 0, result, 4, body.length);
        return result;
    }

    private static byte[] fullBox(int version, int flags) {
        return new byte[]{(byte) version, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags};
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeIntBigEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeIntLittleEndian(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeLongLittleEndian(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void putAscii(ByteBuffer buffer, String value) {
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }
}
