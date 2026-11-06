package com.musicbridge.provider.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 录音版本 DTO。
 * <p>firstReleaseDate 由 Release 聚合派生，仅供展示；外部标识统一放 externalIds。</p>
 */
public record RecordingDTO(String mbid,
                           String title,
                           List<String> artists,
                           Map<String, String> externalIds,
                           Duration duration,
                           LocalDate firstReleaseDate,
                           VersionType versionType,
                           String workMbid) {
}
