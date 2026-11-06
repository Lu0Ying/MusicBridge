package com.musicbridge.clips;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 片段元数据仓储。
 */
public interface ClipRepository {

    void insert(ClipRecord clip);

    Optional<ClipRecord> findByIdAndUserId(String id, String userId);

    /**
     * 需要清理的片段：已到期但仍为 STORED，或已经标记 EXPIRED 等待物理删除（失败重试）。
     */
    List<ClipRecord> findNeedingCleanup(Instant now, int limit);

    /** 条件更新 STORED → EXPIRED，返回是否本次完成迁移（幂等/并发安全） */
    boolean markExpired(String id, Instant now);

    /** 按归属删除，返回是否删除成功 */
    boolean deleteByIdAndUserId(String id, String userId);
}
