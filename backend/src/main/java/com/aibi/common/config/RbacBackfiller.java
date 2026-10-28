package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * RBAC 存量数据回填协作类（T8.1 补账自 RbacSeeder 抽出，SQL 与语义原样平移；设计 §11.2 的 7 步，全部幂等）。
 *
 * <p><b>回填顺序不可调换</b>：步骤 1 产出的 {@code identity_id} 是步骤 7 的前提，
 * 步骤 2 产出的默认团队 id 是步骤 3/6 的前提，步骤 5 的 {@code owner_uid} 又被步骤 2 的团队负责人复用。
 *
 * <p>每步都是集合式语句 + 唯一键/空值守卫，重复执行结果一致。不包在一个事务里：
 * 元数据库为远端单实例，长事务会持有大量行锁并撑大 undo log；而各步本身幂等，
 * 中途失败下次启动自愈，比整体回滚更适合启动期批处理。
 */
@Component
class RbacBackfiller {

    private static final Logger log = LoggerFactory.getLogger(RbacBackfiller.class);

    /** 默认团队编码，DDL 约定 {@code default} 不可删改。 */
    private static final String DEFAULT_TEAM_CODE = "default";

    private final JdbcTemplate metaJdbc;

    RbacBackfiller(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    /** 依序执行 7 步回填（在演示域播种之后调用——要读到刚插入的 tenant/app_user 行）。 */
    void backfill() {
        int identity = backfillIdentities();
        int teams = backfillDefaultTeams();
        int members = backfillTeamMembers();
        int grants = backfillUserRoles();
        int tenants = backfillTenantFacts();
        int currentTeam = backfillCurrentTeam();
        if (identity + teams + members + grants + tenants + currentTeam > 0) {
            log.info("RBAC 存量回填完成: identity={}, 默认团队={}, 团队成员={}, 角色授予={}, 租户事实={}, 当前团队={}",
                    identity, teams, members, grants, tenants, currentTeam);
        } else {
            log.debug("RBAC 存量回填: 无需处理（已是最新）");
        }
    }

    /**
     * 步骤 1 + 7：为每个 {@code app_user} 生成或归并 {@code user_identity}。
     *
     * <p>按 {@code email} 归并——方案 A 下同一个自然人在多个组织各有 {@code app_user}，
     * 但口令与联系方式只能有一份事实源。{@code email} 为空时无法归并，每人一个 identity
     * （MySQL 唯一索引允许多个 NULL，故 {@code uk_email} 不会冲突）。
     *
     * <p>步骤 7（为 {@code superadmin} 建 identity）在此自然完成：它 {@code tenant_id=0} 且无 email，
     * 走"每人一个"分支；其平台角色由步骤 4 补登。
     */
    private int backfillIdentities() {
        // 1a 有 email：先建缺失的 identity（同 email 只建一行，口令取最早账号的）
        // 用 LEFT JOIN 而非 NOT EXISTS 判重：MySQL 手册明确允许目标表出现在 INSERT..SELECT 的
        // FROM 子句里，但对"在子查询中读取正在写入的同一张表"表述含混，不必去踩这个边界
        metaJdbc.update("""
                INSERT INTO user_identity (email, password_hash, nickname, auth_source, status)
                SELECT d.email,
                       (SELECT p.password_hash FROM app_user p WHERE p.email = d.email ORDER BY p.id LIMIT 1),
                       (SELECT n.nickname FROM app_user n WHERE n.email = d.email ORDER BY n.id LIMIT 1),
                       'LOCAL', 1
                FROM (SELECT DISTINCT email FROM app_user
                      WHERE identity_id IS NULL AND email IS NOT NULL AND email <> '') d
                LEFT JOIN user_identity ui ON ui.email = d.email
                WHERE ui.id IS NULL
                """);
        // 1a' 回写外键
        int linked = metaJdbc.update("""
                UPDATE app_user u JOIN user_identity ui ON ui.email = u.email
                SET u.identity_id = ui.id
                WHERE u.identity_id IS NULL AND u.email IS NOT NULL AND u.email <> ''
                """);

        // 1b 无 email：逐行插入以取回自增 id（集合式 INSERT..SELECT 无法把新 id 映射回各行）
        List<Map<String, Object>> orphans = metaJdbc.queryForList(
                "SELECT id, password_hash, nickname FROM app_user WHERE identity_id IS NULL");
        int created = 0;
        for (Map<String, Object> row : orphans) {
            Object hash = row.get("password_hash");
            if (hash == null) {
                // user_identity.password_hash 为 NOT NULL；无口令的行（若存在）只能留空并告警，
                // 强行插一个占位哈希会让该账号从此无法登录
                log.warn("app_user id={} 无 password_hash，跳过 identity 生成", row.get("id"));
                continue;
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            metaJdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO user_identity (email, phone, password_hash, nickname, auth_source, status)
                        VALUES (NULL, NULL, ?, ?, 'LOCAL', 1)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, hash.toString());
                ps.setObject(2, row.get("nickname"));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                created += metaJdbc.update("UPDATE app_user SET identity_id = ? WHERE id = ?",
                        key.longValue(), row.get("id"));
            }
        }
        return linked + created;
    }

