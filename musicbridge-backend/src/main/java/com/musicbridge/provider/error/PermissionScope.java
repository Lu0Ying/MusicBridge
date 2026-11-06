package com.musicbridge.provider.error;

/**
 * 403 的权限范围。无法判断时按账号级保守处理（报告 8.5）。
 */
public enum PermissionScope {
    /** 仅当前能力受限：允许切换，并冷却该能力 */
    CAPABILITY,
    /** 账号封禁/停用/欠费：整 Provider 停用，禁止切换 */
    ACCOUNT,
    /** 无法判断：按账号级处理 */
    UNKNOWN
}
