package com.musicbridge.provider.manager;

import java.time.Duration;

/**
 * 可替换的等待实现：生产直接 sleep，测试注入不等待实现。
 */
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper threadSleeper() {
        return duration -> {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider 退避等待被中断", e);
            }
        };
    }

    static Sleeper noop() {
        return duration -> {
        };
    }
}
