package com.musicbridge.provider.recognition;

import java.util.Set;

/**
 * {@link RecognitionCapabilities} 的通用实现。
 */
public record DefaultRecognitionCapabilities(Set<RecognitionInputType> inputTypes,
                                             boolean returnsIsrc,
                                             boolean supportsLanguageHint,
                                             boolean supportsStreamUrl)
        implements RecognitionCapabilities {

    public DefaultRecognitionCapabilities {
        inputTypes = inputTypes == null ? Set.of() : Set.copyOf(inputTypes);
    }
}
