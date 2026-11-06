package com.musicbridge.provider.recognition;

import java.util.Set;

/**
 * 识别能力声明（报告 8.2）。
 */
public interface RecognitionCapabilities {

    /** 支持的输入类型；不支持的类型不调用该 Provider */
    Set<RecognitionInputType> inputTypes();

    boolean returnsIsrc();

    boolean supportsLanguageHint();

    /** 仅表示支持“可访问音频流 URL”，不代表本机声卡采集 */
    boolean supportsStreamUrl();
}
