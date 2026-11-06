package com.musicbridge.provider.admin;

import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.observability.ProviderUsageStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Provider 管理 API（UI 归 Phase 5，此处仅暴露最小管理能力）。
 */
@RestController
@RequestMapping("/api/v1/admin/providers")
@Tag(name = "Provider 管理", description = "Provider 状态、主动探测与用量统计")
public class ProviderAdminController {

    private final ProviderAdminService adminService;
    private final ProviderUsageStats usageStats;

    public ProviderAdminController(ProviderAdminService adminService, ProviderUsageStats usageStats) {
        this.adminService = adminService;
        this.usageStats = usageStats;
    }

    @GetMapping
    @Operation(summary = "查询 Provider 状态列表")
    public List<ProviderStatusDTO> list() {
        return adminService.listProviders();
    }

    @PostMapping("/{providerId}/probe")
    @Operation(summary = "主动探测白名单 Provider（默认关闭，结果缓存 10 分钟）")
    public ProviderHealth probe(@PathVariable String providerId) {
        return adminService.probe(providerId);
    }

    @GetMapping("/stats")
    @Operation(summary = "查询 Provider 用量与成本统计")
    public Map<String, ProviderUsageStats.Summary> stats() {
        return usageStats.snapshot();
    }
}
