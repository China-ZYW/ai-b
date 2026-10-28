package com.aibi.module.config.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL 可见性判定（需求 §4「SQL 查看权限可配置，部分角色禁止查看原始 SQL」）。
 *
 * <p><b>为什么必须是后端判定而不是前端隐藏</b>：前端隐藏只是"看不见"，SQL 文本仍然随响应体下发，
 * 打开浏览器 DevTools / 抓包即可看到；真实的安全边界只能在服务端——无权角色拿到的 VO 里
 * 根本不含 SQL 字段。这是本类存在的唯一理由。
 *
 * <p><b>判定规则</b>（配置 {@code ATTRIBUTION / sql-viewable-roles}，见
 * {@link AibiConfig#sqlViewableRoles()}）：
 * <ol>
 *   <li>白名单为空 → <b>不限制</b>（沿用历史行为，避免升级后大面积功能"消失"）；</li>
 *   <li>白名单非空 → 当前身份须命中平台/组织/团队任一角色码；</li>
 *   <li>超级管理员恒放行（运维兜底，防止配置写错把自己锁死）；</li>
 *   <li>匿名上下文（持有者为空）→ 拒绝。这补上了公开分享/公开大屏的 SQL 暴露面。</li>
 * </ol>
 *
 * <p><b>fail-safe 取向</b>：仅在"明确要限制且明确无权限"时返回 false。配置读取异常或角色缺失
 * 不会误伤正常用户，但也绝不会因解析失败而放行已配置的限制。
 */
@Component
public class SqlVisibility {

    private final AibiConfig aibiConfig;

    public SqlVisibility(AibiConfig aibiConfig) {
        this.aibiConfig = aibiConfig;
    }

    /**
     * 当前身份是否允许查看原始 SQL。
     *
     * @return true=允许下发 SQL；false=调用方必须把 SQL 字段置空后再返回
     */
    public boolean canViewSql() {
        List<String> allowed = aibiConfig.sqlViewableRoles();
        // 未配置白名单 = 不启用该限制，沿用既有行为。
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        AccessContext ctx = AccessContextHolder.get();
        // 匿名（公开分享 / 公开大屏）：一旦启用了白名单，匿名必然不在其中。
        if (ctx == null) {
            return false;
        }
        if (ctx.isSuperAdmin()) {
            return true;
        }
        for (String role : allowed) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String code = role.trim();
            if (ctx.hasPlatformRole(code) || ctx.hasOrgRole(code) || ctx.hasTeamRole(code)) {
                return true;
            }
        }
        return false;
    }

    /** 按可见性返回 SQL 文本：不可见一律置 null（调用方统一走此方法，避免漏判）。 */
    public String mask(String sql) {
        return canViewSql() ? sql : null;
    }
}
