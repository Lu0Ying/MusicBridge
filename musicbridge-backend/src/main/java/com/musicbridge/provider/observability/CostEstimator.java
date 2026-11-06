package com.musicbridge.provider.observability;

import java.math.BigDecimal;

/**
 * 成本估算接口：不同 Provider 的计费口径不同，由实现按调用单位换算。
 */
public interface CostEstimator {

    BigDecimal estimate(String providerId, int requestUnits);
}
