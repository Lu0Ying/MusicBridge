package com.musicbridge.web.clips;

/**
 * 片段生命周期状态（契约草案）。
 *
 * <p>{@code EXPIRED} 仅用于内部与清理流程，客户端读取已过期片段一律返回 404，不会看到该状态。</p>
 */
public enum ClipStatus {

    /** 已落盘且可读取、可用于识曲 */
    STORED,

    /** 已过期：不可读取、不可用于识曲，等待物理清理 */
    EXPIRED
}
