package com.musicbridge.provider.manager;

/**
 * 单次任务外部调用预算耗尽。属于编排层异常，不属于 Provider 错误码。
 */
public class ProviderBudgetExhaustedException extends RuntimeException {

    public ProviderBudgetExhaustedException(String message) {
        super(message);
    }
}
