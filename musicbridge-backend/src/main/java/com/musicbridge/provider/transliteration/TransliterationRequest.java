package com.musicbridge.provider.transliteration;

import java.util.List;

/**
 * 音译请求：只转写读音，不改变语义。
 */
public record TransliterationRequest(List<String> lines, String sourceLanguage, TransliterationScheme scheme) {

    public TransliterationRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
