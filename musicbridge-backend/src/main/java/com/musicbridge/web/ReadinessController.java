package com.musicbridge.web;

import com.musicbridge.security.AccessForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务就绪探测：检查数据库是否可用，供桌面端在存活检查通过后进一步判断能否处理请求。
 *
 * <p>探测在独立线程中执行并受 {@link #PROBE_TIMEOUT} 硬上限约束，
 * 数据库不可用或超时返回 503；响应只包含状态与时间戳，不暴露连接串、主机名或异常细节。</p>
 */
@RestController
@Tag(name = "健康检查", description = "存活探测与业务就绪探测")
public class ReadinessController {

    /** 就绪探测硬上限：数据库响应超过该时长即视为未就绪 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final DataSource dataSource;
    private final Clock clock;

    public ReadinessController(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @GetMapping("/ready")
    @Operation(summary = "业务就绪检查（数据库可用性），仅允许回环地址访问")
    public ResponseEntity<ReadinessResponse> ready(HttpServletRequest request) {
        if (!LoopbackAccess.isLoopback(request.getRemoteAddr())) {
            throw new AccessForbiddenException("仅允许本机访问");
        }
        boolean ready = databaseReachable();
        HttpStatus status = ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .body(new ReadinessResponse(ready ? "UP" : "DOWN", Instant.now(clock)));
    }

    /**
     * 轻量 Ping：连接后执行 JDBC 连接有效性校验。耗时不可控的连接获取也由线程超时兜底。
     */
    private boolean databaseReachable() {
        AtomicBoolean reachable = new AtomicBoolean(false);
        Thread probe = Thread.ofVirtual().name("musicbridge-ready-probe").start(() -> {
            try (Connection connection = dataSource.getConnection()) {
                reachable.set(connection.isValid((int) PROBE_TIMEOUT.toSeconds()));
            } catch (Exception e) {
                reachable.set(false);
            }
        });
        try {
            probe.join(PROBE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (probe.isAlive()) {
            probe.interrupt();
            return false;
        }
        return reachable.get();
    }

    public record ReadinessResponse(String status, Instant timestamp) {
    }
}
