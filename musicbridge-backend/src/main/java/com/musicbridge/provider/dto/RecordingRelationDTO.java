package com.musicbridge.provider.dto;

/**
 * 录音关系 DTO：仅表达“同作品不同录音”。
 * <p>同录音多发行通过 {@link ReleaseTrackDTO} 列表查询，不建关系 DTO。</p>
 */
public record RecordingRelationDTO(String sourceRecordingId,
                                   String targetRecordingId,
                                   RecordingRelationScope relationScope,
                                   RecordingRelationSubtype relationSubtype,
                                   Double confidence,
                                   boolean confirmedByUser) {
}
