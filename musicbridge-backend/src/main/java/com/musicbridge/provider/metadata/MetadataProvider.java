package com.musicbridge.provider.metadata;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.MusicProvider;
import com.musicbridge.provider.dto.MetadataRecord;

/**
 * 元数据 Provider 契约：返回 Work / Recording / Release / ReleaseTrack 四层数据。
 */
public interface MetadataProvider extends MusicProvider {

    MetadataRecord lookup(MetadataQuery query, RequestContext ctx);
}
