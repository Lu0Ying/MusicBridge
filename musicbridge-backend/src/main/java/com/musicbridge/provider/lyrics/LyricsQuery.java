package com.musicbridge.provider.lyrics;

import java.time.Duration;

/**
 * 歌词查询条件。语言必填；preferSynced 决定是否优先同步歌词。
 */
public record LyricsQuery(String recordingMbid,
                          String title,
                          String artist,
                          String album,
                          Duration duration,
                          String language,
                          boolean preferSynced) {
}
