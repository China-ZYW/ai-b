package com.aibi.common.security;

/**
 * 团队角色码（{@code role.level='TEAM'}，仅在所属团队及其子团队内生效），见《角色权限体系与组织协同设计》3.4。
 *
 * <p>团队角色<b>永远不能</b>携带组织级权限点：授予时按 {@code permission.level ∈ {TEAM, RESOURCE}} 强校验。
 * {@code role.inherit_to_child=1} 时对 {@code parent_id} 链上的全部子团队生效（默认 OWNER/EDITOR 继承、VIEWER 不继承）。
 */
public final class TeamRole {

    /** 团队负责人：团队内成员增删与角色分配（限团队角色）、团队资源全部、团队级邀请、团队设置。 */
    public static final String TEAM_OWNER = "TEAM_OWNER";

    /** 团队编辑者：团队内指标/驾驶舱/收藏/分享 CRUD、问数、团队资源读。 */
    public static final String TEAM_EDITOR = "TEAM_EDITOR";

    /** 团队只读：团队资源只读 + 问数（同 {@link OrgRole#VIEWER} 约束）。 */
    public static final String TEAM_VIEWER = "TEAM_VIEWER";

    /** 全部内置团队角色码（{@code team_member.team_role} 与 {@code user_role(scope_type='TEAM')} 共用）。 */
    public static final String[] ALL = {TEAM_OWNER, TEAM_EDITOR, TEAM_VIEWER};

    /** 新成员入组的默认团队角色（最小权限）。 */
    public static final String DEFAULT = TEAM_VIEWER;

    private TeamRole() {
    }

    /** 是否为内置团队角色码。 */
    public static boolean isTeam(String code) {
        if (code == null) {
            return false;
        }
        for (String r : ALL) {
            if (r.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 容错解析：未知或空值回退到 {@link #DEFAULT}（最小权限，安全默认）。 */
    public static String normalize(String code) {
        return isTeam(code) ? code : DEFAULT;
    }
}
