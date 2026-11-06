package com.musicbridge.common;

import java.time.Instant;
import java.util.Objects;

/**
 * 单次业务请求的调用上下文。
 * <p>traceId 用于串联调用日志；budget 用于限制单次任务的全局外部调用与切换次数。</p>
 */
public final class RequestContext {

    /** 报告 8.5：单次识别任务外部调用上限 5 次、切换上限 2 次 */
    public static final int DEFAULT_MAX_EXTERNAL_CALLS = 5;
    public static final int DEFAULT_MAX_SWITCHES = 2;

    private final String traceId;
    private final String userId;
    private final CallBudget budget;
    private final Instant createdAt;

    private RequestContext(String traceId, String userId, CallBudget budget, Instant createdAt) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.userId = userId;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static RequestContext create(String userId) {
        return new RequestContext(TraceContext.currentOrNew(), userId,
                new CallBudget(DEFAULT_MAX_EXTERNAL_CALLS, DEFAULT_MAX_SWITCHES), Instant.now());
    }

    public static RequestContext withBudget(String userId, int maxCalls, int maxSwitches) {
        return new RequestContext(TraceContext.currentOrNew(), userId,
                new CallBudget(maxCalls, maxSwitches), Instant.now());
    }

    public static RequestContext of(String traceId, String userId, CallBudget budget) {
        return new RequestContext(traceId, userId, budget, Instant.now());
    }

    public String traceId() {
        return traceId;
    }

    public String userId() {
        return userId;
    }

    public CallBudget budget() {
        return budget;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
