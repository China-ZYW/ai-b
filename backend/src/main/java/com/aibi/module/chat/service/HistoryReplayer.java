package com.aibi.module.chat.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.chart.ChartRecommender;
import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.chart.ChartType;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.Verify;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.history.dto.HistoryDetailVO;
import com.aibi.module.history.dto.HistoryVO;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.service.QueryHistoryService;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 历史回放协作类（T8.3 自 ChatService 抽出）：还原"问数当时"的数据/图表/解读/校验/归因。
 *
 * <p>优先还原本地落库的结果快照（零执行成本）；无快照的老记录重跑已存 SQL 回放
 * （{@link SqlValidator} 复校 + 表级授权拦截 → 适配器只读执行，<b>不耗 LLM、不计配额</b>，
 * 与指标刷新同模式），图表由规则引擎重建并尽量尊重 chartUsed，回放成功后回填快照。
 * 展示名/脱敏按当前语义层与权限重新装配（{@link ResultDecorator}），别名修正后历史也跟着生效。
 */
@Component
public class HistoryReplayer {

    private static final Logger log = LoggerFactory.getLogger(HistoryReplayer.class);

    private final QueryHistoryService historyService;
    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final ChartRecommender chartRecommender;
    private final HistoryWriter historyWriter;
    private final ResultDecorator resultDecorator;
    private final ObjectMapper objectMapper;
    private final long maxRows;
    private final int timeoutSeconds;

