package com.musicbridge.provider.dto;

import java.time.LocalDate;

/**
 * 发行载体 DTO。
 */
public record ReleaseDTO(String mbid,
                         String title,
                         LocalDate releaseDate,
                         String country,
                         String status,
                         String mediumFormat) {
}