    /** 步骤 2：为每个租户建默认团队（{@code code='default'}、{@code name='默认空间'}）并回写 {@code default_team_id}。 */
    private int backfillDefaultTeams() {
        int created = metaJdbc.update("""
                INSERT INTO team (tenant_id, parent_id, name, code, owner_uid, description, status, sort)
                SELECT t.id, NULL, '默认空间', ?, t.owner_uid, '升级时自动创建的默认团队', 1, 0
                FROM tenant t
                WHERE NOT EXISTS (SELECT 1 FROM team x WHERE x.tenant_id = t.id AND x.code = ?)
                """, DEFAULT_TEAM_CODE, DEFAULT_TEAM_CODE);
        int linked = metaJdbc.update("""
                UPDATE tenant t JOIN team x ON x.tenant_id = t.id AND x.code = ?
                SET t.default_team_id = x.id
                WHERE t.default_team_id IS NULL
                """, DEFAULT_TEAM_CODE);
        return created + linked;
    }

    /**
     * 步骤 3：把每个租户的全部 {@code app_user} 加入默认团队。
     *
     * <p>团队角色一律给 {@code TEAM_OWNER}：存量每租户通常只有注册者一人，给负责人合理；
     * 且 {@code TEAM_OWNER} 的权限由 ANALYST 派生，不会超过其组织角色（ADMIN/OWNER）已有的范围，
     * 因此这一步不会放大任何人的实际权限。
     *
     * <p>{@code tenant_id > 0} 排除平台身份（{@code tenant_id=0} 无团队维度）。
     */
    private int backfillTeamMembers() {
        return metaJdbc.update("""
                INSERT INTO team_member (tenant_id, team_id, uid, team_role, status, joined_at)
                SELECT u.tenant_id, x.id, u.id, 'TEAM_OWNER', 1, NOW()
                FROM app_user u JOIN team x ON x.tenant_id = u.tenant_id AND x.code = ?
                WHERE u.tenant_id > 0
                  AND NOT EXISTS (SELECT 1 FROM team_member m
                                  WHERE m.tenant_id = u.tenant_id AND m.team_id = x.id AND m.uid = u.id)
                """, DEFAULT_TEAM_CODE);
    }

    /**
     * 步骤 4 + 7：把 {@code app_user.role} 映射为 {@code user_role} 授予行。
     *
     * <p>平台域：{@code role='SUPER_ADMIN'} 且 {@code tenant_id=0} → {@code PLATFORM/SUPER_ADMIN}（步骤 7 的另一半）。
     *
     * <p><b>组织域与设计文档的一处有意偏离</b>：文档步骤 4 写"{@code USER} → {@code OWNER}"，
     * 理由是"存量每租户只有 1 人"。但超管后台（{@code AdminUserController}）可以在任意租户下建账号，
     * 一旦出现多账号租户，照抄会让<b>每个</b>存量账号都成为组织所有者——那是无法回收的批量提权。
     * 故改为：每租户最早的账号（{@code MIN(id)}，即注册者）→ {@code OWNER}，其余 → {@code ADMIN}。
     * {@code ADMIN} 与升级前的实际能力等价（数据源、调优、指标、驾驶舱、分享、成员管理全有），
     * 只少了升级前根本不存在的转让/解散/续订，因此不构成行为回归。
     *
     * <p><b>已知边界</b>：{@code MIN(id)} 不排除已停用账号。若某组织的注册者后来被停用，
     * 该组织的 {@code OWNER} 与 {@code tenant.owner_uid} 会指向一个无法登录的账号，转让/解散入口随之不可用。
     * 此处不改为"最早的活跃账号"：那会让步骤 4 与步骤 5 的判定条件必须同步改动，
     * 且停用注册者的组织本就需要平台运营介入（P4 的代客处置），不值得在回填里为它引入第二套规则。
     */
    private int backfillUserRoles() {
        int platform = metaJdbc.update("""
                INSERT INTO user_role (tenant_id, uid, role_code, scope_type, scope_id, granted_by, status)
                SELECT 0, u.id, 'SUPER_ADMIN', 'PLATFORM', 0, NULL, 1
                FROM app_user u
                WHERE u.tenant_id = 0 AND u.role = 'SUPER_ADMIN'
                  AND NOT EXISTS (SELECT 1 FROM user_role r
                                  WHERE r.uid = u.id AND r.role_code = 'SUPER_ADMIN'
                                    AND r.scope_type = 'PLATFORM' AND r.scope_id = 0)
                """);
        int owner = metaJdbc.update("""
                INSERT INTO user_role (tenant_id, uid, role_code, scope_type, scope_id, granted_by, status)
                SELECT u.tenant_id, u.id, 'OWNER', 'TENANT', 0, NULL, 1
                FROM app_user u
                WHERE u.tenant_id > 0
                  AND u.id = (SELECT MIN(x.id) FROM app_user x WHERE x.tenant_id = u.tenant_id)
                  AND NOT EXISTS (SELECT 1 FROM user_role r
                                  WHERE r.uid = u.id AND r.role_code = 'OWNER'
                                    AND r.scope_type = 'TENANT' AND r.scope_id = 0)
                """);
        int admin = metaJdbc.update("""
                INSERT INTO user_role (tenant_id, uid, role_code, scope_type, scope_id, granted_by, status)
                SELECT u.tenant_id, u.id, 'ADMIN', 'TENANT', 0, NULL, 1
                FROM app_user u
                WHERE u.tenant_id > 0
                  AND u.id <> (SELECT MIN(x.id) FROM app_user x WHERE x.tenant_id = u.tenant_id)
                  AND NOT EXISTS (SELECT 1 FROM user_role r
                                  WHERE r.uid = u.id AND r.role_code = 'ADMIN'
                                    AND r.scope_type = 'TENANT' AND r.scope_id = 0)
                """);
        return platform + owner + admin;
    }

