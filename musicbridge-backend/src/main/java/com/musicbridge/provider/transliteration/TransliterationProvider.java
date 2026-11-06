package com.musicbridge.provider.transliteration;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.MusicProvider;

/**
 * 音译 Provider 契约：与翻译是两个服务、两个字段，不可互换。
 */
public interface TransliterationProvider extends MusicProvider {

    TransliterationResult transliterate(TransliterationRequest request, RequestContext ctx);
}
