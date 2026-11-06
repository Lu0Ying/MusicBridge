package com.musicbridge.security;

import java.util.List;

/**
 * 授权撤销时的本地临时片段清理契约。
 *
 * <p>实现方必须按用户与授权隔离，不得触碰其他用户或其他授权的文件；
 * 正在上传中的片段应跳过并留给后续清理。清理失败不得阻断授权撤销。</p>
 */
public interface FragmentCleaner {

    CleanupResult cleanupForConsent(String userId, String consentId);

    record CleanupResult(int deleted, int skippedActive, List<String> failures) {

        public static CleanupResult empty() {
            return new CleanupResult(0, 0, List.of());
        }
    }
}
