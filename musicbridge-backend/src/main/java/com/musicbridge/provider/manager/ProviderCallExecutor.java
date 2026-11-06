package com.musicbridge.provider.manager;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带超时的 Provider 调用执行器。
 * <p>超时结果不确定时不再重复发起计费调用，由调用方决策。</p>
 */
@Component
public class ProviderCallExecutor {

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "provider-call-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicInteger COUNTER = new AtomicInteger();

    public <T> T call(Duration timeout, Callable<T> callable) throws TimeoutException, ExecutionException {
        Future<T> future = executor.submit(callable);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider 调用被中断", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
