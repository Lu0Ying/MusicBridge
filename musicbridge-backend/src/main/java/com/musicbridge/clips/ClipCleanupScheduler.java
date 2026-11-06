package com.musicbridge.clips;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期片段清理调度：每日定时执行一次，后端启动后补扫一次。
 *
 * <p>清理本身幂等、可重试；失败只记录日志，不影响启动与业务请求。</p>
 */
@Component
public class ClipCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClipCleanupScheduler.class);

    private final ClipService clipService;

    public ClipCleanupScheduler(ClipService clipService) {
        this.clipService = clipService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        // 启动补扫放在虚拟线程，避免阻塞就绪流程
        Thread.ofVirtual().name("clip-cleanup-startup").start(this::runSafely);
    }

    @Scheduled(cron = "${musicbridge.clips.cleanup-cron:0 0 3 * * *}")
    public void sweepDaily() {
        runSafely();
    }

    void runSafely() {
        try {
            int processed = clipService.cleanupExpired();
            if (processed > 0) {
                log.info("过期片段清理完成，本次处理 {} 条", processed);
            }
        } catch (RuntimeException e) {
            log.warn("过期片段清理失败，将在下次调度重试: {}", e.getMessage());
        }
    }
}
