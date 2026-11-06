package com.musicbridge.provider.metadata;

import java.util.List;

/**
 * 元数据查询条件。适配器按可用字段构造查询，不要求所有字段齐全。
 */
public record MetadataQuery(String recordingMbid,
                            String workMbid,
                            String releaseMbid,
                            String title,
                            List<String> artists,
                            String country) {

    public MetadataQuery {
        artists = artists == null ? List.of() : List.copyOf(artists);
    }

    public static MetadataQuery byRecordingMbid(String recordingMbid) {
        return new MetadataQuery(recordingMbid, null, null, null, List.of(), null);
    }

    public static MetadataQuery byTitle(String title, List<String> artists, String country) {
        return new MetadataQuery(null, null, null, title, artists, country);
    }
}
