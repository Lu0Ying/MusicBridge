package com.musicbridge.provider.lyrics;

/**
 * 歌词类别：原文 / 翻译 / 音译三类分存，不合并为一个 Provider 结果。
 */
public enum LyricsKind {
    ORIGINAL,
    TRANSLATION,
    TRANSLITERATION
}
