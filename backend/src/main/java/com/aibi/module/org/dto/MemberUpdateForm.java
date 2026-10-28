package com.aibi.module.org.dto;

/**
 * 成员资料更新表单（{@code member:member:write}，矩阵 YYNNNN → 仅 OWNER/ADMIN）。
 *
 * <p>仅覆盖<b>组织内</b>展示字段（昵称、职位，均落在 {@code app_user} 行上）。邮箱/手机属自然人身份
 * （{@code user_identity}）、跨组织共享，不在组织侧修改，故本表单不含。字段为 {@code null} 表示该项不改。
 */
public record MemberUpdateForm(
        String nickname,
        String position) {
}
