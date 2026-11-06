package com.musicbridge.web;

import com.musicbridge.provider.observability.ProviderCallLog;
import com.musicbridge.provider.observability.ProviderCallLogRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路查询：按 traceId 返回本次请求的全部 Provider 调用日志。
 */
@RestController
@RequestMapping("/api/v1/admin/traces")
@Tag(name = "链路追踪", description = "按 traceId 查询 Provider 调用日志")
public class TraceController {

    private final ProviderCallLogRecorder recorder;

    public TraceController(ProviderCallLogRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/{traceId}")
    @Operation(summary = "按 traceId 查询调用日志（最新的在前）")
    public List<ProviderCallLog> byTraceId(@PathVariable String traceId,
                                           @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return recorder.byTraceId(traceId, safeLimit);
    }
}
