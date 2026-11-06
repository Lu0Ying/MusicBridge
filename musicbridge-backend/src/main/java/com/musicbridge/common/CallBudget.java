package com.musicbridge.common;

/**
 * 单次任务的全局外部调用预算计数器。
 * <p>报告 8.5：重试 + 切换 + 429 合计不超过若干次外部调用与若干次切换。</p>
 */
public final class CallBudget {

    private final int maxCalls;
    private final int maxSwitches;
    private int calls;
    private int switches;

    public CallBudget(int maxCalls, int maxSwitches) {
        if (maxCalls <= 0 || maxSwitches < 0) {
            throw new IllegalArgumentException("调用预算参数非法");
        }
        this.maxCalls = maxCalls;
        this.maxSwitches = maxSwitches;
    }

    /** 尝试占用一次外部调用额度 */
    public synchronized boolean tryConsumeCall() {
        if (calls >= maxCalls) {
            return false;
        }
        calls++;
        return true;
    }

    /** 尝试占用一次跨 Provider 切换额度 */
    public synchronized boolean tryConsumeSwitch() {
        if (switches >= maxSwitches) {
            return false;
        }
        switches++;
        return true;
    }

    public synchronized int callsUsed() {
        return calls;
    }

    public synchronized int switchesUsed() {
        return switches;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int maxSwitches() {
        return maxSwitches;
    }
}
