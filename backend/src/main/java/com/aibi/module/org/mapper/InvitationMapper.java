package com.aibi.module.org.mapper;

import com.aibi.module.org.entity.Invitation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 组织邀请 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p><b>务必注意</b>：{@code invitation} 带 {@code tenant_id} 却列入租户拦截器 IGNORE 名单
 * （为支持匿名接受，凭 {@code token_hash} 全局寻址），因此管理侧的每条 SQL 都<b>显式携带
 * {@code tenant_id}</b>，这是该表唯一的隔离保障，新增查询漏写即造成跨组织越权。
 *
 * <p>{@link #selectByTokenHash(String)} 是唯一<b>不带</b> {@code tenant_id} 的查询：接受链路凭令牌哈希
 * 全局定位邀请（此时租户上下文尚未建立），命中后再校验其 {@code tenant_id} 与状态。
 *
 * <p>方法名<b>不可</b>与 {@code BaseMapper} 的 {@code selectOne/selectList} 等重载同名，
 * 否则 MyBatis 注册 statement id 时会与内置方法冲突而启动失败（同 {@code TeamMemberMapper.selectMember}）。
 *
 * <p>{@link #consumeSeat}/{@link #releaseSeat} 的原子占用/回滚已由接受链路 {@code InvitationService.accept} 真实消费。
 */
public interface InvitationMapper extends BaseMapper<Invitation> {

    /**
     * 凭令牌哈希全局寻址（接受链路用，<b>不带</b> {@code tenant_id}）。
     *
     * <p>{@code token_hash} 有唯一索引，至多一行。命中后由服务层校验租户归属、状态、过期与名额。
     */
    @Select("SELECT * FROM invitation WHERE token_hash = #{tokenHash} LIMIT 1")
    Invitation selectByTokenHash(@Param("tokenHash") String tokenHash);

    /** 本组织全部邀请（邀请列表页，按创建时间倒序）。 */
    @Select("""
            SELECT * FROM invitation WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC, id DESC
            """)
    List<Invitation> selectByTenant(@Param("tenantId") Long tenantId);

    /**
     * 精确取单条邀请（撤销前的存在性与租户校验）。
     *
     * <p>显式带 {@code tenant_id}：防止撤销他组织的邀请（横向越权）。
     */
    @Select("SELECT * FROM invitation WHERE id = #{id} AND tenant_id = #{tenantId} LIMIT 1")
    Invitation selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * 原子占用一个邀请名额（接受链路用）：仅当不限名额（{@code seats < 0}）或未用尽时 {@code used_count + 1}。
     *
     * <p>与 {@link com.aibi.module.auth.mapper.TenantMapper#consumeSeat(Long)} 同事务、锁序固定为
     * 先 invitation 后 tenant（防死锁，见 {@code Tenant} 类注释）。接受链路 InvitationService.accept 已消费
     * （与 {@link com.aibi.module.auth.mapper.TenantMapper#consumeSeat(Long)} 同事务、锁序先 invitation 后 tenant）。
     *
     * @return 受影响行数；{@code 1}=占用成功，{@code 0}=名额已用尽（{@code INVITE_EXHAUSTED}）
     */
    @Update("""
            UPDATE invitation SET used_count = used_count + 1
            WHERE id = #{id} AND tenant_id = #{tenantId} AND (seats < 0 OR used_count < seats)
            """)
    int consumeSeat(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * 回滚一个邀请名额（原子占用失败时的补偿，如租户席位已满）：{@code used_count - 1}，带 {@code > 0} 守卫。
     *
     * @return 受影响行数；{@code 1}=回滚成功。
     */
    @Update("""
            UPDATE invitation SET used_count = used_count - 1
            WHERE id = #{id} AND tenant_id = #{tenantId} AND used_count > 0
            """)
    int releaseSeat(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
