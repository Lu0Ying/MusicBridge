package com.musicbridge.provider.recognition;

import java.time.Instant;
import java.util.List;

/**
 * 识别结果（报告 8.2）。
 */
public record RecognitionResult(List<RecognitionCandidate> candidates, String providerId, Instant fetchedAt) {

    public RecognitionResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean hasMatch() {
        return !candidates.isEmpty();
    }
}