    public HistoryReplayer(QueryHistoryService historyService,
                           DataSourceService dataSourceService,
                           DataSourceAdapterRegistry adapterRegistry,
                           SqlValidator sqlValidator,
                           DataMaskGate dataMaskGate,
                           ChartRecommender chartRecommender,
                           HistoryWriter historyWriter,
                           ResultDecorator resultDecorator,
                           ObjectMapper objectMapper,
                           @Value("${aibi.query.max-rows:10000}") long maxRows,
                           @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.historyService = historyService;
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.dataMaskGate = dataMaskGate;
        this.chartRecommender = chartRecommender;
        this.historyWriter = historyWriter;
        this.resultDecorator = resultDecorator;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    public HistoryDetailVO detail(Long uid, Long id) {
        QueryHistory h = historyService.findOwned(uid, id);
        if (h == null) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "历史记录不存在或无权访问");
        }
        HistoryVO vo = historyService.getHistory(uid, id);
        // 解读/校验是"问数当时"的结论，直接从落库 JSON 还原（不重跑 LLM）；即便下方结果回放失败也照常带回
        Insight insight = readInsight(h.getInsightJson());
        Verify verify = readVerify(h.getVerifyJson());
        Object attribution = readAttribution(h.getAttributionJson());
        if (!"OK".equals(h.getStatus()) || h.getSqlText() == null || h.getSqlText().isBlank()) {
            return new HistoryDetailVO(vo, null, null, insight, verify, attribution);
        }
        // ① 快照命中：原样还原（零执行成本）；展示名按当前语义层重新装配，别名修正后历史也跟着生效
        if (h.getResultJson() != null && !h.getResultJson().isBlank()) {
            QueryResult snap = readSnapshot(h);
            if (snap != null) {
                return new HistoryDetailVO(vo, resultDecorator.decorate(snap, h.getDatasourceId()),
                        readChart(h.getChartJson()), insight, verify, attribution);
            }
        }
        // ② 老记录无快照：重跑已存 SQL 回放
        try {
            DataSourceConfig config = dataSourceService.loadConfig(h.getDatasourceId());
            DataSourceAdapter adapter = adapterRegistry.get(config.type());
            String safeSql = sqlValidator.validate(h.getSqlText());
            // 表级授权（v2）：回放历史 SQL 也需拦截，防历史记录里残留指向隐藏表的查询
            dataMaskGate.assertTablesAllowed(h.getDatasourceId(), safeSql);
            QueryResult executed = adapter.execute(config, safeSql, maxRows, timeoutSeconds);
            // 与快照同上限裁剪：保证"首次回放"与"后续快照命中"返回行数一致（rowCount 保留真实总数）
            List<List<Object>> rs = executed.rows() == null ? List.of() : executed.rows();
            QueryResult result = rs.size() > HistoryWriter.snapshotMaxRows()
                    ? QueryResult.of(executed.sql(), executed.columns(), rs.subList(0, HistoryWriter.snapshotMaxRows()),
                            executed.rowCount(), executed.executionTimeMs())
                    : executed;
            ChartSpec chart = rebuildChart(result, h);
            try {
                historyService.updateSnapshot(h.getId(), historyWriter.snapshotJson(result), historyWriter.chartJson(chart));
            } catch (Exception e) {
                log.warn("历史[{}] 快照回填失败（不影响本次返回）：{}", id, e.getMessage());
            }
            return new HistoryDetailVO(vo, resultDecorator.decorate(result, h.getDatasourceId()), chart, insight, verify, attribution);
        } catch (RuntimeException e) {
            log.warn("历史[{}] 回放失败（数据源可能已删除/变更）：{}", id, e.getMessage());
            return new HistoryDetailVO(vo, null, null, insight, verify, attribution);
        }
    }

    /**
     * 归因结果（{@code AttributionVO}）从落库 JSON 还原——与解读/校验同口径：**只还原不重算**。
     * 用 {@code Object.class} 反序列化为通用 JSON 结构透传前端：归因 VO 归属 attribution 模块，
     * 让 chat 模块去依赖它的类型会形成不必要的模块耦合（前端消费的是同一份 JSON 形状）。
     */
    private Object readAttribution(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("归因结果解析失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** 落库 JSON → AI 解读；空/解析失败回 null（老记录无此字段，回放时不展示）。 */
    private Insight readInsight(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Insight.class);
        } catch (Exception e) {
            log.warn("AI 解读解析失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** 落库 JSON → 质量校验；空/解析失败回 null。 */
    private Verify readVerify(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Verify.class);
        } catch (Exception e) {
            log.warn("质量校验解析失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    private QueryResult readSnapshot(QueryHistory h) {
        try {
            JsonNode node = objectMapper.readTree(h.getResultJson());
            List<String> columns = objectMapper.convertValue(node.path("columns"), new TypeReference<>() {});
            List<List<Object>> rows = objectMapper.convertValue(node.path("rows"), new TypeReference<>() {});
            if (columns == null) {
                return null;
            }
            return QueryResult.of(h.getSqlText(), columns, rows == null ? List.of() : rows,
                    h.getRowCount() == null ? 0 : h.getRowCount(),
                    h.getElapsedMs() == null ? 0L : h.getElapsedMs());
        } catch (Exception e) {
            log.warn("历史[{}] 快照解析失败（回落重跑）：{}", h.getId(), e.getMessage());
            return null;
        }
    }

    private ChartSpec readChart(String chartJson) {
        if (chartJson == null || chartJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(chartJson, ChartSpec.class);
        } catch (Exception e) {
            log.warn("图表规格解析失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** 回放图表重建：规则引擎（无 AI）；chartUsed 为可成图类型且结构允许时尊重原图型。 */
    private ChartSpec rebuildChart(QueryResult result, QueryHistory h) {
        ChartSpec spec = chartRecommender.recommendNoAi(result, h.getQuestion(), h.getDatasourceId());
        if (h.getChartUsed() == null || h.getChartUsed().isBlank()) {
            return spec;
        }
        try {
            ChartType used = ChartType.valueOf(h.getChartUsed().trim().toUpperCase(Locale.ROOT));
            if (used == ChartType.TABLE) {
                return ChartSpec.table(spec.reason());
            }
            if (spec.type() != used && spec.xField() != null) {
                // withType 而非重建：保留推荐装配的展示样式（度量单位等），仅换图型
                return spec.withType(used);
            }
            return spec;
        } catch (IllegalArgumentException e) {
            return spec;   // 未知图型留痕，保持规则引擎结果
        }
    }
}
