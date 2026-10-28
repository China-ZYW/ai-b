package com.aibi.module.history.service.impl;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.history.dto.ConversationVO;
import com.aibi.module.history.dto.FeedbackCorrection;
import com.aibi.module.history.dto.HistoryVO;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.aibi.module.history.service.QueryHistoryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询历史服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p>{@code tenant_id} 由 {@code TenantLineInnerInterceptor} 自动注入，故业务方法不再显式传租户；
 * 仅 {@link #listConversations} 因走关闭注入的自定义聚合 SQL，需在实现内从 {@link TenantContext} 取租户显式传入。
 */
@Service
public class QueryHistoryServiceImpl extends ServiceImpl<QueryHistoryMapper, QueryHistory>
        implements QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryServiceImpl.class);

    /** 单次列表默认/最大条数，避免一次拉取过多。 */
    private static final int MAX_PAGE_SIZE = 100;

    @Override
    public Long saveHistory(Long uid, Long datasourceId, String conversationId,
                            String question, String sql, Integer rowCount, Long elapsedMs, String status,
                            String chartRequested, String chartUsed, String errorMsg,
                            String resultJson, String chartJson, String insightJson, String verifyJson, String skill) {
        QueryHistory entity = new QueryHistory();
        entity.setUid(uid);
        entity.setDatasourceId(datasourceId);
        entity.setConversationId(conversationId);
        entity.setQuestion(question);
        entity.setSqlText(sql);
        entity.setRowCount(rowCount == null ? 0 : rowCount);
        entity.setElapsedMs(elapsedMs == null ? 0L : elapsedMs);
        entity.setStatus(status);
        entity.setChartRequested(chartRequested);
        entity.setChartUsed(chartUsed);
        entity.setErrorMsg(errorMsg == null ? null
                : errorMsg.substring(0, Math.min(errorMsg.length(), 500)));
        entity.setResultJson(resultJson);
        entity.setChartJson(chartJson);
        entity.setInsightJson(insightJson);
        entity.setVerifyJson(verifyJson);
        entity.setSkill(skill);
        save(entity);   // 租户拦截器自动补 tenant_id；IdType.AUTO 回填自增主键
        return entity.getId();
    }

    @Override
    public void feedback(Long uid, Long id, int vote, FeedbackCorrection correction) {
        if (vote != 1 && vote != -1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "反馈值仅支持 1（赞）或 -1（踩）");
        }
        QueryHistory entity = lambdaQuery()
                .eq(QueryHistory::getId, id)
                .eq(QueryHistory::getUid, uid)
                .one();
        if (entity == null) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "历史记录不存在或无权访问");
        }
        entity.setFeedback(vote);
        entity.setFeedbackAt(java.time.LocalDateTime.now());
        // T11-2 反馈修正采集：仅点踩（vote==-1）且携带实质建议时落三列；点赞/无建议则不写（保留旧行为）。
        if (vote == -1 && correction != null && correction.hasContent()) {
            entity.setExpectedSql(trim(correction.expectedSql(), 60000));
            entity.setFeedbackNote(trim(correction.note(), 500));
            entity.setFeedbackReason(trim(correction.reason(), 32));
        }
        updateById(entity);
    }

    /** 去首尾空白；空串归 null（避免将空白建议当作有内容落库），超长截断。 */
    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    @Override
    public List<HistoryVO> listHistory(Long uid, Long datasourceId, int limit, int offset) {
        int size = clampSize(limit);
        int off = Math.max(0, offset);
        List<QueryHistory> rows = lambdaQuery()
                .eq(QueryHistory::getUid, uid)
                .eq(datasourceId != null, QueryHistory::getDatasourceId, datasourceId)
                .orderByDesc(QueryHistory::getId)
                .last("LIMIT " + size + " OFFSET " + off)
                .list();
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public long countHistory(Long uid, Long datasourceId) {
        Long total = lambdaQuery()
                .eq(QueryHistory::getUid, uid)
                .eq(datasourceId != null, QueryHistory::getDatasourceId, datasourceId)
                .count();
        return total == null ? 0L : total;
    }

    @Override
    public List<ConversationTurn> recentTurns(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            List<QueryHistory> desc = lambdaQuery()
                    .eq(QueryHistory::getConversationId, conversationId)
                    .eq(QueryHistory::getStatus, "OK")
                    .isNotNull(QueryHistory::getSqlText)
                    .orderByDesc(QueryHistory::getId)
                    .last("LIMIT " + Math.min(limit, MAX_PAGE_SIZE))
                    .list();
            List<ConversationTurn> asc = new ArrayList<>(desc.size());
            for (QueryHistory h : desc) {
                asc.add(new ConversationTurn(h.getQuestion(), h.getSqlText()));
            }
            Collections.reverse(asc);   // 转为时间正序，符合对话先后
            return asc;
        } catch (Exception e) {
            log.debug("多轮上下文读取跳过：{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public HistoryVO getHistory(Long uid, Long id) {
        QueryHistory entity = findOwned(uid, id);
        if (entity == null) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "历史记录不存在或无权访问");
        }
        return toVO(entity);
    }

    @Override
    public QueryHistory findOwned(Long uid, Long id) {
        return lambdaQuery()
                .eq(QueryHistory::getId, id)
                .eq(QueryHistory::getUid, uid)
                .one();
    }

    @Override
    public void updateSnapshot(Long id, String resultJson, String chartJson) {
        update(Wrappers.<QueryHistory>lambdaUpdate()
                .eq(QueryHistory::getId, id)
                .set(QueryHistory::getResultJson, resultJson)
                .set(QueryHistory::getChartJson, chartJson));
    }

    @Override
    public void deleteHistory(Long uid, Long id) {
        boolean removed = remove(Wrappers.<QueryHistory>lambdaQuery()
                .eq(QueryHistory::getId, id)
                .eq(QueryHistory::getUid, uid));
        if (!removed) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "历史记录不存在或无权访问");
        }
    }

    @Override
    public List<ConversationVO> listConversations(Long uid, int limit) {
        Long tenantId = TenantContext.requireTenantId();   // 自定义聚合 SQL 关闭了自动注入，需显式传入
        return baseMapper.selectConversations(tenantId, uid, clampSize(limit));
    }

    private int clampSize(int limit) {
        return Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
    }

    private HistoryVO toVO(QueryHistory e) {
        return new HistoryVO(e.getId(), e.getConversationId(), e.getDatasourceId(), e.getQuestion(),
                e.getSqlText(), e.getRowCount(), e.getElapsedMs(), e.getStatus(), e.getCreatedAt(),
                e.getFeedback(), e.getChartRequested(), e.getChartUsed());
    }
}
