package com.musicbridge.provider.recognition;

import java.time.Duration;

/**
 * 识别请求（报告 8.2）。
 * <p>RAW_AUDIO 必填 clipId + consentId；AUDIO_FINGERPRINT 必填 fingerprint + audioDuration。
 * 输入校验由应用层按 inputType 执行，不匹配返回 400 INVALID_INPUT，不进入 Provider。</p>
 */
public record RecognitionRequest(RecognitionInputType inputType,
                                 String clipId,
                                 String fingerprint,
                                 Duration audioDuration,
                                 String consentId,
                                 String languageHint) {
}
