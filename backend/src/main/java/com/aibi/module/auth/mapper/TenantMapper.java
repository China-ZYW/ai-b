package com.aibi.module.auth.mapper;

import com.aibi.module.auth.entity.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户 Mapper（MyBatis-Plus）。tenant 为全局表，在租户拦截器 IGNORE 名单中。
 */
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 只取组织权限版本号（{@code PermVersionService} 的 pv 比对热路径，30s 缓存）。
     *
     * <p>不加载整行 {@link Tenant}：pv 校验每请求都可能触发，只读一列可显著降低回源成本。
     * 行不存在时返回 {@code null}，由调用方按 0 处理。
     */
    @Select("SELECT perm_version FROM tenant WHERE id = #{id}")
    Long selectPermVersion(@Param("id") Long id);

    /**
     * 原子占用一个累计名额：仅当不限总量（{@code total_limit < 0}）或未达上限时 {@code total_used + 1}。
     *
     * @return 受影响行数；{@code 1}=占用成功，{@code 0}=累计配额已用尽
     */
    @Update("""
            UPDATE tenant SET total_used = total_used + 1
            WHERE id = #{tenantId} AND (total_limit < 0 OR total_used < total_limit)
            """)
    int consumeTotal(@Param("tenantId") Long tenantId);

    /**
     * 组织权限版本 +1（组织级授权变更：转让所有权、批量角色调整）：使该组织<b>全部</b>成员旧 JWT 的
     * {@code pv} 失配而强制重登。单成员角色变更应改用 {@code UserMapper.bumpTokenVersion}（只影响本人，
     * 避免一次降级把全组织踢下线）。
     */
    @Update("UPDATE tenant SET perm_version = perm_version + 1 WHERE id = #{id}")
    int bumpPermVersion(@Param("id") Long id);

    /**
     * 原子释放一个席位（移除成员/退出组织）：{@code seat_used - 1}，带 {@code > 0} 守卫防越界为负。
     *
     * @return 受影响行数；{@code 1}=释放成功，{@code 0}=已用席位为 0（无需释放）
     */
    @Update("UPDATE tenant SET seat_used = seat_used - 1 WHERE id = #{id} AND seat_used > 0")
    int releaseSeat(@Param("id") Long id);

    /**
     * 原子占用一个席位（邀请接受时）：仅当不限席位（{@code seat_limit < 0}）或未达上限时 {@code seat_used + 1}。
     *
     * <p>与 {@code invitation.used_count} 的条件 UPDATE 同事务、锁序固定为先 invitation 后 tenant（防死锁，
     * 见 {@code Tenant} 类注释）。接受链路 {@code InvitationService.accept} 已消费（席位不足时回滚邀请席位）。
     *
     * @return 受影响行数；{@code 1}=占用成功，{@code 0}=席位已满（{@code SEAT_LIMIT_EXCEEDED}）
     */
    @Update("""
            UPDATE tenant SET seat_used = seat_used + 1
            WHERE id = #{id} AND (seat_limit < 0 OR seat_used < seat_limit)
            """)
    int consumeSeat(@Param("id") Long id);
}
