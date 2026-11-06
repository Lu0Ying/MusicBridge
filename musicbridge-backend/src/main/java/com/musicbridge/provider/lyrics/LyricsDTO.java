package com.musicbridge.provider.lyrics;

import com.musicbridge.provider.dto.SourceRef;

import java.util.List;

/**
 * 歌词 DTO（报告 8.4）：普通与同步共用该结构，不设 format 字段。
 * <p>language 必填；plainText 或 lines 至少一项非空。</p>
 */
public record LyricsDTO(String lyricsId,
                        String recordingId,
                        LyricsKind kind,
                        String language,
                        String plainText,
                        List<LyricLineDTO> lines,
                        SourceRef source) {

    public LyricsDTO {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean hasMatch() {
        return (plainText != null && !plainText.isBlank()) || !lines.isEmpty();
    }
}
