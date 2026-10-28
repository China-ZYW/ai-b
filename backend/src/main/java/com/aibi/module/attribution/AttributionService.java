package com.aibi.module.attribution;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.attribution.dto.AttributionOptionVO;
import com.aibi.module.attribution.dto.AttributionReq;
import com.aibi.module.attribution.dto.AttributionTrendPoint;
import com.aibi.module.attribution.dto.AttributionVO;
import com.aibi.module.attribution.dto.ContributionItem;
import com.aibi.module.attribution.dto.FactorItem;
import com.aibi.module.attribution.quality.AttributionContext;
import com.aibi.module.attribution.quality.AttributionQualityRule;
import com.aibi.module.attribution.quality.RuleIssue;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.config.service.SqlVisibility;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.dashboard.service.MetricDefinitionNormalizer;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.chat.nl2sql.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 归因服务：回答"指标为什么涨/跌"，产出【趋势 + 量价因子 + 单维度归因】三份可独立审计的结果。
 *
 * <p><b>数值边界（需求 §6 硬性约束）</b>：所有数字由 SQL 聚合 + 本类算术得出，
 * LLM 至多基于已算好的结构化结果生成文案（{@code insight} 字段），
 * 绝不参与任何数值计算——这是"杜绝 AI 幻觉"在归因场景的落地形式。
 *
 * <p><b>为什么不做成 {@code AskSkill}</b>：技能契约 {@code SkillPlan} 的 runner 只能返回<b>单一</b>
 * {@code QueryResult}，而归因必须同时给出趋势、量价、维度贡献三份结构与语义都不同的产物，
 * 且需要把三套 SQL 分别透出供审计查看。硬塞进技能契约会让三份结果挤在一张表里、无法单独复算。
 * 故归因走独立服务与接口（{@code POST /api/attribution/run}），由前端快照页在识别到波动类问题时调用。
 *
 * <p><b>安全</b>：SQL 由 {@link AttributionSqlBuilder} 生成（含标识符/表达式白名单），
 * 再经 {@link SqlValidator} 复校，最后由数据源适配器<b>只读</b>执行并受超时与行数上限约束。
 */
@Service
public class AttributionService {

    private static final Logger log = LoggerFactory.getLogger(AttributionService.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final MetricMapper metricMapper;
    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final AttributionSqlBuilder sqlBuilder;
    private final DimensionResolver dimensionResolver;
    private final AibiConfig aibiConfig;
    private final SqlVisibility sqlVisibility;
    /** 问数历史：归因结果按请求携带的 historyId 回写 attribution_json，供历史回放还原。 */
    private final QueryHistoryMapper historyMapper;
    private final ObjectMapper objectMapper;
    /** 数据权限 A：归因结果出口的字段脱敏（与问数/驾驶舱同一门禁）。 */
    private final DataMaskGate dataMaskGate;
    private final List<AttributionQualityRule> rules;
    private final long maxRows;
    private final int timeoutSeconds;

    /**
     * 时间列精度缓存：{@code 数据源|表|时间列 → 精度}。
     * 精度探测要回查一次业务库，缓存后同一链路只探一次；列的形态极少变更，进程内缓存足够。
     */
    private final ConcurrentHashMap<String, AttributionSqlBuilder.TimePrecision> precisionCache =
            new ConcurrentHashMap<>();

    public AttributionService(MetricMapper metricMapper,
                              DataSourceService dataSourceService,
                              DataSourceAdapterRegistry adapterRegistry,
                              SqlValidator sqlValidator,
                              AttributionSqlBuilder sqlBuilder,
                              DimensionResolver dimensionResolver,
                              AibiConfig aibiConfig,
                              SqlVisibility sqlVisibility,
                              QueryHistoryMapper historyMapper,
                              ObjectMapper objectMapper,
                              List<AttributionQualityRule> rules,
                              @Value("${aibi.query.max-rows:10000}") long maxRows,
                              @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds,
                              DataMaskGate dataMaskGate) {
        this.metricMapper = metricMapper;
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.sqlBuilder = sqlBuilder;
        this.dimensionResolver = dimensionResolver;
        this.aibiConfig = aibiConfig;
        this.sqlVisibility = sqlVisibility;
        this.historyMapper = historyMapper;
        this.objectMapper = objectMapper;
        this.rules = rules == null ? List.of() : rules;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
        this.dataMaskGate = dataMaskGate;
    }

    /**
     * 执行一次归因分析。
     *
     * @param req 归因请求（以指标为中心，口径取自指标 canonical 表达式）
     * @return 归因结果（三份产物 + 三套 SQL + 校验提示）
     * @throws BizException 模块关闭 / 指标缺失 / 口径未沉淀 / 语义层未标记 / 非可加度量 / 执行失败
     */
    public AttributionVO run(AttributionReq req) {
        long started = System.currentTimeMillis();
        if (!aibiConfig.attributionEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "归因模块已关闭，请联系管理员开启");
        }
        if (req == null || req.metricId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指标不能为空");
        }

        Metric metric = requireMetric(req.metricId());
        // P1：请求未显式指定时，回落指标沉淀的归因默认值（保存指标时一并沉淀，下次打开直接生效）
        String baseline = fallback(req.baseline(), fallback(metric.getBaselineType(), req.baselineOrDefault()));
        // 基准只要有一处明确（请求显式指定 或 指标已沉淀）就不算「缺基准」，避免误扣分
        boolean baselineExplicit = (req.baseline() != null && !req.baseline().isBlank())
                || (metric.getBaselineType() != null && !metric.getBaselineType().isBlank());
        String granularity = req.granularityOrDefault();

        // ① canonical 口径：趋势/因子/归因唯一度量来源，杜绝三处口径漂移
        String definition = metric.getDefinition();
        if (definition == null || definition.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "指标「" + nameOf(metric) + "」尚未沉淀口径表达式，无法归因；请先在指标库补全 canonical 口径");
        }
        // 读侧兜底：历史指标可能把口径存成了 SQL 输出别名（如 total_quantity），直接拼进 SQL 会生成
        // SUM(total_quantity) 而业务库没有该列 → Unknown column。据 sql_text 还原为真实表达式。
        definition = MetricDefinitionNormalizer.canonicalize(definition, metric.getSqlText());
        String measureExpr = sqlBuilder.atomicExpr(definition);
        boolean additive = sqlBuilder.isAdditive(measureExpr);

