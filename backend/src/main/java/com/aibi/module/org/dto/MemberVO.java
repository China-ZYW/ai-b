package com.aibi.module.org.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织成员视图对象（§9.2 组织设置 · 成员列表，{@code member:member:read}）。
 *
 * <p>方案 A 下「成员」= 本组织内 {@code status=1} 的 {@code app_user} 行 + 其 {@code user_role}
 * （{@code scope_type=TENANT}）组织角色集合。<b>绝不含</b>口令哈希、令牌版本等敏感列。
 *
 * @param uid      {@code app_user.id}（即全项目的 {@code uid}）
 * @param orgRoles 组织角色码集合（OWNER/ADMIN/DATA_STEWARD/ANALYST/VIEWER/BILLING），按权限宽窄降序；
 *                 <b>仅含内置角色授予</b>（{@code custom_role_id} 为空），自定义角色授予见 {@code customRoles}
 * @param owner    是否组织所有者（{@code orgRoles} 含 OWNER）
 * @param self     是否当前登录者本人（前端据此禁用「移除自己 / 回收自己角色」等操作）
 * @param customRoles 该成员持有的自定义角色授予（可空=无）；前端据此展示「自定义角色」标签并按 id 精确回收
 */
public record MemberVO(
        Long uid,
        String username,
        String nickname,
        String email,
        String position,
        Integer status,
        List<String> orgRoles,
        boolean owner,
        boolean self,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        List<MemberCustomRoleVO> customRoles) {
}
