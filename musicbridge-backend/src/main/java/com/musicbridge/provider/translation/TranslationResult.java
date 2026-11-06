package com.musicbridge.provider.translation;

import com.musicbridge.provider.dto.SourceRef;

import java.util.List;

/**
 * 翻译结果：翻译 ≠ 音译，字段独立存储。
 */
public record TranslationResult(List<String> lines,
                                String sourceLanguage,
                                String targetLanguage,
                                String providerId,
                                SourceRef source) {

    public TranslationResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean hasMatch() {
        return !lines.isEmpty();
    }
}
