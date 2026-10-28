package com.aibi.module.history.service;

import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.history.dto.ConversationVO;
import com.aibi.module.history.dto.FeedbackCorrection;
import com.aibi.module.history.dto.HistoryVO;
import com.aibi.module.history.entity.QueryHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 查询历史服务（M4 / 开发计划 T4.4）：问数结果落库、历史检索、多轮上下文提取。四层架构之业务层接口。
 *
 * <p>继承 {@link IService} 获得 MyBatis-Plus 通用 CRUD；下列为业务方法。
 * <b>隔离</b>：{@code tenant_id} 由租户拦截器自动注入，{@code uid} 由参数显式携带；
 * 删除/详情仅命中本租户本人的记录。
 */
public interface QueryHistoryService extends IService<QueryHistory> {

    /**
     * 落库一条问数历史，返回自增 id。
     *
     * @param status         OK / FAILED
     * @param chartRequested 问题中显式指定的图表类型（可空）
     * @param chartUsed      最终推荐/使用的图表类型（可空）
     * @param errorMsg       失败原因（可空，截断入库）
     * @param resultJson     结果快照 JSON（columns+前若干行，可空），供历史会话回放还原数据
     * @param chartJson      图表规格 JSON（可空），供历史会话回放还原图表
     * @param insightJson    AI 数据解读 JSON（可空），供历史回放还原解读
     * @param verifyJson     AI 质量校验 JSON（可空），供历史回放还原校验结论
     */
    Long saveHistory(Long uid, Long datasourceId, String conversationId,
                     String question, String sql, Integer rowCount, Long elapsedMs, String status,
                     String chartRequested, String chartUsed, String errorMsg,
                     String resultJson, String chartJson, String insightJson, String verifyJson, String skill);

    /**
     * 用户对某条历史的反馈（1=赞 / -1=踩）；赞且 OK 的记录进入少样本晋升池（反馈闭环）。
     *
     * <p>无修正建议的便捷入口（纯点踩/点赞），委派 {@link #feedback(Long, Long, int, FeedbackCorrection)} 传 null。
     */
    default void feedback(Long uid, Long id, int vote) {
        feedback(uid, id, vote, null);
    }

    /**
     * 带反馈修正采集的反馈（T11-2）。{@code vote==1}（赞）忽略 {@code correction}（赞不带建议）；
     * {@code vote==-1}（踩）且 {@code correction.hasContent()} 时，将期望 SQL/口径说明/错因落
     * {@code query_history} 附列，供治理侧 THUMBS_DOWN 行作转写预填草稿（绝不自动进少样本/指标）。
     *
     * @param correction 可空的修正建议；null 或无实质内容则等价纯点踩
     */
    void feedback(Long uid, Long id, int vote, FeedbackCorrection correction);

    /** 分页列出某用户（本租户内）的问数历史，按时间倒序。datasourceId 为空表示不限数据源。 */
    List<HistoryVO> listHistory(Long uid, Long datasourceId, int limit, int offset);

    /** 统计某用户（本租户内）的历史条数，用于分页总数。 */
    long countHistory(Long uid, Long datasourceId);

    /**
     * 取某会话最近若干轮"成功问答"，按时间<b>正序</b>返回，供多轮对话上下文注入 Prompt。
     *
     * @param limit 期望轮数；返回不超过该数量的最近轮次
     */
    List<ConversationTurn> recentTurns(String conversationId, int limit);

    /** 取单条历史（仅限本租户本人）。 */
    HistoryVO getHistory(Long uid, Long id);

    /** 取单条历史实体（仅限本租户本人）；不存在返回 null，供回放等需要快照字段的场景。 */
    QueryHistory findOwned(Long uid, Long id);

    /** 回填结果快照/图表规格（老记录回放成功后调用，最佳努力）。 */
    void updateSnapshot(Long id, String resultJson, String chartJson);

    /** 删除一条历史（仅限本租户本人）。 */
    void deleteHistory(Long uid, Long id);

    /** 列出某用户（本租户内）的会话摘要，按最近更新倒序，用于前端对话列表。 */
    List<ConversationVO> listConversations(Long uid, int limit);
}
