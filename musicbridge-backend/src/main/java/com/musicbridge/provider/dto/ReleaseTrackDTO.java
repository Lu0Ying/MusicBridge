package com.musicbridge.provider.dto;

import java.time.Duration;

/**
 * 录音在某次发行中的位置 DTO。
 */
public record ReleaseTrackDTO(String releaseMbid,
                              String recordingMbid,
                              int discNumber,
                              int trackNumber,
                              int position,
                              Duration duration) {
}
