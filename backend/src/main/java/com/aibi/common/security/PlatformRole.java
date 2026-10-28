package com.aibi.common.security;

/**
 * 平台运营角色码（{@code role.level='PLATFORM'}），见《角色权限体系与组织协同设计》3.2。
 *
 * <p>只在 {@code tenant_id=0} 的平台身份上生效；租户身份即使库里存在平台角色行，
 * 构建上下文时也必须强制置为空集（见 3.6①），防止"超管身份"被带进租户域形成跨域提权。
 */
public final class PlatformRole {

    /** 平台超级管理员：唯一可授出平台角色的角色。 */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** 运营用户：租户开通/续费/停用、席位与配额、内容运营、邀请治理。 */
    public static final String OPERATOR = "OPERATOR";

    /** 平台审计：只读全平台审计日志、登录记录、越权拦截记录。 */
    public static final String AUDITOR = "AUDITOR";

    /** 技术支持：只读诊断 + 发起代客协助授权申请（ASSIST 影子令牌）。 */
    public static final String SUPPORT = "SUPPORT";

    /** Spring Security {@code hasAnyRole} 用的全部平台角色（L1 域门禁）。 */
    public static final String[] ALL = {SUPER_ADMIN, OPERATOR, AUDITOR, SUPPORT};

    private PlatformRole() {
    }

    /** 是否为平台角色码（用于拒绝把平台角色授予到 TENANT/TEAM scope）。 */
    public static boolean isPlatform(String code) {
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
}
