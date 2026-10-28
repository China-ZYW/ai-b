package com.aibi.module.dac;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.dac.dto.DataAclClearForm;
import com.aibi.module.dac.dto.DataAclForm;
import com.aibi.module.dac.dto.DataAclItemVO;
import com.aibi.module.rbac.entity.CustomRole;
import com.aibi.module.rbac.entity.RoleDatasourceAcl;
import com.aibi.module.rbac.entity.RoleFieldAcl;
import com.aibi.module.rbac.entity.RoleTableAcl;
import com.aibi.module.rbac.entity.UserDatasourceAcl;
import com.aibi.module.rbac.entity.UserFieldAcl;
import com.aibi.module.rbac.entity.UserTableAcl;
import com.aibi.module.rbac.mapper.CustomRoleMapper;
import com.aibi.module.rbac.mapper.RoleDatasourceAclMapper;
import com.aibi.module.rbac.mapper.RoleFieldAclMapper;
import com.aibi.module.rbac.mapper.RoleTableAclMapper;
import com.aibi.module.rbac.mapper.UserDatasourceAclMapper;
import com.aibi.module.rbac.mapper.UserFieldAclMapper;
import com.aibi.module.rbac.mapper.UserTableAclMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据权限 A · 管理服务（设计文档《组织治理专项设计》§3）。
 *
 * <p>维护「成员直授」与「角色继承」两类、三种授权维度：
 * <ul>
 *   <li><b>数据源可见性（DATASOURCE）</b>：整库 HIDDEN/ALLOW（黑名单：无行=可见）；</li>
 *   <li><b>字段脱敏（FIELD）</b>：敏感列 MASK/UNMASK（白名单：无行=按语义层默认掩码）；</li>
 *   <li><b>表级可见性（TABLE，v2）</b>：单表 HIDDEN/ALLOW（黑名单：无行=可见），schema 层省略 + SQL 执行前拦截。</li>
 * </ul>
 * 隔离由租户拦截器保证（六张表均带 {@code tenant_id} 且未列入 IGNORE 名单）。字段脱敏（FIELD）额外带
 * {@code table_name} 维度（NULL=作用于该数据源所有同名列；非 NULL=特定表.列），故字段写读按
 * {@code tenant_id + uid/custom_role_id + datasource_id + table_name} 定位；数据源/表级可见性按前三者即可。
 *
 * <p>解析与施加（合并取最宽 + fail-closed）在 {@link DataMaskGate}；本服务只负责增删查与审计。
 */
@Service
public class DataAclService {

    private static final Logger log = LoggerFactory.getLogger(DataAclService.class);

    /** 授权主体：成员直授。 */
    public static final String TARGET_USER = "USER";
    /** 授权主体：角色继承。 */
    public static final String TARGET_ROLE = "ROLE";

    /** 维度：数据源可见性。 */
    public static final String KIND_DATASOURCE = "DATASOURCE";
    /** 维度：字段脱敏。 */
    public static final String KIND_FIELD = "FIELD";
    /** 维度：表级可见性（v2）。 */
    public static final String KIND_TABLE = "TABLE";

    private final UserDatasourceAclMapper userDsAclMapper;
    private final UserFieldAclMapper userFieldAclMapper;
    private final UserTableAclMapper userTableAclMapper;
    private final RoleDatasourceAclMapper roleDsAclMapper;
    private final RoleFieldAclMapper roleFieldAclMapper;
    private final RoleTableAclMapper roleTableAclMapper;
    private final CustomRoleMapper customRoleMapper;
    private final AuditService auditService;

    public DataAclService(UserDatasourceAclMapper userDsAclMapper,
                          UserFieldAclMapper userFieldAclMapper,
                          UserTableAclMapper userTableAclMapper,
                          RoleDatasourceAclMapper roleDsAclMapper,
                          RoleFieldAclMapper roleFieldAclMapper,
                          RoleTableAclMapper roleTableAclMapper,
                          CustomRoleMapper customRoleMapper,
                          AuditService auditService) {
        this.userDsAclMapper = userDsAclMapper;
        this.userFieldAclMapper = userFieldAclMapper;
        this.userTableAclMapper = userTableAclMapper;
        this.roleDsAclMapper = roleDsAclMapper;
        this.roleFieldAclMapper = roleFieldAclMapper;
        this.roleTableAclMapper = roleTableAclMapper;
        this.customRoleMapper = customRoleMapper;
        this.auditService = auditService;
    }

