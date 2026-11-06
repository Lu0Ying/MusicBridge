package com.musicbridge.provider.lyrics;

/**
 * 逐行歌词。普通歌词 lines 为空，同步歌词携带时间轴。
 */
public record LyricLineDTO(int lineNo, Integer startMs, Integer endMs, String text) {
}
