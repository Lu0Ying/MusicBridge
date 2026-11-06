package com.musicbridge.provider.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 缓存 single-flight：同一缓存键的并发请求只允许一个真正执行，其余等待并复用结果。
 * <p>避免缓存击穿时对第三方 Provider 发起重复计费调用；执行完成后立即移除在途标记，
 * 后续请求直接命中缓存。失败会原样传播给所有等待者，不产生“半成功”结果。</p>
 */
@Component
public class SingleFlight {

    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T run(String key, Supplier<T> action) {
        if (key == null) {
            return action.get();
        }
        CompletableFuture<Object> leader = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, leader);
        if (running != null) {
            return (T) await(running);
        }
        try {
            T value = action.get();
            leader.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            leader.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, leader);
        }
    }

    /** 当前正在执行中的键数量，供测试与观测 */
    public int inFlightCount() {
        return inFlight.size();
    }

    private Object await(CompletableFuture<Object> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一缓存键的在途请求被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("在途请求执行失败", cause);
        }
    }
}