    // ==================== 写：设置 / 清除 ====================

    /** 设置一条数据授权（整体替换：删旧插新）。维度由 {@code tableName}/{@code columnName} 决定。 */
    public void setAcl(DataAclForm form) {
        AccessContext ctx = requireCtx();
        Long tenantId = ctx.tenantId();
        if (form.targetId() == null || form.datasourceId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetId 与 datasourceId 必填");
        }
        String kind = resolveKind(form);
        validate(form.targetType(), form.effect(), kind);
        if (TARGET_USER.equals(form.targetType())) {
            switch (kind) {
                case KIND_FIELD -> upsertUserField(tenantId, form.targetId(), form.datasourceId(), form.tableName(), form.columnName(), form.effect(), ctx.uid());
                case KIND_TABLE -> upsertUserTable(tenantId, form.targetId(), form.datasourceId(), form.tableName(), form.effect(), ctx.uid());
                default -> upsertUserDs(tenantId, form.targetId(), form.datasourceId(), form.effect(), ctx.uid());
            }
        } else {
            requireOwnedRole(tenantId, form.targetId());
            switch (kind) {
                case KIND_FIELD -> upsertRoleField(tenantId, form.targetId(), form.datasourceId(), form.tableName(), form.columnName(), form.effect());
                case KIND_TABLE -> upsertRoleTable(tenantId, form.targetId(), form.datasourceId(), form.tableName(), form.effect());
                default -> upsertRoleDs(tenantId, form.targetId(), form.datasourceId(), form.effect());
            }
        }
        Map<String, Object> detail = detail(form.targetType(), form.targetId(), form.datasourceId(),
                form.columnName(), form.tableName(), kind);
        detail.put("effect", form.effect());
        auditService.record(AuditAction.DATA_ACL_SET, AuditAction.RESULT_OK, tenantId,
                TARGET_USER.equals(form.targetType()) ? form.targetId() : null,
                "DATA_ACL", String.valueOf(form.datasourceId()), detail);
    }

    /** 清除一条数据授权（恢复默认：可见 / 按语义层默认掩码）。维度由 {@code tableName}/{@code columnName} 决定。 */
    public void clearAcl(DataAclClearForm form) {
        AccessContext ctx = requireCtx();
        Long tenantId = ctx.tenantId();
        if (form.targetId() == null || form.datasourceId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetId 与 datasourceId 必填");
        }
        if (!TARGET_USER.equals(form.targetType()) && !TARGET_ROLE.equals(form.targetType())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetType 仅支持 USER / ROLE");
        }
        String kind = resolveKind(form);
        if (TARGET_USER.equals(form.targetType())) {
            switch (kind) {
                case KIND_FIELD -> {
                    var q = new QueryWrapper<UserFieldAcl>().eq("tenant_id", tenantId)
                            .eq("uid", form.targetId()).eq("datasource_id", form.datasourceId())
                            .eq("column_name", form.columnName());
                    if (form.tableName() == null) q.isNull("table_name"); else q.eq("table_name", form.tableName());
                    userFieldAclMapper.delete(q);
                }
                case KIND_TABLE -> userTableAclMapper.delete(new QueryWrapper<UserTableAcl>().eq("tenant_id", tenantId)
                        .eq("uid", form.targetId()).eq("datasource_id", form.datasourceId())
                        .eq("table_name", form.tableName()));
                default -> userDsAclMapper.delete(new QueryWrapper<UserDatasourceAcl>().eq("tenant_id", tenantId)
                        .eq("uid", form.targetId()).eq("datasource_id", form.datasourceId()));
            }
        } else {
            requireOwnedRole(tenantId, form.targetId());
            switch (kind) {
                case KIND_FIELD -> {
                    var q = new QueryWrapper<RoleFieldAcl>().eq("tenant_id", tenantId)
                            .eq("custom_role_id", form.targetId()).eq("datasource_id", form.datasourceId())
                            .eq("column_name", form.columnName());
                    if (form.tableName() == null) q.isNull("table_name"); else q.eq("table_name", form.tableName());
                    roleFieldAclMapper.delete(q);
                }
                case KIND_TABLE -> roleTableAclMapper.delete(new QueryWrapper<RoleTableAcl>().eq("tenant_id", tenantId)
                        .eq("custom_role_id", form.targetId()).eq("datasource_id", form.datasourceId())
                        .eq("table_name", form.tableName()));
                default -> roleDsAclMapper.delete(new QueryWrapper<RoleDatasourceAcl>().eq("tenant_id", tenantId)
                        .eq("custom_role_id", form.targetId()).eq("datasource_id", form.datasourceId()));
            }
        }
        Map<String, Object> detail = detail(form.targetType(), form.targetId(), form.datasourceId(),
                form.columnName(), form.tableName(), kind);
        auditService.record(AuditAction.DATA_ACL_CLEAR, AuditAction.RESULT_OK, tenantId,
                TARGET_USER.equals(form.targetType()) ? form.targetId() : null,
                "DATA_ACL", String.valueOf(form.datasourceId()), detail);
    }

