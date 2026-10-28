package com.aibi.module.dac;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.rbac.entity.RoleDatasourceAcl;
import com.aibi.module.rbac.entity.RoleFieldAcl;
import com.aibi.module.rbac.entity.RoleTableAcl;
import com.aibi.module.rbac.entity.UserDatasourceAcl;
import com.aibi.module.rbac.entity.UserFieldAcl;
import com.aibi.module.rbac.entity.UserTableAcl;
import com.aibi.module.rbac.mapper.RoleDatasourceAclMapper;
import com.aibi.module.rbac.mapper.RoleFieldAclMapper;
import com.aibi.module.rbac.mapper.RoleTableAclMapper;
import com.aibi.module.rbac.mapper.UserDatasourceAclMapper;
import com.aibi.module.rbac.mapper.UserFieldAclMapper;
import com.aibi.module.rbac.mapper.UserTableAclMapper;
import com.aibi.module.semantic.service.FieldPermissionService;
import com.aibi.common.sql.SqlTableExtractor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据权限 A · 统一脱敏门禁（设计文档《组织治理专项设计》§3.4）。
 *
 * <p>把分散在各出口的字段脱敏收敛为一个门禁，确保<b>成员直授 ∪ 角色继承</b>的数据授权在
 * 问数 / 指标 / 驾驶舱 / 归因 / 分享 全出口表现一致，杜绝「编辑器可见、大屏/分享空白或泄露」的分裂。
 *
 * <p>两类控制：
 * <ol>
 *   <li><b>字段脱敏（结果层）</b>：语义层敏感列默认掩码（fail-closed），成员/角色的 {@code UNMASK} 显式放行（取最宽）。</li>
 *   <li><b>数据源可见性（schema 省略）</b>：{@link #isDatasourceHidden} 合并解析，任一来源 {@code ALLOW} 即可见，
 *       仅当全部来源 {@code HIDDEN} 且无 {@code ALLOW} 才隐藏。</li>
 * </ol>
 *
 * <p><b>fail-closed</b>：字段 ACL 解析异常 → 不放行（保持掩码）；数据源可见性解析异常 → 按最严处理（隐藏）。
 * 任何异常都<b>不阻断主链路</b>（宁可更严，也不因故障放开）。
 */
@Service
public class DataMaskGate {

    private static final Logger log = LoggerFactory.getLogger(DataMaskGate.class);
    private static final String MASK = "***";

    private final FieldPermissionService fieldPermissionService; // @Lazy 注入，见构造器：破 dataMaskGate→fieldPermissionService→semanticService 回环
    private final UserDatasourceAclMapper userDsAclMapper;
    private final UserFieldAclMapper userFieldAclMapper;
    private final UserTableAclMapper userTableAclMapper;
    private final RoleDatasourceAclMapper roleDsAclMapper;
    private final RoleFieldAclMapper roleFieldAclMapper;
    private final RoleTableAclMapper roleTableAclMapper;

    public DataMaskGate(@Lazy FieldPermissionService fieldPermissionService,
                        UserDatasourceAclMapper userDsAclMapper,
                        UserFieldAclMapper userFieldAclMapper,
                        UserTableAclMapper userTableAclMapper,
                        RoleDatasourceAclMapper roleDsAclMapper,
                        RoleFieldAclMapper roleFieldAclMapper,
                        RoleTableAclMapper roleTableAclMapper) {
        this.fieldPermissionService = fieldPermissionService;
        this.userDsAclMapper = userDsAclMapper;
        this.userFieldAclMapper = userFieldAclMapper;
        this.userTableAclMapper = userTableAclMapper;
        this.roleDsAclMapper = roleDsAclMapper;
        this.roleFieldAclMapper = roleFieldAclMapper;
        this.roleTableAclMapper = roleTableAclMapper;
    }

    // ==================== 字段脱敏（结果层） ====================

    /**
     * 对结果按「语义层敏感掩码 + 字段 ACL UNMASK 放行」合并脱敏。
     * 非租户上下文 / 无 datasourceId / 结果为空 → 原样返回。
     */
    public QueryResult mask(QueryResult qr, Long datasourceId) {
        Set<Integer> maskCols = fieldPermissionService.columnsToMask(qr, datasourceId);
        if (maskCols.isEmpty()) {
            return qr;
        }
        // 字段 ACL UNMASK 放行：把应掩码列中、被任一来源显式放行的列移出（取最宽）
        Set<Integer> unmask;
        try {
            unmask = unmaskColumns(datasourceId, qr.columns());
        } catch (RuntimeException e) {
            log.warn("字段 ACL 解析失败，按 fail-closed 不放开脱敏：{}", e.getMessage());
            unmask = Set.of();
        }
        Set<Integer> finalMask = new LinkedHashSet<>(maskCols);
        finalMask.removeAll(unmask);
        if (finalMask.isEmpty()) {
            return qr;
        }
        List<Integer> cols = new ArrayList<>(finalMask);
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
        log.info("数据脱敏：对数据源[{}]脱敏 {} 列（共 {} 列）", datasourceId, cols.size(), qr.columns().size());
        return new QueryResult(qr.sql(), qr.columns(), qr.columnLabels(), newRows,
                qr.rowCount(), qr.executionTimeMs(), qr.enumDicts());
    }

    /** 合并解析需放行的列下标：成员直授 ∪ 角色继承，任一 {@code UNMASK} 即放行。 */
    private Set<Integer> unmaskColumns(Long datasourceId, List<String> columns) {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope() || datasourceId == null) {
            return Set.of();
        }
        Set<String> unmaskCols = new HashSet<>();
        List<UserFieldAcl> userRows = userFieldAclMapper.selectList(new QueryWrapper<UserFieldAcl>()
                .eq("tenant_id", ctx.tenantId()).eq("uid", ctx.uid()).eq("datasource_id", datasourceId));
        for (UserFieldAcl a : userRows) {
            if (a.isUnmask()) {
                unmaskCols.add(a.getColumnName());
            }
        }
        if (!ctx.customRoleIds().isEmpty()) {
            List<RoleFieldAcl> roleRows = roleFieldAclMapper.selectList(new QueryWrapper<RoleFieldAcl>()
                    .eq("tenant_id", ctx.tenantId())
                    .in("custom_role_id", ctx.customRoleIds())
                    .eq("datasource_id", datasourceId));
            for (RoleFieldAcl a : roleRows) {
                if (a.isUnmask()) {
                    unmaskCols.add(a.getColumnName());
                }
            }
        }
        Set<Integer> idx = new LinkedHashSet<>();
        for (int j = 0; j < columns.size(); j++) {
            if (unmaskCols.contains(columns.get(j))) {
                idx.add(j);
            }
        }
        return idx;
    }

    // ==================== 数据源可见性（schema 省略） ====================

    /**
     * 数据源对当前用户是否隐藏（合并解析，取最宽：ALLOW 胜 HIDDEN）。
     * 无上下文 / 非租户域 / 入参缺失 → 返回 false（不拦截，由既有资源权限决定）。
     * <b>fail-closed</b>：解析异常 → 返回 true（隐藏）。
     */
    public boolean isDatasourceHidden(Long datasourceId) {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope() || datasourceId == null) {
            return false;
        }
        try {
            boolean hasAllow = false;
            boolean hasHidden = false;
            List<UserDatasourceAcl> userRows = userDsAclMapper.selectList(new QueryWrapper<UserDatasourceAcl>()
                    .eq("tenant_id", ctx.tenantId()).eq("uid", ctx.uid()).eq("datasource_id", datasourceId));
            for (UserDatasourceAcl a : userRows) {
                if (a.isAllow()) {
                    hasAllow = true;
                } else if (a.isHidden()) {
                    hasHidden = true;
                }
            }
            if (!ctx.customRoleIds().isEmpty()) {
                List<RoleDatasourceAcl> roleRows = roleDsAclMapper.selectList(new QueryWrapper<RoleDatasourceAcl>()
                        .eq("tenant_id", ctx.tenantId())
                        .in("custom_role_id", ctx.customRoleIds())
                        .eq("datasource_id", datasourceId));
                for (RoleDatasourceAcl a : roleRows) {
                    if (a.isAllow()) {
                        hasAllow = true;
                    } else if (a.isHidden()) {
                        hasHidden = true;
                    }
                }
            }
            // 取最宽：任一 ALLOW 即可见；仅当全部 HIDDEN 且无 ALLOW 才隐藏
            return hasHidden && !hasAllow;
        } catch (RuntimeException e) {
            log.warn("数据源可见性解析失败，按 fail-closed 隐藏[{}]：{}", datasourceId, e.getMessage());
            return true;
        }
    }

    /** 批量过滤：从候选数据源 id 列表中剔除对当前用户隐藏者（schema 省略用）。 */
    public List<Long> visibleDatasourceIds(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        List<Long> visible = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
            if (!isDatasourceHidden(id)) {
                visible.add(id);
            }
        }
        return visible;
    }

    // ==================== 表级可见性（v2：schema 省略 + SQL 拦截） ====================

    /**
     * 计算对当前用户隐藏的表<b>裸名</b>集合（成员直授 ∪ 角色继承，取最宽：ALLOW 胜 HIDDEN）。
     * 裸名 = 去反引号/引号/方括号、转小写、取最后一个点之后的段（兼容 {@code 库.表} 前缀）。
     * 无上下文 / 非租户域 / 入参缺失 → 空集合（不隐藏任何表）。
     * <b>fail-open</b>：ACL 解析异常 → 返回空集合（不隐藏），宁可放开也不因故障让全部问数失效
     * （与数据源可见性"异常即隐藏"不同：表维度若异常即隐藏全部表，会让整个数据源的 NL2SQL 无法工作）。
     */
    private Set<String> hiddenTableBareNames(Long datasourceId) {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope() || datasourceId == null) {
            return Set.of();
        }
        try {
            Set<String> hidden = new HashSet<>();
            Set<String> allowed = new HashSet<>();
            for (UserTableAcl a : userTableAclMapper.selectList(new QueryWrapper<UserTableAcl>()
                    .eq("tenant_id", ctx.tenantId()).eq("uid", ctx.uid()).eq("datasource_id", datasourceId))) {
                if (a.isAllow()) {
                    allowed.add(SqlTableExtractor.bareName(a.getTableName()));
                } else if (a.isHidden()) {
                    hidden.add(SqlTableExtractor.bareName(a.getTableName()));
                }
            }
            if (!ctx.customRoleIds().isEmpty()) {
                for (RoleTableAcl a : roleTableAclMapper.selectList(new QueryWrapper<RoleTableAcl>()
                        .eq("tenant_id", ctx.tenantId())
                        .in("custom_role_id", ctx.customRoleIds())
                        .eq("datasource_id", datasourceId))) {
                    if (a.isAllow()) {
                        allowed.add(SqlTableExtractor.bareName(a.getTableName()));
                    } else if (a.isHidden()) {
                        hidden.add(SqlTableExtractor.bareName(a.getTableName()));
                    }
                }
            }
            hidden.removeAll(allowed);
            return hidden;
        } catch (RuntimeException e) {
            log.warn("表级可见性解析失败，按 fail-open 不隐藏[ds={}]：{}", datasourceId, e.getMessage());
            return Set.of();
        }
    }

    /** 某表对当前用户是否隐藏（合并解析，取最宽：ALLOW 胜 HIDDEN）。 */
    public boolean isTableHidden(Long datasourceId, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        return hiddenTableBareNames(datasourceId).contains(SqlTableExtractor.bareName(tableName));
    }

    /** 从全量表中剔除当前用户被隐藏的表（schema 层省略，LLM 看不到 → 不会生成指向隐藏表的 SQL）。 */
    public List<TableMeta> filterTables(Long datasourceId, List<TableMeta> all) {
        if (all == null || all.isEmpty()) {
            return all;
        }
        Set<String> hidden = hiddenTableBareNames(datasourceId);
        if (hidden.isEmpty()) {
            return all;
        }
        List<TableMeta> visible = new ArrayList<>(all.size());
        for (TableMeta t : all) {
            if (!hidden.contains(SqlTableExtractor.bareName(t.name()))) {
                visible.add(t);
            }
        }
        log.info("表级可见性：数据源[{}] 隐藏 {} / 共 {} 张表", datasourceId, all.size() - visible.size(), all.size());
        return visible;
    }

    /**
     * SQL 执行前拦截：若语句涉及当前用户被隐藏的表，抛 {@link ErrorCode#TABLE_FORBIDDEN}。
     * 防"直接手填 SQL"绕过 schema 层省略。无隐藏表 / 无上下文 → 直接放行。
     * <b>fail-open</b>：隐藏表集合解析异常（见 {@link #hiddenTableBareNames}）→ 不拦截，避免阻断正常查询。
     */
    public void assertTablesAllowed(Long datasourceId, String sql) {
        Set<String> hidden = hiddenTableBareNames(datasourceId);
        if (hidden.isEmpty() || sql == null || sql.isBlank()) {
            return;
        }
        for (String referenced : SqlTableExtractor.extractTables(sql)) {
            if (hidden.contains(SqlTableExtractor.bareName(referenced))) {
                throw new BizException(ErrorCode.TABLE_FORBIDDEN, "无权访问数据表：" + referenced);
            }
        }
    }

    // T10-3：表名抽取与裸名归一已原样下沉至公共工具 {@link SqlTableExtractor}（溯源与授权共用同一份抽取逻辑，
    // 避免两套实现漂移），本类改为委派调用；行为与既有拦截逐字节等价。
}
