package com.musicbridge.clips;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 片段上传与清理配置，默认值与 /clips 冻结契约一致。
 */
@ConfigurationProperties(prefix = "musicbridge.clips")
public class ClipProperties {

    /** 单文件最大字节数：25 MB，按文件实际字节数 */
    private long maxSizeBytes = 26_214_400L;

    /** 实测时长下限（毫秒） */
    private long minDurationMs = 500L;

    /** 实测时长上限（毫秒）：5 分钟 */
    private long maxDurationMs = 300_000L;

    /** 客户端时长提示与实测值的最大允许偏差（毫秒） */
    private long durationToleranceMs = 2_000L;

    /** 每日清理任务 cron（默认每天 03:00） */
    private String cleanupCron = "0 0 3 * * *";

    /** 单次清理最多处理的片段数 */
    private int cleanupBatchSize = 200;

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public long getMinDurationMs() {
        return minDurationMs;
    }

    public void setMinDurationMs(long minDurationMs) {
        this.minDurationMs = minDurationMs;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    public long getDurationToleranceMs() {
        return durationToleranceMs;
    }

    public void setDurationToleranceMs(long durationToleranceMs) {
        this.durationToleranceMs = durationToleranceMs;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
