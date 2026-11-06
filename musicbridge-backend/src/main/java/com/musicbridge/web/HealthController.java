package com.musicbridge.web;

import com.musicbridge.security.AccessForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * 本机就绪探测：仅回环来源可访问，返回最小状态，供桌面端判断后端是否可用。
 *
 * <p>响应不包含版本、依赖或数据源等内部信息。
 */
@RestController
@Tag(name = "健康检查", description = "本机就绪探测")
public class HealthController {

    private final Clock clock;

    public HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    @Operation(summary = "本机健康检查，仅允许回环地址访问")
    public HealthResponse health(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new AccessForbiddenException("仅允许本机访问");
        }
        return new HealthResponse("UP", Instant.now(clock));
    }

    private boolean isLoopback(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }

    public record HealthResponse(String status, Instant timestamp) {
    }
}
