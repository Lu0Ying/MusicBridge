package com.musicbridge.provider.recognition;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 识别候选：只保留规范化字段，不含原始 Provider 响应。
 * <p>candidateReleaseHint 只是候选给出的发行日期提示，不是权威发行日期；
 * debugReference 仅指向受控调试存储，不进业务 DTO、数据库与普通日志。</p>
 */
public record RecognitionCandidate(String title,
                                   List<String> artists,
                                   String album,
                                   Duration duration,
                                   LocalDate candidateReleaseHint,
                                   Double confidence,
                                   Map<String, String> externalIds,
                                   String debugReference) {

    public RecognitionCandidate {
        artists = artists == null ? List.of() : List.copyOf(artists);
        externalIds = externalIds == null ? Map.of() : Map.copyOf(externalIds);
    }
}