    /**
     * 步骤 5：回写 {@code tenant.owner_uid} / {@code seat_used} / {@code seat_limit}，并把默认团队负责人补齐。
     *
     * <p>{@code owner_uid} 取该租户最早的账号（{@code MIN(id)}），与步骤 4 的 OWNER 判定同源，
     * 二者必须一致——否则"谁是所有者"在 {@code tenant} 与 {@code user_role} 里会有两个答案。
     *
     * <p>{@code seat_used} 每次都按 {@code status=1} 的成员数重算而非只在为 0 时填：它是可推导量，
     * 重算即自愈，能修掉 P3 席位原子占位在异常路径下留下的偏差。但<b>必须带差异守卫</b>：
     * MySQL 驱动默认按"匹配行数"而非"实际变更行数"返回，无守卫时本语句每次启动都返回租户总数，
     * 使 {@link #backfill()} 的汇总恒为正——稳态重启与首次升级在日志上再也无法区分，
     * 而这条日志正是判断"回填是否还在反复写库"的唯一线索。
     *
     * <p>{@code LEFT JOIN} 而非 {@code INNER JOIN}：活跃成员为 0 的租户不会出现在派生表里，
     * 内连接会让它们的 {@code seat_used} 永远停在旧值（例如成员全部被停用后仍显示占用席位）。
     *
     * <p><b>{@code seat_limit} 的守卫是权宜之计</b>：条件 {@code seat_limit = -1} 表示"仍是 DDL 兼容默认值"。
     * P5 的席位调整接口（{@code PUT /api/admin/tenants/{id}/seats}）落地后，运营若把某租户设为不限（-1），
     * 下次重启会被这里重置回套餐默认值——届时必须引入显式的"不限"标记或改为一次性迁移，
     * 不能继续沿用本守卫。当前无消费方，风险为零，故按设计文档先落值。
     */
    private int backfillTenantFacts() {
        int owner = metaJdbc.update("""
                UPDATE tenant t
                SET t.owner_uid = (SELECT MIN(u.id) FROM app_user u WHERE u.tenant_id = t.id)
                WHERE t.owner_uid IS NULL
                  AND EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t.id)
                """);
        int seatUsed = metaJdbc.update("""
                UPDATE tenant t
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS c FROM app_user WHERE status = 1 GROUP BY tenant_id) s
                       ON s.tenant_id = t.id
                SET t.seat_used = COALESCE(s.c, 0)
                WHERE t.seat_used <> COALESCE(s.c, 0)
                """);
        int seatLimit = metaJdbc.update("""
                UPDATE tenant
                SET seat_limit = CASE plan WHEN 'FREE' THEN 3 WHEN 'PRO' THEN 20 ELSE -1 END
                WHERE plan IN ('FREE', 'PRO') AND seat_limit = -1
                """);
        // 默认团队负责人：步骤 2 建团队时 owner_uid 可能还是 NULL（依赖本步骤才回填）
        int teamOwner = metaJdbc.update("""
                UPDATE team x JOIN tenant t ON t.id = x.tenant_id
                SET x.owner_uid = t.owner_uid
                WHERE x.code = ? AND x.owner_uid IS NULL AND t.owner_uid IS NOT NULL
                """, DEFAULT_TEAM_CODE);
        return owner + seatUsed + seatLimit + teamOwner;
    }

    /** 步骤 6：{@code app_user.current_team_id} 指向所属默认团队，使升级后首次登录即有团队上下文。 */
    private int backfillCurrentTeam() {
        return metaJdbc.update("""
                UPDATE app_user u JOIN team x ON x.tenant_id = u.tenant_id AND x.code = ?
                SET u.current_team_id = x.id
                WHERE u.current_team_id IS NULL AND u.tenant_id > 0
                """, DEFAULT_TEAM_CODE);
    }
}
