package com.musicbridge.provider.observability;

import com.musicbridge.config.ProviderProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 默认成本估算：单价 × 调用单位，单价来自配置，未配置即为 0。
 * <p>Phase 0 仅做线性估算，不做套餐阶梯、汇率与重试放大。</p>
 */
@Component
public class DefaultCostEstimator implements CostEstimator {

    private final ProviderProperties properties;

    public DefaultCostEstimator(ProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal estimate(String providerId, int requestUnits) {
        BigDecimal unitCost = properties.settingFor(providerId).getCostPerUnit();
        if (unitCost == null) {
            return BigDecimal.ZERO;
        }
        return unitCost.multiply(BigDecimal.valueOf(Math.max(requestUnits, 1)));
    }
}
