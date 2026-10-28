package com.aibi.module.auth.mapper;

import com.aibi.module.auth.entity.LoginSession;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录会话 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code login_session} 带 {@code tenant_id} 且<b>不在</b> IGNORE 名单，但下列查询一律用
 * {@code @InterceptorIgnore} 关闭自动注入并显式处理租户，原因是<b>调用时机</b>：
 * {@code JwtAuthFilter} 校验会话发生在上下文建立的过程中，若依赖自动注入，一旦顺序被后续改动
 * 打乱（先查会话、后设上下文）就会得到 {@code tenant_id = NULL} 恒不命中 → 全站 403，
 * 且故障表现与"令牌失效"完全一致，极难定位。改为按全局唯一的 {@code sid}（{@code uk_sid}）寻址后，
 * 租户一致性由 {@code TenantAssertion.assertMatch} 在 Java 侧断言，语义明确且不依赖调用顺序。
 *
 * <p>所有吊销都是<b>置 {@code revoked_at}</b> 而非删行：会话行同时是登录审计的证据，
 * 删除会让"何时被谁强制下线"无从追溯。过期行由清理任务按 {@code expire_at} 定期物理删除。
 */
public interface LoginSessionMapper extends BaseMapper<LoginSession> {

    /** 按会话 id 取会话（{@code JwtAuthFilter} 的吊销校验热路径）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM login_session WHERE sid = #{sid} LIMIT 1")
    LoginSession selectBySid(@Param("sid") String sid);

    /** 按令牌哈希取会话：兜底旧令牌（无 {@code sid} claim）的定位路径。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM login_session WHERE token_hash = #{tokenHash} ORDER BY id DESC LIMIT 1")
    LoginSession selectByTokenHash(@Param("tokenHash") String tokenHash);

    /** 某身份的在册会话（多端登录管理页；已吊销与自然过期的不展示）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM login_session
            WHERE tenant_id = #{tenantId} AND uid = #{uid} AND revoked_at IS NULL AND expire_at > NOW()
            ORDER BY id DESC
            """)
    List<LoginSession> selectActiveByUid(@Param("tenantId") Long tenantId, @Param("uid") Long uid);

    /** 吊销单个会话（自助登出/管理员强制下线某一端）。返回受影响行数，0 表示会话不存在或已吊销。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE login_session SET revoked_at = NOW(), revoked_by = #{revokedBy}
            WHERE sid = #{sid} AND revoked_at IS NULL
            """)
    int revokeBySid(@Param("sid") String sid, @Param("revokedBy") Long revokedBy);

    /**
     * 吊销某身份的全部会话（移除成员、停用账号、重置口令后必须调用，否则旧令牌可用至自然过期）。
     *
     * <p>显式带 {@code tenant_id}，避免误吊销同一自然人在<b>其他组织</b>的会话——方案 A 下
     * 一人可在多组织各有 {@code app_user}，吊销范围必须收敛在本组织内。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE login_session SET revoked_at = NOW(), revoked_by = #{revokedBy}
            WHERE tenant_id = #{tenantId} AND uid = #{uid} AND revoked_at IS NULL
            """)
    int revokeAllByUid(@Param("tenantId") Long tenantId, @Param("uid") Long uid, @Param("revokedBy") Long revokedBy);

    /** 吊销某组织全部会话（组织被停用/解散时；平台运营操作，{@code revokedBy} 为运营 uid）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE login_session SET revoked_at = NOW(), revoked_by = #{revokedBy}
            WHERE tenant_id = #{tenantId} AND revoked_at IS NULL
            """)
    int revokeAllByTenant(@Param("tenantId") Long tenantId, @Param("revokedBy") Long revokedBy);

    /**
     * 物理删除已自然过期的会话行（清理任务，分批删以免长事务）。
     *
     * <p>只按 {@code expire_at} 删，不区分是否已吊销：令牌已自然过期后，会话行的吊销证据价值
     * 已由 {@code auth_audit_log} 承担，继续保留只会让本表无限增长。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM login_session WHERE expire_at < #{before} LIMIT #{batchSize}")
    int deleteExpired(@Param("before") LocalDateTime before, @Param("batchSize") int batchSize);
}