    // ==================== 读：列出 ====================

    /** 列出某成员的全部数据授权（数据源可见性 + 字段脱敏 + 表级可见性）。 */
    public List<DataAclItemVO> listUser(Long uid) {
        Long tenantId = requireCtx().tenantId();
        List<DataAclItemVO> items = new ArrayList<>();
        for (UserDatasourceAcl a : userDsAclMapper.selectList(new QueryWrapper<UserDatasourceAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid))) {
            items.add(new DataAclItemVO(TARGET_USER, uid, a.getDatasourceId(), null, null, a.getEffect(), KIND_DATASOURCE));
        }
        for (UserFieldAcl a : userFieldAclMapper.selectList(new QueryWrapper<UserFieldAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid))) {
            items.add(new DataAclItemVO(TARGET_USER, uid, a.getDatasourceId(), a.getColumnName(), a.getTableName(), a.getEffect(), KIND_FIELD));
        }
        for (UserTableAcl a : userTableAclMapper.selectList(new QueryWrapper<UserTableAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid))) {
            items.add(new DataAclItemVO(TARGET_USER, uid, a.getDatasourceId(), null, a.getTableName(), a.getEffect(), KIND_TABLE));
        }
        return items;
    }

    /** 列出某自定义角色继承的全部数据授权。 */
    public List<DataAclItemVO> listRole(Long customRoleId) {
        Long tenantId = requireCtx().tenantId();
        requireOwnedRole(tenantId, customRoleId);
        List<DataAclItemVO> items = new ArrayList<>();
        for (RoleDatasourceAcl a : roleDsAclMapper.selectList(new QueryWrapper<RoleDatasourceAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", customRoleId))) {
            items.add(new DataAclItemVO(TARGET_ROLE, customRoleId, a.getDatasourceId(), null, null, a.getEffect(), KIND_DATASOURCE));
        }
        for (RoleFieldAcl a : roleFieldAclMapper.selectList(new QueryWrapper<RoleFieldAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", customRoleId))) {
            items.add(new DataAclItemVO(TARGET_ROLE, customRoleId, a.getDatasourceId(), a.getColumnName(), a.getTableName(), a.getEffect(), KIND_FIELD));
        }
        for (RoleTableAcl a : roleTableAclMapper.selectList(new QueryWrapper<RoleTableAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", customRoleId))) {
            items.add(new DataAclItemVO(TARGET_ROLE, customRoleId, a.getDatasourceId(), null, a.getTableName(), a.getEffect(), KIND_TABLE));
        }
        return items;
    }

    // ==================== 私有工具 ====================

    /** 由表单推断授权维度：tableName 非空 → TABLE；否则 columnName 非空 → FIELD；否则 DATASOURCE。 */
    private static String resolveKind(DataAclForm form) {
        // 列名优先：字段脱敏可带 tableName（表.列级），故先判 columnName 再判 tableName，避免被误判为表级可见性。
        if (isField(form.columnName())) {
            return KIND_FIELD;
        }
        if (isTable(form.tableName())) {
            return KIND_TABLE;
        }
        return KIND_DATASOURCE;
    }

    private static String resolveKind(DataAclClearForm form) {
        if (isField(form.columnName())) {
            return KIND_FIELD;
        }
        if (isTable(form.tableName())) {
            return KIND_TABLE;
        }
        return KIND_DATASOURCE;
    }

    private static boolean isField(String columnName) {
        return columnName != null && !columnName.isBlank();
    }

    private static boolean isTable(String tableName) {
        return tableName != null && !tableName.isBlank();
    }

    private void validate(String targetType, String effect, String kind) {
        if (!TARGET_USER.equals(targetType) && !TARGET_ROLE.equals(targetType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetType 仅支持 USER / ROLE");
        }
        if (effect == null || effect.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "effect 必填");
        }
        if (KIND_FIELD.equals(kind)) {
            if (!UserFieldAcl.EFFECT_MASK.equals(effect) && !UserFieldAcl.EFFECT_UNMASK.equals(effect)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "字段脱敏 effect 仅支持 MASK / UNMASK");
            }
        } else if (!UserDatasourceAcl.EFFECT_HIDDEN.equals(effect) && !UserDatasourceAcl.EFFECT_ALLOW.equals(effect)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源/表级可见性 effect 仅支持 HIDDEN / ALLOW");
        }
    }

    private void requireOwnedRole(Long tenantId, Long customRoleId) {
        CustomRole role = customRoleMapper.selectById(customRoleId);
        if (role == null || !tenantId.equals(role.getTenantId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "自定义角色不存在或不属于本组织");
        }
    }

    private AccessContext requireCtx() {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope()) {
            throw new BizException(ErrorCode.TENANT_MISSING);
        }
        return ctx;
    }

    private void upsertUserDs(Long tenantId, Long uid, Long ds, String effect, Long actor) {
        userDsAclMapper.delete(new QueryWrapper<UserDatasourceAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid).eq("datasource_id", ds));
        userDsAclMapper.insert(new UserDatasourceAcl(tenantId, uid, ds, effect, actor));
    }

    private void upsertUserField(Long tenantId, Long uid, Long ds, String tbl, String col, String effect, Long actor) {
        var q = new QueryWrapper<UserFieldAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid).eq("datasource_id", ds).eq("column_name", col);
        if (tbl == null) q.isNull("table_name"); else q.eq("table_name", tbl);
        userFieldAclMapper.delete(q);
        userFieldAclMapper.insert(new UserFieldAcl(tenantId, uid, ds, tbl, col, effect, actor));
    }

    private void upsertUserTable(Long tenantId, Long uid, Long ds, String tbl, String effect, Long actor) {
        userTableAclMapper.delete(new QueryWrapper<UserTableAcl>()
                .eq("tenant_id", tenantId).eq("uid", uid).eq("datasource_id", ds).eq("table_name", tbl));
        userTableAclMapper.insert(new UserTableAcl(tenantId, uid, ds, tbl, effect, actor));
    }

    private void upsertRoleDs(Long tenantId, Long roleId, Long ds, String effect) {
        roleDsAclMapper.delete(new QueryWrapper<RoleDatasourceAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", roleId).eq("datasource_id", ds));
        roleDsAclMapper.insert(new RoleDatasourceAcl(tenantId, roleId, ds, effect));
    }

    private void upsertRoleField(Long tenantId, Long roleId, Long ds, String tbl, String col, String effect) {
        var q = new QueryWrapper<RoleFieldAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", roleId).eq("datasource_id", ds).eq("column_name", col);
        if (tbl == null) q.isNull("table_name"); else q.eq("table_name", tbl);
        roleFieldAclMapper.delete(q);
        roleFieldAclMapper.insert(new RoleFieldAcl(tenantId, roleId, ds, tbl, col, effect));
    }

    private void upsertRoleTable(Long tenantId, Long roleId, Long ds, String tbl, String effect) {
        roleTableAclMapper.delete(new QueryWrapper<RoleTableAcl>()
                .eq("tenant_id", tenantId).eq("custom_role_id", roleId).eq("datasource_id", ds).eq("table_name", tbl));
        roleTableAclMapper.insert(new RoleTableAcl(tenantId, roleId, ds, tbl, effect));
    }

    private Map<String, Object> detail(String targetType, Long targetId, Long datasourceId,
                                       String columnName, String tableName, String kind) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("targetType", targetType);
        detail.put("targetId", targetId);
        detail.put("datasourceId", datasourceId);
        if (columnName != null) {
            detail.put("columnName", columnName);
        }
        if (tableName != null) {
            detail.put("tableName", tableName);
        }
        detail.put("kind", kind);
        return detail;
    }
}
