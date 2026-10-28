package com.aibi.common.security;

/**
 * 组织角色码（{@code role.level='TENANT'}，租户内生效），见《角色权限体系与组织协同设计》3.3。
 *
 * <p>实现上<b>不做隐式继承</b>：每个内置角色在 {@code role_permission} 中显式列出全部权限点，
 * 否则"给 VIEWER 加一个权限"就会变成改代码。{@code OWNER ⊃ ADMIN ⊃ DATA_STEWARD ∪ ANALYST ⊃ VIEWER}
 * 只是逻辑包含关系，便于阅读，不参与判定。
 */
public final class OrgRole {

    /** 组织所有者：全部组织权限点 + 转让 + 解散；唯一可无限制分配角色者。 */
    public static final String OWNER = "OWNER";

    /** 组织管理员：成员/团队/邀请/角色分配（不可授出 OWNER）、数据源与调优全部。 */
    public static final String ADMIN = "ADMIN";

    /** 数据管理员：数据源、语义层、样例库、图表规则、结果展示、主题；不含成员管理。 */
    public static final String DATA_STEWARD = "DATA_STEWARD";

    /** 分析师：问数 + 指标/驾驶舱/分享/收藏 CRUD，数据源与调优只读。 */
    public static final String ANALYST = "ANALYST";

    /** 只读成员：问数（可被组织开关关闭）+ 查看被授权资源；不可导出、不可分享、不可建指标。 */
    public static final String VIEWER = "VIEWER";

    /** 计费联系人：仅用量与账单只读、续订入口、席位查看；无业务数据权限。 */
    public static final String BILLING = "BILLING";

    /** 全部内置组织角色码（播种与校验用，顺序即权限宽窄降序）。 */
    public static final String[] ALL = {OWNER, ADMIN, DATA_STEWARD, ANALYST, VIEWER, BILLING};

    /** 不可被授予、只能走转让流程的角色（防"自建角色自我提权"）。 */
    public static final String[] NON_GRANTABLE = {OWNER};

    private OrgRole() {
    }

    /** 是否为内置组织角色码。 */
    public static boolean isOrg(String code) {
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

    /** 该角色是否具备成员管理能力（成员列表/邀请/移除）。 */
    public static boolean canManageMembers(String code) {
        return OWNER.equals(code) || ADMIN.equals(code);
    }

    /** 该角色是否可发起或接受邀请后直接入组（无需审批）。 */
    public static boolean canInvite(String code) {
        return OWNER.equals(code) || ADMIN.equals(code);
    }
}
