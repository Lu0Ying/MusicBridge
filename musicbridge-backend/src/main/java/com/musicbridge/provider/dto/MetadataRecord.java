package com.musicbridge.provider.dto;

import java.util.List;

/**
 * 四层元数据聚合结果。
 */
public record MetadataRecord(WorkDTO work,
                             RecordingDTO recording,
                             List<ReleaseDTO> releases,
                             List<ReleaseTrackDTO> releaseTracks,
                             List<String> coverUrls,
                             Double confidence,
                             SourceRef source) {

    public MetadataRecord {
        releases = releases == null ? List.of() : List.copyOf(releases);
        releaseTracks = releaseTracks == null ? List.of() : List.copyOf(releaseTracks);
        coverUrls = coverUrls == null ? List.of() : List.copyOf(coverUrls);
    }

    /** 是否包含可用的元数据（用于 NO_MATCH 判定） */
    public boolean hasMatch() {
        return (recording != null && recording.title() != null && !recording.title().isBlank())
                || (work != null && work.title() != null && !work.title().isBlank());
    }
}
