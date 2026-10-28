package com.aibi.module.semantic.service;

import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.OrgRole;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.entity.SemanticMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段级（列级）权限：在问数结果返回前，对语义层标记为敏感的列按当前组织角色脱敏。
 *
 * <p>敏感列（{@code semantic_meta.sensitive=1}）仅允许 {@code visible_roles} 中的角色查看；其余角色看到
 * {@code ***}。{@code visible_roles} 为空表示仅组织管理员（OWNER/ADMIN）可见。脱敏在结果投影层完成、
 * 不影响落库快照（历史回放时按当前角色实时再判定），与"业务库只读、其行级权限不归平台管"的设计一致。
 *
 * <p>安全兜底：语义层查询失败 / 非租户上下文 / 入参缺失时<b>原样返回</b>，绝不阻断主链路（宁可少脱敏也不影响可用性）。
 */
@Service
public class FieldPermissionService {

    private static final Logger log = LoggerFactory.getLogger(FieldPermissionService.class);
    private static final String MASK = "***";
    private static final Set<String> ADMIN_ROLES = Set.of(OrgRole.OWNER, OrgRole.ADMIN);
    private static final String TYPE_COLUMN = "COLUMN";

    private final SemanticService semanticService;

    public FieldPermissionService(SemanticService semanticService) {
        this.semanticService = semanticService;
    }

    /**
     * 对结果按字段权限脱敏。非租户上下文 / 无 datasourceId / 结果为空时原样返回。
     */
    /**
     * 计算「按字段权限应脱敏的列下标集合」（不改动入参），供 {@link DataMaskGate} 在脱敏前叠加字段 ACL 放行。
     *
     * <p>语义层敏感列（{@code sensitive=1}）且当前组织角色不在 {@code visible_roles} 内 → 纳入。
     * 非租户上下文 / 无 datasourceId / 结果为空 / 语义层查询失败时返回空集（不脱敏，与 {@link #mask} 兜底一致）。
     */
    public Set<Integer> columnsToMask(QueryResult qr, Long datasourceId) {
        Set<Integer> maskCols = new LinkedHashSet<>();
        if (qr == null || qr.columns() == null || qr.rows() == null) {
            return maskCols;
        }
        var ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope() || datasourceId == null) {
            return maskCols;
        }
        // 列名 → 所需角色（空串=仅管理员）；仅收集本数据源的敏感 COLUMN
        Map<String, String> requiredRoles = new java.util.HashMap<>();
        try {
            for (SemanticMeta m : semanticService.list(datasourceId)) {
                if (!TYPE_COLUMN.equals(m.getObjectType())
                        || m.getSensitive() == null || m.getSensitive() != 1
                        || m.getColumnName() == null || m.getColumnName().isBlank()) {
                    continue;
                }
                requiredRoles.put(m.getColumnName(), m.getVisibleRoles());
            }
        } catch (RuntimeException e) {
            log.warn("字段权限：语义层查询失败，跳过脱敏（不阻断返回）：{}", e.getMessage());
            return maskCols;
        }
        if (requiredRoles.isEmpty()) {
            return maskCols;
        }
        for (int j = 0; j < qr.columns().size(); j++) {
            String roles = requiredRoles.get(qr.columns().get(j));
            if (roles == null) {
                continue;
            }
            Set<String> allowed = roles.isBlank()
                    ? ADMIN_ROLES
                    : new HashSet<>(List.of(roles.split(",")));
            if (!ctx.orgRoles().stream().anyMatch(allowed::contains)) {
                maskCols.add(j);
            }
        }
        return maskCols;
    }

    /**
     * 对结果按字段权限脱敏。非租户上下文 / 无 datasourceId / 结果为空时原样返回。
     */
    public QueryResult mask(QueryResult qr, Long datasourceId) {
        Set<Integer> maskCols = columnsToMask(qr, datasourceId);
        if (maskCols.isEmpty()) {
            return qr;
        }
        List<Integer> cols = new ArrayList<>(maskCols);
        List<List<Object>> newRows = new ArrayList<>(qr.rows().size());
        for (List<Object> row : qr.rows()) {
            List<Object> nr = new ArrayList<>(row);
            for (int j : cols) {
                if (j < nr.size()) {
                    nr.set(j, MASK);
                }
            }
            newRows.add(nr);
        }
        log.info("字段权限：对数据源[{}]脱敏 {} 列（共 {} 列）", datasourceId, cols.size(), qr.columns().size());
        return new QueryResult(qr.sql(), qr.columns(), qr.columnLabels(), newRows,
                qr.rowCount(), qr.executionTimeMs(), qr.enumDicts());
    }
}
