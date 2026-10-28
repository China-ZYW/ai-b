package com.aibi.module.history.mapper;

import com.aibi.module.history.dto.ConversationVO;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.common.security.RowScope;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问数历史 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>{@code query_history} 不在租户拦截器 IGNORE 名单，通用 CRUD 会自动追加 {@code tenant_id}；
 * 唯一例外是 {@link #selectConversations} —— 它含 JOIN + 子查询聚合，为避免拦截器改写复杂 SQL 的不确定性，
 * 用 {@link InterceptorIgnore} 关闭自动注入并在 SQL 内显式携带 {@code tenant_id}。
 *
 * <p><b>行级范围</b>：类级 {@link RowScope} 使本 Mapper 全部动作在 {@code SELF} 数据范围下追加
 * {@code uid = 当前uid}（由 {@code AibiDataScopeHandler} 执行）。{@link #selectConversations} 已自行在
 * SQL 内按 {@code uid} 收敛，故用 {@code dataPermission="true"} 关闭再次追加，避免重复条件。
 */
@RowScope(uidColumn = "uid")
public interface QueryHistoryMapper extends BaseMapper<QueryHistory> {

    /**
     * 会话摘要列表：每个 {@code conversation_id} 取最近一条问答作为标题，并统计其问答条数。
     *
     * <p>关闭租户自动注入，{@code tenant_id} 由参数显式传入（值取自当前租户上下文）；
     * 同时关闭行级 DataPermission（已自行按 {@code uid} 收敛），避免重复条件。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT h.conversation_id, h.datasource_id, h.question AS last_question, \
            c.cnt AS message_count, h.created_at AS updated_at
            FROM query_history h
            JOIN (SELECT conversation_id, MAX(id) AS mid, COUNT(*) AS cnt
                  FROM query_history
                  WHERE tenant_id = #{tenantId} AND uid = #{uid} AND conversation_id IS NOT NULL
                  GROUP BY conversation_id) c ON h.id = c.mid
            ORDER BY h.id DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "datasource_id", javaType = Long.class),
            @Arg(column = "last_question", javaType = String.class),
            @Arg(column = "message_count", javaType = Integer.class),
            @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    List<ConversationVO> selectConversations(@Param("tenantId") Long tenantId,
                                             @Param("uid") Long uid,
                                             @Param("limit") int limit);

    /**
     * 问题沉淀（T11-1）取数：拉取本租户时间窗内的历史行，供 {@code IssueDistiller} 内存聚合。
     *
     * <p><b>为什么跨用户</b>：普通历史是「我的历史」（类级 {@code @RowScope} 自动追加 {@code uid=本人}）；
     * 而问题沉淀是给<b>数据管理员</b>看全租户「哪些问法反复出错」的治理视图，必须聚合本租户所有人的问句。
     * 故与 {@link #selectConversations} 同理关闭 tenant 自动注入与 uid 行级范围，{@code tenant_id} 由参数显式携带，
     * 严格不跨租户。
     *
     * <p><b>只选聚合需要的列</b>：刻意不取 {@code result_json}/{@code chart_json}/{@code insight_json}/
     * {@code attribution_json} 等大文本，降低大表读取压力（§11 性能护栏）；{@code datasourceId} 为空表示不限数据源。
     * <p>二期（T11-1 写侧闭环）额外取 {@code sql_text}：供待治理清单选代表 SQL 草稿、人工修正后转写指标/少样本。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id, tenant_id, datasource_id, conversation_id, question, status,
                   error_msg, verify_json, feedback, skill, row_count, elapsed_ms, sql_text,
                   expected_sql, feedback_note, feedback_reason, created_at
            FROM query_history
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{since}
              AND (#{datasourceId} IS NULL OR datasource_id = #{datasourceId})
            ORDER BY created_at DESC
            LIMIT #{cap}
            """)
    List<QueryHistory> selectForDistill(@Param("tenantId") Long tenantId,
                                        @Param("datasourceId") Long datasourceId,
                                        @Param("since") LocalDateTime since,
                                        @Param("cap") int cap);

    /**
     * 审计 NL 查询（T11-3）跨用户投影检索：管理员视角的「谁在何时问了什么」。
     *
     * <p><b>与 {@link #selectForDistill} 同源的安全口径</b>：关闭租户自动注入与行级 uid 范围，
     * {@code tenant_id} 由参数显式携带（组域钉死当前租户；平台域传 null 跨租户），严格不越界。
     *
     * <p><b>显式列名禁 ∗</b>：只取审计投影所需列；{@code result_json} 仅在服务端解析成
     * 行数/列名/首行示例的<b>摘要</b>，原始快照内容绝不下发前端（拍板 P2 的敏感面收敛）。
     * 条件均可空（空则不约束），时间倒序、{@code cap} 硬性截断防大表拖垮。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            <script>
              SELECT id, tenant_id, uid, question, status, skill, row_count, elapsed_ms,
                     result_json, created_at
              FROM query_history
              <where>
                <if test="tenantId != null"> AND tenant_id = #{tenantId} </if>
                <if test="from != null"> AND created_at &gt;= #{from} </if>
                <if test="to != null"> AND created_at &lt;= #{to} </if>
                <if test="keyword != null and keyword != ''"> AND question LIKE CONCAT('%', #{keyword}, '%') </if>
                <if test="uids != null and uids.size() > 0">
                  AND uid IN <foreach collection="uids" item="u" open="(" separator="," close=")">#{u}</foreach>
                </if>
              </where>
              ORDER BY id DESC
              LIMIT #{cap}
            </script>
            """)
    List<QueryHistory> selectAuditNlProjection(@Param("tenantId") Long tenantId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               @Param("keyword") String keyword,
                                               @Param("uids") List<Long> uids,
                                               @Param("cap") int cap);
}