        // ② 可加性硬拦截：非可加度量（含除法 / 聚合函数 / DISTINCT）朴素拆分必然算出错误结论，宁可不做
        if (!additive) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "指标「" + nameOf(metric) + "」口径属非可加度量（含除法 / 聚合函数 / DISTINCT，"
                            + "如毛利率、客单价、去重计数），各维度成员增量相加无业务含义，已阻止归因；"
                            + "请改用可加度量（金额 / 数量 / 行数）或先做量价拆解");
        }

        // ③ 语义层骨架（表/时间列/可用维度，敏感维度已被剔除）
        DimensionResolver.AttributionSchema schema = dimensionResolver.schema(metric.getDatasourceId());
        if (schema == null || schema.timeColumn() == null || schema.dimensions().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "语义层尚未标记时间列或业务维度，无法归因；请在问数调优中心把相关列角色标记为「时间/维度」");
        }
        String dimensionWanted = req.dimension() != null && !req.dimension().isBlank()
                ? req.dimension()
                : metric.getAttributionDimension();
        DimensionResolver.DimensionOption dim = findDimension(schema, dimensionWanted);
        if (dim == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "维度「" + req.dimension() + "」不可用于归因：未标记为维度角色，或属于敏感列（敏感维度禁止归因）");
        }

        DataSourceConfig config = dataSourceService.loadConfig(metric.getDatasourceId());
        DataSourceAdapter adapter = adapterRegistry.get(config.type());

        // ④ 期间：未指定则取数据最新一期；PEAK 需两步（先定位峰值期，再取该期分布）
        String currentPeriod = normalizePeriod(req.period(), granularity);
        if (currentPeriod == null) {
            currentPeriod = queryLatestPeriod(adapter, config, schema, granularity);
        }
        // 期间边界必须按时间列的真实精度渲染：月粒度列（'2026-09'）若用 'yyyy-MM-01' 做边界，
        // 字符串比较会把当月整段排除、把上月误判为基期，产出"本期为 0、下降 100%"的错位结论。
        AttributionSqlBuilder.TimePrecision precision =
                detectTimePrecision(adapter, config, metric.getDatasourceId(), schema);
        AttributionSqlBuilder.PeriodRange current = sqlBuilder.currentRange(currentPeriod, granularity, precision);
        if (current == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本期期间无法解析：" + req.period());
        }
        AttributionSqlBuilder.PeriodRange base = sqlBuilder.baseRange(current, granularity, baseline);
        String basePeriod = null;
        if (base == null) {
            // PEAK：各成员峰值期不同会导致加总后指向一个历史上从未存在的组合，必须先定位真实峰值期
            basePeriod = queryPeakPeriod(adapter, config, schema, measureExpr, granularity);
            base = sqlBuilder.rangeOf(basePeriod, granularity, precision);
            if (base == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未能定位历史峰值期，无法按 PEAK 归因");
            }
        } else {
            basePeriod = periodTextOf(base.start(), granularity);
        }

        // ⑤ 三套 SQL 独立生成（审计要求：每份可单独查看、复制、重跑）
        String attributionSql = sqlBuilder.contributionSql(
                schema.table(), dim.column(), schema.timeColumn(), measureExpr, current, base, config.type());
        String trendSql = sqlBuilder.trendSql(schema.table(), schema.timeColumn(), measureExpr, 12, config.type());
        // 趋势数据独立执行并返回（与三套审计 SQL 解耦）：此前 trendSql 只生成不下发执行，
        // 前端只能以文本看到语句；现在把序列算出来供折线图渲染。
        QueryResult trendResult = dataMaskGate.mask(execute(adapter, config, trendSql), metric.getDatasourceId());
        List<AttributionTrendPoint> trend = toTrend(trendResult);

        // ⑥ 维度归因执行与贡献计算
        QueryResult contribution = dataMaskGate.mask(execute(adapter, config, attributionSql), metric.getDatasourceId());
        ContributionResult cr = toContributions(contribution, req.topNorDefault());
        List<ContributionItem> items = cr.top();

        // ⑦ 量价因子拆解（需要"量"字段；推断不到则跳过，不产出误导性结论）
        List<FactorItem> factors = List.of();
        String factorSql = null;
        String volumeExpr = req.volumeExpr() != null && !req.volumeExpr().isBlank()
                ? req.volumeExpr().trim()
                : dimensionResolver.inferVolumeExpr(metric.getDatasourceId());
        if (volumeExpr != null) {
            factorSql = sqlBuilder.priceVolumeSql(
                    schema.table(), schema.timeColumn(), measureExpr, volumeExpr, current, base, config.type());
            QueryResult pv = dataMaskGate.mask(execute(adapter, config, factorSql), metric.getDatasourceId());
            factors = toFactors(pv);
        }

        // ⑧ 质量校验联动（阶段 0 规则链：缺基准 / 维度稀疏 / 可加性）
        AttributionContext ctx = AttributionContext.withAdditivity(
                null, definition, dim.column(), baseline, contribution.rowCount(), additive);
        List<String> issues = collectIssues(ctx);

        // ⑧′ 空期保护：本期或基期合计为 0（该期无数据）时，"下降 100%"是区间错位或数据缺失的
        //     典型症状，结论不可信。必须显式提示并大幅扣分——否则会出现置信度 0.95 却结论荒谬的情况。
        BigDecimal curTotal0 = cr.curTotal();
        BigDecimal baseTotal0 = cr.baseTotal();
        boolean emptyPeriod = curTotal0.signum() == 0 || baseTotal0.signum() == 0;
        if (emptyPeriod) {
            issues.add("本期或基期无数据（合计为 0）：请确认时间范围与数据是否已更新，当前归因结论不可信");
        }

        // ⑨ 置信度门控（需求 §3.2）：分数只由可观测信号扣分得出，绝不由模型"感觉"打分。
        //    已澄清过的请求不再回 CLARIFY，从机制上保证"最多 1 轮"。
        double confidence = confidenceOf(baselineExplicit, items.size(), issues,
                contribution.rowCount() >= maxRows, factors.isEmpty());
        if (emptyPeriod) {
            confidence = Math.max(0, confidence - 0.5);
        }
        // 「置信度单轮澄清」开关（ATTRIBUTION / clarify-enabled）：关闭后中等区间直接出结论、不再弹澄清窗
        // （等价于"全量一次性返回"，保留原有一键体验）。★只影响"是否多问一轮"，**不放宽 <30% 的硬阻断**
        // ——后者是"结论不可信"的保护，与"要不要多问一轮"是两回事。
        boolean clarifyOn = aibiConfig.clarifyEnabled();
        String gate = AttributionGate.of(confidence, req.clarifiedOrDefault(), clarifyOn);
        // 澄清被开关抑制时不确定性依然存在 → 解读里保留风险提示，避免呈现为"高置信直出"。
        boolean clarifySuppressed = AttributionGate.clarifySuppressed(confidence, clarifyOn);

        // ⑩ 解读文案：由已算好的结构化结果确定性拼装。需求 §6 明令"LLM 仅做文字翻译、
        //    不参与数值计算"，这里连翻译都不需要大模型——模板化输出可保证每个数字都能对上 items。
        String insight = buildInsight(nameOf(metric), dim.label(), baseline,
                periodTextOf(current.start(), granularity), basePeriod,
                cr.curTotal(), cr.baseTotal(), items, gate, clarifySuppressed);

        log.info("归因完成 指标[{}] 维度[{}] 基准[{}] 本期[{}] 基期[{}] 成员[{}] 置信度[{}] 门控[{}] 澄清开关[{}] 耗时[{}ms]",
                metric.getId(), dim.column(), baseline, currentPeriod, basePeriod,
                items.size(), String.format("%.2f", confidence), gate, clarifyOn, System.currentTimeMillis() - started);

        // ⑩ SQL 可见性（需求 §4）：无权角色在服务端就拿不到 SQL 文本，而非前端隐藏后仍能抓包看到。
        boolean sqlVisible = sqlVisibility.canViewSql();

        AttributionVO vo = new AttributionVO(
                metric.getId(),
                nameOf(metric),
                dim.column(),
                dim.label(),
                baseline,
                periodTextOf(current.start(), granularity),
                basePeriod,
                cr.curTotal(),
                cr.baseTotal(),
                cr.totalDelta(),
                items,
                factors,
                trend,
                additive,
                sqlVisible ? trendSql : null,
                sqlVisible ? factorSql : null,
                sqlVisible ? attributionSql : null,
                issues,
                insight,
                System.currentTimeMillis() - started,
                confidence,
                gate,
                sqlVisible
        );
        persistToHistory(req, vo);
        return vo;
    }

    /**
     * 把归因结果回写到发起它的那条问数历史（{@code query_history.attribution_json}），使历史回放能还原归因面板。
     *
     * <p>与 {@code insightJson} / {@code verifyJson} 同一套口径：**只存不重算**——归因依赖"当时的口径与维度选择"，
     * 回放时重跑既耗资源，也可能因数据/口径已变而与当时结论不一致，反而误导。老历史（无此列值）回放时不显示归因。
     *
     * <p>失败只记日志不抛：归因本身已经成功，落库属于"锦上添花"，因此失败不应让用户的归因结果消失，
     * 这与驾驶舱"单 widget 刷新失败降级占位"是同一条原则。
     */
    private void persistToHistory(AttributionReq req, AttributionVO vo) {
        Long historyId = req == null ? null : req.historyId();
        if (historyId == null) {
            return;   // 指标库等无历史上下文的场景：结果本就无处可挂
        }
        try {
            historyMapper.update(null, Wrappers.<QueryHistory>lambdaUpdate()
                    .eq(QueryHistory::getId, historyId)
                    .set(QueryHistory::getAttributionJson, objectMapper.writeValueAsString(vo)));
        } catch (Exception e) {
            log.warn("归因结果回写历史失败（不影响本次归因结果）: historyId={}, msg={}", historyId, e.getMessage());
        }
    }

    /** 某指标可选归因维度（已剔除敏感列），供前端下拉。 */
    public AttributionOptionVO dimensions(Long metricId) {
        Metric metric = requireMetric(metricId);
        return new AttributionOptionVO(
                dimensionResolver.dimensionOptions(metric.getDatasourceId()),
                metric.getAttributionDimension(),
                metric.getBaselineType(),
                metric.getDefinition() != null && !metric.getDefinition().isBlank());
    }

    // ---------- 内部实现 ----------

    private Metric requireMetric(Long id) {
        Metric metric = metricMapper.selectById(id);
        if (metric == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指标不存在或无权访问: " + id);
        }
        return metric;
    }

    /** 首值为空时回落第二值（用于「请求未指定 → 取指标沉淀默认值 → 再回落兜底值」）。 */
    private String fallback(String primary, String secondary) {
        return (primary != null && !primary.isBlank()) ? primary : secondary;
    }

    private String nameOf(Metric m) {
        return (m.getAlias() != null && !m.getAlias().isBlank()) ? m.getAlias() : m.getName();
    }

    private DimensionResolver.DimensionOption findDimension(DimensionResolver.AttributionSchema schema, String column) {
        if (column == null) {
            return null;
        }
        for (DimensionResolver.DimensionOption option : schema.dimensions()) {
            if (column.equalsIgnoreCase(option.column())) {
                return option;
            }
        }
        return null;
    }

    /** 只读执行一条已生成 SQL（复校 → 适配器执行）。 */
    private QueryResult execute(DataSourceAdapter adapter, DataSourceConfig config, String sql) {
        return adapter.execute(config, sqlValidator.validate(sql), maxRows, timeoutSeconds);
    }

    /** 查数据最新一期（period 未指定时）。 */
    private String queryLatestPeriod(DataSourceAdapter adapter, DataSourceConfig config,
                                     DimensionResolver.AttributionSchema schema, String granularity) {
        String sql = sqlBuilder.latestPeriodSql(schema.table(), schema.timeColumn(), config.type());
        QueryResult result = execute(adapter, config, sql);
        if (result.rows() == null || result.rows().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未能从数据中获取最新期间");
        }
        String period = normalizePeriod(text(result.rows().get(0).isEmpty() ? null : result.rows().get(0).get(0)),
                granularity);
        if (period == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未能从数据中获取最新期间");
        }
        return period;
    }

    /**
     * 探测时间列的存储精度：取该列最大值看文本形态。
     *
     * <p>长度 ≤ 7（{@code 2026-09}）即月粒度，边界须渲染为 {@code yyyy-MM}；
     * 更长（{@code 2026-09-19}、带时分秒的 DATETIME）按日粒度渲染 {@code yyyy-MM-dd}。
     * 探测失败一律回落日粒度——那只是退回修复前的行为，不会更糟。
     */
    private AttributionSqlBuilder.TimePrecision detectTimePrecision(DataSourceAdapter adapter,
                                                                    DataSourceConfig config,
                                                                    Long datasourceId,
                                                                    DimensionResolver.AttributionSchema schema) {
        String key = datasourceId + "|" + schema.table() + "|" + schema.timeColumn();
        return precisionCache.computeIfAbsent(key, k -> {
            try {
                QueryResult r = execute(adapter, config,
                        sqlBuilder.latestPeriodSql(schema.table(), schema.timeColumn(), config.type()));
                Object first = (r.rows() == null || r.rows().isEmpty() || r.rows().get(0).isEmpty())
                        ? null : r.rows().get(0).get(0);
                String sample = text(first);
                boolean month = sample != null && sample.trim().length() <= 7;
                log.info("时间列精度探测 {}.{} 样本[{}] -> {}",
                        schema.table(), schema.timeColumn(), sample, month ? "MONTH" : "DAY");
                return month ? AttributionSqlBuilder.TimePrecision.MONTH
                        : AttributionSqlBuilder.TimePrecision.DAY;
            } catch (Exception e) {
                log.warn("时间列精度探测失败，回落日粒度边界: {}", e.getMessage());
                return AttributionSqlBuilder.TimePrecision.DAY;
            }
        });
    }

    /** 查历史峰值期（PEAK 基准）。 */
    private String queryPeakPeriod(DataSourceAdapter adapter, DataSourceConfig config,
                                   DimensionResolver.AttributionSchema schema, String measureExpr, String granularity) {
        String sql = sqlBuilder.peakPeriodSql(schema.table(), schema.timeColumn(), measureExpr, config.type());
        QueryResult result = execute(adapter, config, sql);
        if (result.rows() == null || result.rows().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未能定位历史峰值期");
        }
        String period = normalizePeriod(text(result.rows().get(0).isEmpty() ? null : result.rows().get(0).get(0)),
                granularity);
        if (period == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未能定位历史峰值期");
        }
        return period;
    }

    /**
     * 把归因结果集转换为贡献明细（按贡献绝对值降序、截断 topN）。
     *
     * <p>贡献 = 本期 − 基期；占比以<b>总变化</b>为分母。总变化为 0 时占比记为 null——
     * 此时"各成员贡献占比"在数学上无定义（除零），前端应隐藏占比列而非显示 0%。
     */
    private ContributionResult toContributions(QueryResult result, int topN) {
        int memberIdx = indexOf(result, "dim_member", 0);
        int curIdx = indexOf(result, "cur_val", 1);
        int baseIdx = indexOf(result, "base_val", 2);

        List<ContributionItem> all = new ArrayList<>();
        BigDecimal curTotal = BigDecimal.ZERO;
        BigDecimal baseTotal = BigDecimal.ZERO;
        if (result.rows() != null) {
            for (List<Object> row : result.rows()) {
                String member = text(at(row, memberIdx));
                BigDecimal cur = decimal(at(row, curIdx));
                BigDecimal base = decimal(at(row, baseIdx));
                curTotal = curTotal.add(cur);
                baseTotal = baseTotal.add(base);
                all.add(new ContributionItem(member, member == null ? "(空)" : member,
                        cur, base, cur.subtract(base), null, null, null));
            }
        }
        BigDecimal totalDelta = curTotal.subtract(baseTotal);
        boolean hasDelta = totalDelta.compareTo(BigDecimal.ZERO) != 0;

        List<ContributionItem> withShares = new ArrayList<>(all.size());
        boolean hasCur = curTotal.compareTo(BigDecimal.ZERO) != 0;
        boolean hasBase = baseTotal.compareTo(BigDecimal.ZERO) != 0;
        for (ContributionItem item : all) {
            Double share = hasDelta ? ratio(item.delta(), totalDelta) : null;
            Double curShare = hasCur ? ratio(item.cur(), curTotal) : null;
            Double baseShare = hasBase ? ratio(item.base(), baseTotal) : null;
            withShares.add(new ContributionItem(item.member(), item.label(), item.cur(), item.base(),
                    item.delta(), share, curShare, baseShare));
        }
        withShares.sort(Comparator.comparing((ContributionItem i) -> i.delta().abs()).reversed());
        List<ContributionItem> top = withShares.size() <= topN
                ? withShares
                : new ArrayList<>(withShares.subList(0, topN));
        // 合计一律取全量成员：topN 只是"明细展示条数"，绝不能反向影响总额，
        // 否则 Σ(topN 贡献) ≠ ΔM，图表与解读会出现"各成员占比之和 ≠ 100%"的自相矛盾。
        return new ContributionResult(withShares, top, curTotal, baseTotal);
    }

    /**
     * 趋势序列解析：把趋势 SQL 结果集（period, metric_value）转为有序数据点列表，供前端画折线。
     *
     * <p>趋势 SQL 已按 period ASC 排序，直接按序渲染即可；period 取时间列原值（保留月/日形态）。
     */
    private List<AttributionTrendPoint> toTrend(QueryResult result) {
        if (result.rows() == null || result.rows().isEmpty()) {
            return List.of();
        }
        int pIdx = indexOf(result, "period", 0);
        int vIdx = indexOf(result, "metric_value", 1);
        List<AttributionTrendPoint> points = new ArrayList<>(result.rows().size());
        for (List<Object> row : result.rows()) {
            points.add(new AttributionTrendPoint(text(at(row, pIdx)), decimal(at(row, vIdx))));
        }
        return points;
    }

    /**
     * 维度归因结果：全量成员 + 截断后的展示明细 + **全量**合计。
     *
     * <p>为什么合计必须单独带出来：如果让调用方对 {@code top} 求和，当 topN 小于维度基数时
     * 得到的只是"头部几项之和"，而它会被当作本期/基期总额展示——
     * 表现为 totalDelta 与各成员 share 对不上（share 按全量算、总额按截断算）。
     */
    private record ContributionResult(List<ContributionItem> all, List<ContributionItem> top,
                                      BigDecimal curTotal, BigDecimal baseTotal) {

        private BigDecimal totalDelta() {
            return curTotal.subtract(baseTotal);
        }
    }

    /**
     * 量价两因素分解（含交叉项）：ΔM = (V1−V0)·P0 + (P1−P0)·V0 + (V1−V0)·(P1−P0)。
     *
     * <p>单价由「总额/总量」推导而非业务库单列，保证价与额同源；总量为 0 时无法定义单价，
     * 直接返回空列表（不做除零兜底出一个假数）。
     */
    private List<FactorItem> toFactors(QueryResult pv) {
        if (pv.rows() == null || pv.rows().isEmpty()) {
            return List.of();
        }
        List<Object> row = pv.rows().get(0);
        BigDecimal curMetric = decimal(at(row, indexOf(pv, "cur_metric", 0)));
        BigDecimal curVolume = decimal(at(row, indexOf(pv, "cur_volume", 1)));
        BigDecimal baseMetric = decimal(at(row, indexOf(pv, "base_metric", 2)));
        BigDecimal baseVolume = decimal(at(row, indexOf(pv, "base_volume", 3)));

        if (curVolume.compareTo(BigDecimal.ZERO) == 0 || baseVolume.compareTo(BigDecimal.ZERO) == 0) {
            return List.of();
        }
        BigDecimal p1 = curMetric.divide(curVolume, 10, RoundingMode.HALF_UP);
        BigDecimal p0 = baseMetric.divide(baseVolume, 10, RoundingMode.HALF_UP);
        BigDecimal dv = curVolume.subtract(baseVolume);
        BigDecimal dp = p1.subtract(p0);

        BigDecimal volumeEffect = dv.multiply(p0);
        BigDecimal priceEffect = dp.multiply(baseVolume);
        BigDecimal crossEffect = dv.multiply(dp);

        List<FactorItem> factors = new ArrayList<>();
        factors.add(new FactorItem("基期值", baseMetric, FactorItem.KIND_BASE, "基期度量"));
        factors.add(new FactorItem("量效应", volumeEffect, FactorItem.KIND_VOLUME, "销量/数量变动带来的影响"));
        factors.add(new FactorItem("价效应", priceEffect, FactorItem.KIND_PRICE, "单价变动带来的影响"));
        factors.add(new FactorItem("交叉效应", crossEffect, FactorItem.KIND_CROSS, "量与价同时变动的联合影响"));
        factors.add(new FactorItem("本期值", curMetric, FactorItem.KIND_TOTAL, "本期度量"));
        return factors;
    }

    /** 跑质量规则链，把命中项转成展示文本（ERROR 在前）。 */
    private List<String> collectIssues(AttributionContext ctx) {
        if (rules.isEmpty()) {
            return List.of();
        }
        List<RuleIssue> all = new ArrayList<>();
        for (AttributionQualityRule rule : rules) {
            try {
                List<RuleIssue> hit = rule.check(ctx);
                if (hit != null) {
                    all.addAll(hit);
                }
            } catch (RuntimeException e) {
                // 单条规则异常绝不阻断归因主流程（与技能路由的容错同策略）
                log.warn("归因质量规则[{}]执行异常已跳过：{}", rule.code(), e.getMessage());
            }
        }
        all.sort(Comparator.comparing((RuleIssue i) -> "ERROR".equals(i.level()) ? 0 : 1));
        List<String> issues = new ArrayList<>(all.size());
        for (RuleIssue issue : all) {
            issues.add("[" + issue.code() + "] " + issue.message());
        }
        return issues;
    }

    /**
     * 归因场景置信度（0~1）。
     *
     * <p><b>为什么不用模型打分</b>：置信度要驱动"是否自动执行归因"的门控，若由模型产出，
     * 同一问题两次调用可能给出不同门控，用户会看到归因时有时无。这里全部扣分项都是
     * 可观测、可复现的确定性信号，扣分权重集中在本方法，便于后续按租户配置化。
     *
     * @param baselineExplicit 用户是否显式选择了对比基准（未选=存在歧义）
     * @param memberCount      参与归因的维度成员数（过少=维度稀疏）
     * @param issues           质量校验命中项
     * @param truncated        结果是否被行数上限截断
     * @param factorMissing    是否未能做量价拆解（缺量字段）
     */
    private double confidenceOf(boolean baselineExplicit, int memberCount, List<String> issues,
                                boolean truncated, boolean factorMissing) {
        double score = 1.0;
        if (!baselineExplicit) {
            score -= 0.15;                       // 对比基准不明确：歧义信号（需求 §3.4）
        }
        if (memberCount <= 0) {
            score -= 0.40;                       // 根本没有可比成员，结论无意义
        } else if (memberCount < 3) {
            score -= 0.30;                       // 维度稀疏：样本过少（需求 §3.1 校验项）
        } else if (memberCount < 5) {
            score -= 0.15;
        }
        int issuePenalty = Math.min(issues.size(), 3) * 10;
        score -= issuePenalty / 100.0;           // 每条校验命中扣 0.1，最多 0.3
        if (truncated) {
            score -= 0.10;                       // 结果被截断，贡献加总可能不等于总变化
        }
        if (factorMissing) {
            score -= 0.05;                       // 缺量价拆解，只能给出维度结论
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 由结构化结果确定性生成解读文案。
     *
     * <p>所有数字均取自已算好的 {@code items}，模板只负责组织语言——这样解读里出现的
     * 每一个百分比都能在明细表里找到出处，审计可逐项复核（需求 §6）。
     *
     * @param clarifySuppressed 中等置信度本应触发澄清、但 {@code ATTRIBUTION / clarify-enabled}
     *                          被关闭而抑制了澄清窗。此时结论仍带不确定性，故照样附风险提示，
     *                          避免用户把"没被追问"误读为"结论高置信、可直接采信"。
     */
    private String buildInsight(String metricName, String dimensionLabel, String baseline,
                                String currentPeriod, String basePeriod,
                                BigDecimal curTotal, BigDecimal baseTotal,
                                List<ContributionItem> items, String gate,
                                boolean clarifySuppressed) {
        if (AttributionVO.GATE_BLOCK.equals(gate)) {
            return "置信度不足 30%，已阻止自动归因：请先在【问数调优中心】完善该指标的口径表达式"
                    + "与时间列/维度标记，再重新发起归因。";
        }
        if (items == null || items.isEmpty()) {
            return "本期与基期均无「" + dimensionLabel + "」的可比数据，无法给出归因结论。";
        }
        BigDecimal delta = curTotal.subtract(baseTotal);
        String direction = delta.signum() >= 0 ? "上升" : "下降";
        String baseName = baselineName(baseline);
        StringBuilder sb = new StringBuilder();
        sb.append(metricName).append("本期（").append(currentPeriod).append("）")
                .append("为 ").append(plain(curTotal)).append("，")
                .append(baseName).append("（").append(basePeriod).append("）")
                .append("为 ").append(plain(baseTotal)).append("，")
                .append("合计").append(direction).append(" ").append(plain(delta.abs())).append("。");
        ContributionItem top = items.get(0);
        ContributionItem bottom = items.get(items.size() - 1);
        sb.append("按「").append(dimensionLabel).append("」拆解，")
                .append(top.label()).append("贡献 ").append(plain(top.delta()))
                .append("（占变化 ").append(percent(top.share())).append("），是最主要的")
                .append(top.delta().signum() >= 0 ? "正向拉动" : "负向拖累").append("项；")
                .append(bottom.label()).append("贡献 ").append(plain(bottom.delta()))
                .append("，位于另一端。");
        if (AttributionVO.GATE_CLARIFY.equals(gate) || clarifySuppressed) {
            sb.append("注意：本次对比基准与维度样本量存在不确定性，建议确认对比基准后重跑以提升结论可信度。");
        }
        return sb.toString();
    }

    private String baselineName(String baseline) {
        if (AttributionReq.BASELINE_YOY.equals(baseline)) {
            return "去年同期";
        }
        if (AttributionReq.BASELINE_PEAK.equals(baseline)) {
            return "历史高点";
        }
        return "上期";
    }

    /** 数值去尾零展示（避免 BigDecimal 直接 toString 出现 1.230000 这类噪声）。 */
    private String plain(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String percent(Double share) {
        if (share == null) {
            return "—";
        }
        return String.format("%.1f%%", share * 100);
    }

    private BigDecimal sumOf(List<ContributionItem> items, boolean current) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ContributionItem item : items) {
            sum = sum.add(current ? item.cur() : item.base());
        }
        return sum;
    }

    /** 占比（分母为 0 返回 null，由调用方决定是否展示）。 */
    private Double ratio(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return part.divide(whole, 6, RoundingMode.HALF_UP).doubleValue();
    }

    /** 规范化期间文本：month 取年月、day 取日期；兼容"2026-08-01"这类完整日期输入。 */
    private String normalizePeriod(Object raw, String granularity) {
        String text = text(raw);
        if (text == null) {
            return null;
        }
        // 统一截取：日期/日期时间都按前 10 位（yyyy-MM-dd）处理，月粒度再截到前 7 位
        String date = text.length() >= 10 ? text.substring(0, 10) : text;
        if ("month".equalsIgnoreCase(granularity)) {
            return date.length() >= 7 ? date.substring(0, 7) : null;
        }
        return date;
    }

    /** 由期间起点反推展示文本（与 {@link #normalizePeriod} 同格式）。 */
    private String periodTextOf(LocalDate date, String granularity) {
        if (date == null) {
            return null;
        }
        String full = DATE.format(date);
        return "month".equalsIgnoreCase(granularity) ? full.substring(0, 7) : full;
    }

    private int indexOf(QueryResult result, String column, int fallback) {
        if (result.columns() == null) {
            return fallback;
        }
        for (int i = 0; i < result.columns().size(); i++) {
            if (column.equalsIgnoreCase(result.columns().get(i))) {
                return i;
            }
        }
        return fallback;
    }

    private Object at(List<Object> row, int index) {
        return (row == null || index < 0 || index >= row.size()) ? null : row.get(index);
    }

    /** 任意结果值转字符串（日期对象统一按 yyyy-MM-dd，便于后续解析）。 */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate ld) {
            return DATE.format(ld);
        }
        if (value instanceof java.sql.Date sd) {
            return sd.toLocalDate().toString();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate().toString();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** 任意结果值转 BigDecimal（数值/字符串皆可；不可解析记 0，避免单行脏数据炸掉整次归因）。 */
    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
