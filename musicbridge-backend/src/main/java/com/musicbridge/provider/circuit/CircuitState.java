package com.musicbridge.provider.circuit;

/**
 * 熔断器状态。
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
