package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 字典播种协作类（T8.1 补账自 RbacSeeder 抽出，逻辑原样平移）。
 *
 * <p>角色 / 权限点 / 角色→权限关联三张字典表逐条按唯一键判缺补齐（老库也能拿到新增权限点）；
 * {@code role_permission} 全部由 {@link RbacSeedDict} 的矩阵标记位派生，不另存清单。
 *
 * <p><b>启动成本</b>：稳态重启只做 3 次全表 SELECT（{@code role}/{@code permission}/{@code role_permission}
 * 均为字典表，行数分别约 13/95/700），不逐行 COUNT。元数据库在远端时，逐行判定会让启动多出数十秒。
 */
@Component
class RbacDictSeeder {

    private static final Logger log = LoggerFactory.getLogger(RbacDictSeeder.class);

    private final JdbcTemplate metaJdbc;

    RbacDictSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    /** 自检 + 播种三字典；字典/授权确有新增时抬高 perm_version（稳态重启不抬，不强制全员重登）。 */
    void seed() {
        RbacSeedDict.validate();
        seedRoles();
        // 权限字典 / 角色授权确有新增时，抬高所有租户的 perm_version，
        // 让已登录会话的旧 JWT(pv 失配) 失效并重登刷新权限快照（见 bumpTenantPermVersion）。
        int addedPerms = seedPermissions();
        int addedRolePerms = seedRolePermissions();
        if (addedPerms > 0 || addedRolePerms > 0) {
            bumpTenantPermVersion();
        }
    }

    private void seedRoles() {
        Set<String> existing = new HashSet<>(metaJdbc.queryForList("SELECT code FROM role", String.class));
        List<String> added = new ArrayList<>();
        for (int i = 0; i < RbacSeedDict.ROLES.length; i++) {
            String[] f = RbacSeedDict.ROLES[i].split("\\|", -1);
            if (existing.contains(f[0])) {
                continue;
            }
            metaJdbc.update("""
                    INSERT INTO role (code, name, level, data_scope, inherit_to_child, builtin, description, sort, enabled)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?, 1)
                    """, f[0], f[1], f[2], f[3], Integer.parseInt(f[4]), f[5], (i + 1) * 10);
            added.add(f[0]);
        }
        log.info("角色字典已就绪: 共 {} 个内置角色，本次新增 {}", RbacSeedDict.ROLES.length, added.isEmpty() ? "无" : added);
    }

    /** @return 本次新增的权限点数量（用于判定是否需要抬高 perm_version）。 */
    private int seedPermissions() {
        Set<String> existing = new HashSet<>(metaJdbc.queryForList("SELECT code FROM permission", String.class));
        int added = 0;
        int sort = 0;
        for (String seed : RbacSeedDict.TENANT_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            sort += 10;
            if (existing.contains(f[0])) {
                continue;
            }
            insertPermission(f[0], f[1], f[2], f[3], emptyToNull(f[4]), sort);
            added++;
        }
        for (String seed : RbacSeedDict.PLATFORM_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            sort += 10;
            if (existing.contains(f[0])) {
                continue;
            }
            insertPermission(f[0], f[1], f[2], RbacSeedDict.LEVEL_PLATFORM, emptyToNull(f[3]), sort);
            added++;
        }
        log.info("权限点字典已就绪: 共 {} 个（组织域 {} + 平台域 {}），本次新增 {}",
                RbacSeedDict.TENANT_PERMISSIONS.length + RbacSeedDict.PLATFORM_PERMISSIONS.length,
                RbacSeedDict.TENANT_PERMISSIONS.length, RbacSeedDict.PLATFORM_PERMISSIONS.length, added);
        return added;
    }

    private void insertPermission(String code, String name, String type, String level, String menuCode, int sort) {
        metaJdbc.update("""
                INSERT INTO permission (code, name, module, level, type, menu_code, builtin, enabled, sort)
                VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?)
                """, code, name, moduleOf(code), level, type, menuCode, sort);
    }

