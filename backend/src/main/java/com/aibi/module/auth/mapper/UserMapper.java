package com.aibi.module.auth.mapper;

import com.aibi.module.auth.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper（MyBatis-Plus）。由 {@code @MapperScan} 扫描注册。
 * app_user 在租户拦截器 IGNORE 名单，查询不会被自动追加 tenant_id。
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 只取身份令牌版本号（{@code PermVersionService} 的 pv 比对热路径，30s 缓存）。
     *
     * <p>{@code app_user} 在 IGNORE 名单，按主键 {@code id} 直查即可，无租户注入风险。
     * 行不存在时返回 {@code null}，由调用方按 0 处理。
     */
    @Select("SELECT token_version FROM app_user WHERE id = #{id}")
    Long selectTokenVersion(@Param("id") Long id);

    /**
     * 身份令牌版本 +1（停用/降级/移除成员/重置口令后调用）：使该成员旧 JWT 的 {@code pv} 失配，
     * 下一次请求即回 401 + {@code 40311} 强制重登（《角色权限体系与组织协同设计》6.3 分级吊销）。
     *
     * <p>{@code app_user} 在 IGNORE 名单，按主键 {@code id} 直更新即可，无租户注入风险。与
     * {@code LoginSessionService.revokeAllForUser} 叠加：后者 ≤1s 精确下线，前者令版本号不再被 30s 旧值遮蔽。
     */
    @Update("UPDATE app_user SET token_version = token_version + 1 WHERE id = #{id}")
    int bumpTokenVersion(@Param("id") Long id);

    /**
     * 按「自然人身份 + 组织」定位成员行（多组织切换 / 登录选组织用）。
     *
     * <p>{@code app_user} 在 IGNORE 名单，按 {@code identity_id + tenant_id} 直查即可，无租户注入风险。
     * 行不存在（该身份不是该组织成员）或已禁用（{@code status=1} 才返回）时返回 {@code null}。
     */
    @Select("SELECT * FROM app_user WHERE identity_id = #{identityId} AND tenant_id = #{tenantId} AND status = 1 LIMIT 1")
    User selectByIdentityAndTenant(@Param("identityId") Long identityId, @Param("tenantId") Long tenantId);
}
