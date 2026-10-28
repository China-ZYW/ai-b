package com.aibi.module.audit.mapper;

import com.aibi.module.audit.entity.OperationLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全局操作日志 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>与 {@link AuthAuditLogMapper} 同样：全部方法显式 {@code @InterceptorIgnore(tenantLine="true")}，
 * 因为 {@code audit_log} 跨租户、查询范围由检索接口按身份收敛，不能被自动注入 {@code tenant_id} 误导。
 *
 * <p>分页沿用全仓约定（未装配 {@code PaginationInnerInterceptor}）：手写 {@code LIMIT/OFFSET} + 独立 {@code count}。
 */
@InterceptorIgnore(tenantLine = "true")
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 插入一条操作日志（{@code id} 自增、{@code created_at} 由代码显式写入当前时刻）。
     *
     * <p>列清单与实体字段一一对应；由 {@code OperationLogInterceptor} 在请求线程捕获上下文、
     * 经 {@code auditExecutor} 异步落库，失败只告警不阻断主流程。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO audit_log
              (tenant_id, user_id, username, org_roles, platform_roles, request_id, module,
               action, method, uri, query_string, request_body, ip, user_agent, http_status, error_msg, duration_ms)
            VALUES
              (#{tenantId}, #{userId}, #{username}, #{orgRoles}, #{platformRoles}, #{requestId}, #{module},
               #{action}, #{method}, #{uri}, #{queryString}, #{requestBody}, #{ip}, #{userAgent}, #{httpStatus}, #{errorMsg}, #{durationMs})
            """)
    int insertLog(OperationLog log);

    /**
     * 条件检索（时间倒序 + LIMIT/OFFSET 分页）。过滤条件均可空，空则不约束。
     *
     * @param module   业务模块（org:metrics / chat / admin:audit ...），精确匹配
     * @param method   HTTP 方法，精确匹配
     * @param uri      路径模糊匹配
     * @param username 操作人登录名模糊匹配
     * @param from     起始时间（含）
     * @param to       截止时间（含）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
              SELECT * FROM audit_log
              <where>
                <if test="tenantId != null"> AND tenant_id = #{tenantId} </if>
                <if test="module != null and module != ''"> AND module = #{module} </if>
                <if test="method != null and method != ''"> AND method = #{method} </if>
                <if test="uri != null and uri != ''"> AND uri LIKE CONCAT('%', #{uri}, '%') </if>
                <if test="username != null and username != ''"> AND username LIKE CONCAT('%', #{username}, '%') </if>
                <if test="from != null"> AND created_at &gt;= #{from} </if>
                <if test="to != null"> AND created_at &lt;= #{to} </if>
              </where>
              ORDER BY id DESC
              LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OperationLog> search(@Param("tenantId") Long tenantId,
                              @Param("module") String module,
                              @Param("method") String method,
                              @Param("uri") String uri,
                              @Param("username") String username,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    /** 与 {@link #search} 同条件的总数（分页用）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
              SELECT COUNT(*) FROM audit_log
              <where>
                <if test="tenantId != null"> AND tenant_id = #{tenantId} </if>
                <if test="module != null and module != ''"> AND module = #{module} </if>
                <if test="method != null and method != ''"> AND method = #{method} </if>
                <if test="uri != null and uri != ''"> AND uri LIKE CONCAT('%', #{uri}, '%') </if>
                <if test="username != null and username != ''"> AND username LIKE CONCAT('%', #{username}, '%') </if>
                <if test="from != null"> AND created_at &gt;= #{from} </if>
                <if test="to != null"> AND created_at &lt;= #{to} </if>
              </where>
            </script>
            """)
    long count(@Param("tenantId") Long tenantId,
               @Param("module") String module,
               @Param("method") String method,
               @Param("uri") String uri,
               @Param("username") String username,
               @Param("from") LocalDateTime from,
               @Param("to") LocalDateTime to);
}
