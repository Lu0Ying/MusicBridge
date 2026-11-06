package com.musicbridge.provider.transliteration;

import com.musicbridge.provider.dto.SourceRef;

import java.util.List;

/**
 * 音译结果。
 */
public record TransliterationResult(List<String> lines,
                                    TransliterationScheme scheme,
                                    String providerId,
                                    SourceRef source) {

    public TransliterationResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean hasMatch() {
        return !lines.isEmpty();
    }
}
