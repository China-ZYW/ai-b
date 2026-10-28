package com.aibi.module.audit.mapper;

import com.aibi.module.audit.entity.AuthAuditLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code auth_audit_log} 带 {@code tenant_id} 且<b>不在</b>租户拦截器 IGNORE 名单，但本 Mapper 全部
 * 方法一律用 {@code @InterceptorIgnore(tenantLine="true")} 关闭自动注入并显式处理租户，原因有二：
 * <ol>
 *   <li><b>写入</b>：事件的 {@code tenant_id} 是「受影响租户」而非「当前上下文租户」——平台运营停用租户 X 时，
 *       上下文租户是 0，但事件应归属 X（便于 X 的组织内审计检索），故必须显式写入、不能被自动注入覆盖；</li>
 *   <li><b>读取</b>：平台后台审计页（{@code admin:audit:read}）要<b>跨租户</b>检索，自动注入会把结果错误地
 *       收敛到运营者自己的 {@code tenant_id=0}。</li>
 * </ol>
 * 与设计文档 DDL 注释「平台域查询走 @InterceptorIgnore + 显式条件」一致（同 {@code LoginSessionMapper} 的处理）。
 */
public interface AuthAuditLogMapper extends BaseMapper<AuthAuditLog> {

    /**
     * 插入一条审计（{@code created_at} 走 DB 默认 {@code CURRENT_TIMESTAMP}）。
     *
     * <p>显式列出全部列并携带 {@code tenant_id}，与自动注入无关；由 {@code AuditService} 在业务事务
     * 提交后于审计线程池调用，失败只告警不阻断主流程。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO auth_audit_log
              (tenant_id, uid, identity_id, actor_type, action, resource_type, resource_id,
               target_uid, result, detail, ip, user_agent, trace_id)
            VALUES
              (#{tenantId}, #{uid}, #{identityId}, #{actorType}, #{action}, #{resourceType}, #{resourceId},
               #{targetUid}, #{result}, #{detail}, #{ip}, #{userAgent}, #{traceId})
            """)
    int insertLog(AuthAuditLog log);

    /**
     * 跨租户条件检索（按时间倒序 + LIMIT/OFFSET 分页）。所有过滤条件均可空，空则不约束。
     *
     * <p>项目未装配 {@code PaginationInnerInterceptor}，故分页沿用全仓约定：手写 {@code LIMIT/OFFSET}
     * （见 {@code FewShotStore} 的 {@code .last("LIMIT n")}），配合 {@link #count} 得总数。
     *
     * @param from 起始时间（含），{@code created_at >= from}
     * @param to   截止时间（含），{@code created_at <= to}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
              SELECT * FROM auth_audit_log
              <where>
                <if test="tenantId != null"> AND tenant_id = #{tenantId} </if>
                <if test="action != null and action != ''"> AND action = #{action} </if>
                <if test="uid != null"> AND uid = #{uid} </if>
                <if test="result != null and result != ''"> AND result = #{result} </if>
                <if test="from != null"> AND created_at &gt;= #{from} </if>
                <if test="to != null"> AND created_at &lt;= #{to} </if>
              </where>
              ORDER BY id DESC
              LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuthAuditLog> search(@Param("tenantId") Long tenantId,
                              @Param("action") String action,
                              @Param("uid") Long uid,
                              @Param("result") String result,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    /** 与 {@link #search} 同条件的总数（分页用）；条件为空则统计全表。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
              SELECT COUNT(*) FROM auth_audit_log
              <where>
                <if test="tenantId != null"> AND tenant_id = #{tenantId} </if>
                <if test="action != null and action != ''"> AND action = #{action} </if>
                <if test="uid != null"> AND uid = #{uid} </if>
                <if test="result != null and result != ''"> AND result = #{result} </if>
                <if test="from != null"> AND created_at &gt;= #{from} </if>
                <if test="to != null"> AND created_at &lt;= #{to} </if>
              </where>
            </script>
            """)
    long count(@Param("tenantId") Long tenantId,
               @Param("action") String action,
               @Param("uid") Long uid,
               @Param("result") String result,
               @Param("from") LocalDateTime from,
               @Param("to") LocalDateTime to);
}
