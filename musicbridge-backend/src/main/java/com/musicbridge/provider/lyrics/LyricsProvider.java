package com.musicbridge.provider.lyrics;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.MusicProvider;

/**
 * 歌词 Provider 契约：普通歌词与同步歌词共用同一 DTO。
 */
public interface LyricsProvider extends MusicProvider {

    LyricsDTO fetch(LyricsQuery query, RequestContext ctx);
}
