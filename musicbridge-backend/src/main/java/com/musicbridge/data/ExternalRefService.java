package com.musicbridge.data;

import com.musicbridge.security.ResourceNotFoundException;
import com.musicbridge.web.InvalidRequestException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D-10：external_ref 的实体存在性校验与孤儿清理。
 *
 * <p>写入前确认 entity_type/entity_id 指向真实实体；清理只删除其实体已不存在的引用行，
 * 全部类型的清理在单个事务内完成，避免清掉一半。重复执行幂等。</p>
 */
@Service
public class ExternalRefService {

    private static final Map<String, String> ENTITY_TABLES;
    private static final Set<String> ID_TYPES =
            Set.of("ISRC", "MBID", "SPOTIFY_ID", "ITUNES_ID", "ACOUSTID");

    static {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("RECORDING", "recording");
        tables.put("RELEASE", "`release`");
        tables.put("WORK", "work");
        tables.put("LYRICS", "lyrics");
        ENTITY_TABLES = Map.copyOf(tables);
    }

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ExternalRefService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 注册一条外部引用；实体或标识类型非法/不存在时不落库。
     *
     * @return 新引用 id
     */
    public String register(String entityType, String entityId, String idType, String idValue,
                           String sourceProvider, boolean primary) {
        String table = entityTable(entityType);
        if (!ID_TYPES.contains(idType)) {
            throw new InvalidRequestException("idType 不支持: " + idType);
        }
        if (!entityExists(table, entityId)) {
            throw new ResourceNotFoundException("引用实体不存在: " + entityType + "/" + entityId);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update(
                    "INSERT INTO external_ref (id, entity_type, entity_id, id_type, id_value, "
                            + "source_provider, is_primary) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, entityType, entityId, idType, idValue, sourceProvider, primary ? 1 : 0);
        } catch (DuplicateKeyException e) {
            throw new InvalidRequestException("外部标识已存在: " + idType + "=" + idValue);
        }
        return id;
    }

    /**
     * 清理孤儿引用：实体不存在的行。单事务执行，返回删除行数；重复调用返回 0。
     */
    public int cleanupOrphans() {
        Integer deleted = transactionTemplate.execute(status -> {
            int total = 0;
            for (Map.Entry<String, String> entry : ENTITY_TABLES.entrySet()) {
                total += jdbcTemplate.update(
                        "DELETE FROM external_ref WHERE entity_type = ? "
                                + "AND entity_id NOT IN (SELECT id FROM " + entry.getValue() + ")",
                        entry.getKey());
            }
            return total;
        });
        return deleted == null ? 0 : deleted;
    }

    /** 只读扫描，返回孤儿引用 id，供清理前核对 */
    public List<String> findOrphanIds() {
        StringBuilder sql = new StringBuilder(
                "SELECT er.id FROM external_ref er WHERE ");
        boolean first = true;
        for (Map.Entry<String, String> entry : ENTITY_TABLES.entrySet()) {
            if (!first) {
                sql.append(" OR ");
            }
            sql.append("(er.entity_type = '").append(entry.getKey())
                    .append("' AND NOT EXISTS (SELECT 1 FROM ").append(entry.getValue())
                    .append(" e WHERE e.id = er.entity_id))");
            first = false;
        }
        return jdbcTemplate.queryForList(sql.toString(), String.class);
    }

    public long countEntityRefs(String entityType, String entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_ref WHERE entity_type = ? AND entity_id = ?",
                Long.class, entityType, entityId);
        return count == null ? 0 : count;
    }

    private boolean entityExists(String table, String entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, entityId);
        return count != null && count > 0;
    }

    private String entityTable(String entityType) {
        String table = ENTITY_TABLES.get(entityType);
        if (table == null) {
            throw new InvalidRequestException("entityType 不支持: " + entityType);
        }
        return table;
    }
}
