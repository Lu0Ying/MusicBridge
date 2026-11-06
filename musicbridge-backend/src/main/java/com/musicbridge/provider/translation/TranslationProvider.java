package com.musicbridge.provider.translation;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.MusicProvider;

/**
 * 翻译 Provider 契约。
 */
public interface TranslationProvider extends MusicProvider {

    TranslationResult translate(TranslationRequest request, RequestContext ctx);
}
