package com.aibi.module.chat.service;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.Verify;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.history.service.QueryHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 问数历史落库协作类（T8.3 自 ChatService 抽出，对应编排第⑧步与失败留痕）。
 *
 * <p>落库内容 = 问句 + SQL + 结果快照 + 图表规格 + AI 解读 + 质量校验 + 技能编码，
 * 历史会话点击据此完整还原"问数当时"；<b>失败不影响返回</b>（成功路径 try/catch 后返回
 * null historyId，失败路径同样吞掉——留痕是副作用，不是主链路的一部分）。
 */
@Component
public class HistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoryWriter.class);

    /** 结果快照行数上限：历史回放还原用（快照与回放返回同上限，rowCount 保留真实总数）。 */
    private static final int SNAPSHOT_MAX_ROWS = 1000;

    private final QueryHistoryService historyService;
    private final ObjectMapper objectMapper;

    public HistoryWriter(QueryHistoryService historyService, ObjectMapper objectMapper) {
        this.historyService = historyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 成功落库（status=OK）：供审计、少样本与多轮上下文自我增强；同存结果快照 + 图表规格
     * + AI 解读 + 质量校验。刻意排在解读之后调用，使 insight/verify 能与结果一并存库。
     *
     * @return 历史记录 id；落库失败返回 null（不影响本次问数返回）
     */
    public Long writeOk(long uid, Long datasourceId, String convId, String question,
                        QueryResult result, String chartRequested, ChartSpec chart,
                        Insight insight, Verify verify, String skillCode) {
        try {
            return historyService.saveHistory(uid, datasourceId, convId, question,
                    result.sql(), result.rowCount(), result.executionTimeMs(), "OK",
                    chartRequested, chart.type().name(), null,
                    snapshotJson(result), chartJson(chart), insightJson(insight), verifyJson(verify),
                    skillCode);
        } catch (Exception e) {
            log.warn("历史落库失败（不影响返回）：{}", e.getMessage());
            return null;
        }
    }

    /** 失败留痕（status=FAILED，error_msg 记失败原因），落库本身再失败也只跳过、不影响异常上抛。 */
    public void writeFailed(long uid, Long datasourceId, String convId, String question,
                            long elapsedMs, String errorMsg, String skillCode) {
        try {
            historyService.saveHistory(uid, datasourceId, convId, question, null, 0,
                    elapsedMs, "FAILED", null, null, errorMsg,
                    null, null, null, null, skillCode);
        } catch (Exception ignore) {
            log.warn("失败历史落库跳过：{}", ignore.getMessage());
        }
    }

    /**
     * 口径歧义澄清留痕（T10-2，status=CLARIFY）：本次提问被判定存在口径歧义、已返回澄清候选但未执行。
     *
     * <p>不存 SQL / 结果快照（未执行）、不记 error；仅把歧义事实写入历史，供审计与 T11-1 治理侧按
     * {@code status=CLARIFY} 聚合"哪些问法反复触发反问"。落库失败返回 {@code null}，不影响本次澄清返回。
     */
    public Long writeClarify(long uid, Long datasourceId, String convId, String question,
                             com.aibi.module.chat.dto.ClarifyProposal proposal) {
        try {
            // 把歧义成因轻量记入 error_msg 列（"CLARIFY:<reason>"），供治理侧区分两类歧义，无需新增列
            String reason = proposal == null ? null : "CLARIFY:" + proposal.reason();
            return historyService.saveHistory(uid, datasourceId, convId, question, null, 0,
                    0L, "CLARIFY", null, null, reason,
                    null, null, null, null, null);
        } catch (Exception e) {
            log.warn("澄清历史落库失败（不影响返回）：{}", e.getMessage());
            return null;
        }
    }

    /** 结果快照 JSON：columns + 前 {@value #SNAPSHOT_MAX_ROWS} 行。序列化失败降级 null（跳过快照）。 */
    public String snapshotJson(QueryResult result) {
        try {
            List<List<Object>> rows = result.rows() == null ? List.of()
                    : result.rows().subList(0, Math.min(result.rows().size(), SNAPSHOT_MAX_ROWS));
            return objectMapper.writeValueAsString(Map.of("columns", result.columns(), "rows", rows));
        } catch (Exception e) {
            log.warn("结果快照序列化失败（跳过快照）：{}", e.getMessage());
            return null;
        }
    }

    /** 快照行数上限（回放裁剪与落库共用同一常量，保证两侧行数一致）。 */
    public static int snapshotMaxRows() {
        return SNAPSHOT_MAX_ROWS;
    }

    public String chartJson(ChartSpec chart) {
        try {
            return objectMapper.writeValueAsString(chart);
        } catch (Exception e) {
            log.warn("图表规格序列化失败（跳过）：{}", e.getMessage());
            return null;
        }
    }

    /** AI 解读 → JSON（供回放）；null 或序列化失败一律回 null，绝不因解读落库拖垮主链路。 */
    public String insightJson(Insight insight) {
        if (insight == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(insight);
        } catch (Exception e) {
            log.warn("AI 解读序列化失败（跳过）：{}", e.getMessage());
            return null;
        }
    }

    /** 质量校验 → JSON（供回放）；同 {@link #insightJson}，失败降级为 null。 */
    public String verifyJson(Verify verify) {
        if (verify == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(verify);
        } catch (Exception e) {
            log.warn("质量校验序列化失败（跳过）：{}", e.getMessage());
            return null;
        }
    }
}
