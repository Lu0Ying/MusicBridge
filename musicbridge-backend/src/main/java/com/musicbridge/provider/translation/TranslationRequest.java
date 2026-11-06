package com.musicbridge.provider.translation;

import java.util.List;

/**
 * 翻译请求：按行输入、按行输出，保持行号。
 * <p>modelVersion 参与缓存键，模型升级即失效。</p>
 */
public record TranslationRequest(List<String> lines,
                                 String sourceLanguage,
                                 String targetLanguage,
                                 String modelVersion) {

    public TranslationRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