    /**
     * 播种角色→权限点关联，全部由权限矩阵的标记位派生。
     *
     * <p><b>团队角色的权限不另列清单</b>，而是从组织矩阵按 {@code level=TEAM} 过滤后派生：
     * {@code TEAM_EDITOR} = ANALYST 的团队级权限点、{@code TEAM_VIEWER} = VIEWER 的团队级权限点、
     * {@code TEAM_OWNER} = TEAM_EDITOR + {@code team:member:write}。三者与 §3.4 的职责描述逐条对得上，
     * 且天然满足 §3.1 的硬约束（团队角色只能绑定 {@code level ∈ {TEAM, RESOURCE}} 的权限点）。
     *
     * <p><b>已知缺口</b>：§3.4 说 TEAM_OWNER 有"团队级邀请"与"团队设置"，但
     * {@code member:invite:create} 与 {@code team:team:write} 都是 {@code level=TENANT}，
     * 按硬约束不可绑定到团队角色。P3 落地团队级邀请时需二选一：新增一个 TEAM 级的
     * {@code team:invite:create} 权限点，或放宽约束——此处不擅自发明权限码。
     *
     * @return 本次新增的角色→权限关联数量（用于判定是否需要抬高 perm_version）。
     */
    private int seedRolePermissions() {
        Set<String> existing = new HashSet<>();
        for (Map<String, Object> row : metaJdbc.queryForList("SELECT role_code, permission_code FROM role_permission")) {
            existing.add(row.get("role_code") + "|" + row.get("permission_code"));
        }
        int before = existing.size();
        List<String> pending = new ArrayList<>();
        for (String seed : RbacSeedDict.TENANT_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            String code = f[0];
            String flags = f[5];
            boolean teamLevel = RbacSeedDict.LEVEL_TEAM.equals(f[3]);
            for (int i = 0; i < RbacSeedDict.ORG_ROLES.length; i++) {
                if (flags.charAt(i) == 'Y') {
                    collect(pending, existing, RbacSeedDict.ORG_ROLES[i], code);
                }
            }
            if (!teamLevel) {
                continue;
            }
            // 团队角色派生：ANALYST → TEAM_EDITOR/TEAM_OWNER，VIEWER → TEAM_VIEWER
            if (flags.charAt(RbacSeedDict.FLAG_ANALYST) == 'Y') {
                collect(pending, existing, "TEAM_EDITOR", code);
                collect(pending, existing, "TEAM_OWNER", code);
            }
            if (flags.charAt(RbacSeedDict.FLAG_VIEWER) == 'Y') {
                collect(pending, existing, "TEAM_VIEWER", code);
            }
        }
        // TEAM_OWNER 相对 TEAM_EDITOR 多出的唯一一项：本团队内的成员管理
        collect(pending, existing, "TEAM_OWNER", "team:member:write");

        for (String seed : RbacSeedDict.PLATFORM_PERMISSIONS) {
            String[] f = seed.split("\\|", -1);
            String flags = f[4];
            for (int i = 0; i < RbacSeedDict.PLATFORM_ROLES.length; i++) {
                if (flags.charAt(i) == 'Y') {
                    collect(pending, existing, RbacSeedDict.PLATFORM_ROLES[i], f[0]);
                }
            }
        }

        for (String pair : pending) {
            int sep = pair.indexOf('|');
            metaJdbc.update("INSERT INTO role_permission (role_code, permission_code) VALUES (?, ?)",
                    pair.substring(0, sep), pair.substring(sep + 1));
        }
        log.info("角色权限关联已就绪: 原有 {} 条，本次新增 {} 条", before, pending.size());
        return pending.size();
    }

    /**
     * 权限字典 / 角色授权发生新增时，抬高所有租户的 {@code perm_version}。
     *
     * <p>作用：已登录会话的 JWT 携带的旧 {@code pv} 与新的 {@code perm_version} 失配，
     * 下次请求被 {@code JwtAuthFilter} 判为"权限已变更"返回 401/40311，前端据此清 auth 跳登录、
     * 重新拉取权限快照——从而让"新增权限点"对存量会话自动生效，避免
     * "后端已授但前端旧快照不含 → 路由守卫 403"的坑（站内信 message:inbox:read 即此情形）。
     *
     * <p>只在确有新增时调用（{@code added>0}）：稳态重启不抬版本，不强制全员重登。
     */
    private void bumpTenantPermVersion() {
        int n = metaJdbc.update("UPDATE tenant SET perm_version = COALESCE(perm_version, 0) + 1");
        log.info("检测到权限字典/授权新增，已抬高 {} 个租户的 perm_version（旧会话将失效并重登刷新权限）", n);
    }

    /** 收集待插入的关联（去重靠 {@code existing}，插入后回写以免同一对在一次运行内被收集两次）。 */
    private void collect(List<String> pending, Set<String> existing, String roleCode, String permissionCode) {
        String key = roleCode + "|" + permissionCode;
        if (existing.add(key)) {
            pending.add(key);
        }
    }

    // ==================== 工具 ====================

    /** 权限码首段即模块名（§4.1 的 {@code {module}:{resource}:{action}} 规范）。 */
    private String moduleOf(String code) {
        int idx = code.indexOf(':');
        return idx > 0 ? code.substring(0, idx) : code;
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
