package com.musicbridge.provider.recognition;

/**
 * 识别输入类型（报告 8.2 双模式）。
 */
public enum RecognitionInputType {
    /** 原始音频片段：必须有 clipId，RAW_AUDIO 上传前必须校验 consentId */
    RAW_AUDIO,
    /** 本地已计算的 Chromaprint 指纹 + 时长 */
    AUDIO_FINGERPRINT
}
