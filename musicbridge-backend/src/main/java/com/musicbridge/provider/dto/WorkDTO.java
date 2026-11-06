package com.musicbridge.provider.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 作品 DTO。
 */
public record WorkDTO(String mbid, String title, List<String> composers, List<String> lyricists) {
}

