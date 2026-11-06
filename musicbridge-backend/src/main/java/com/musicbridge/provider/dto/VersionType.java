package com.musicbridge.provider.dto;

/**
 * 录音版本类型：翻唱 / Live / Remix 等在 Recording 层区分。
 */
public enum VersionType {
    ORIGINAL,
    COVER,
    LIVE,
    REMIX,
    INSTRUMENTAL
}
