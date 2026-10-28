package com.aibi.module.alert.service;

import com.aibi.module.dashboard.dto.MetricDataVO;
import com.aibi.module.dashboard.service.MetricService;
import com.aibi.module.datasource.mapper.DataSourceMapper;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 内置指标解析器：基于真实数据表计算当前值，租户由拦截器自动注入（请求线程或已设上下文的定时线程）。
 *
 * <p><b>平台内置键</b>（对平台自有元数据表的聚合）：
 * <ul>
 *   <li>{@code query.count.24h} —— 近 24h 问数</li>
 *   <li>{@code query.fail.count.24h} —— 近 24h 失败问数</li>
 *   <li>{@code query.fail.rate.24h} —— 近 24h 失败率（%）</li>
 *   <li>{@code datasource.count} —— 数据源数</li>
 * </ul>
 *
 * <p><b>业务指标键</b>（P1-4 新增）：{@code metric:<id>} —— 复用 {@link MetricService#refresh} 重跑该指标已存 SQL
 * （不消耗 LLM、不计入问数配额），取结果<b>首行首个可解析为数值的单元格</b>作为当前值，使预警可监控指标库中的
 * 业务口径（营收、订单数、客单价……），而非仅平台运行健康度。指标不存在 / 非本租户 / 结果为空 /
 * 首行无任何数值列时返回 empty（引擎跳过该规则，不报错）。
 *
 * <p><b>为什么不是"严格首列"</b>：时序指标的 SQL 形如 {@code SELECT order_month, SUM(amount) ...}，
 * 首列是期间字符串，严格取首列会导致这类指标<b>永远取不到值、规则配了却从不触发</b>——
 * 而时序指标恰恰是最该被监控的一类。故改为跨列扫描首个数值，单值指标行为完全不变。
 *
 * <p><b>边界</b>：指标首列若被语义层标记为敏感列且当前上下文角色无权查看，{@code refresh} 会将其脱敏为
 * {@code ***}，此处解析失败即跳过——即敏感指标不会进入预警。定时扫描线程仅有 tenantId、无组织角色，
 * 故对含"可见角色"白名单的敏感列一律跳过；若需对敏感指标告警，请改用非敏感列或内置键。
 */
@Service
public class DefaultMetricValueResolver implements MetricValueResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultMetricValueResolver.class);

    /** 业务指标键前缀：{@code metric:<id>}。 */
    private static final String METRIC_PREFIX = "metric:";

    private final QueryHistoryMapper queryHistoryMapper;
    private final DataSourceMapper dataSourceMapper;
    private final MetricService metricService;

    public DefaultMetricValueResolver(QueryHistoryMapper queryHistoryMapper, DataSourceMapper dataSourceMapper,
                                      MetricService metricService) {
        this.queryHistoryMapper = queryHistoryMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.metricService = metricService;
    }

    @Override
    public Optional<BigDecimal> resolve(Long tenantId, String metricKey) {
        if (metricKey == null) {
            return Optional.empty();
        }
        if (metricKey.startsWith(METRIC_PREFIX)) {
            return resolveBusinessMetric(metricKey.substring(METRIC_PREFIX.length()));
        }
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        switch (metricKey) {
            case "query.count.24h":
                return Optional.of(BigDecimal.valueOf(countQuery(since24h, null)));
            case "query.fail.count.24h":
                return Optional.of(BigDecimal.valueOf(countQuery(since24h, "FAILED")));
            case "query.fail.rate.24h": {
                long total = countQuery(since24h, null);
                if (total == 0) {
                    return Optional.of(BigDecimal.ZERO);
                }
                long failed = countQuery(since24h, "FAILED");
                return Optional.of(BigDecimal.valueOf(failed)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
            }
            case "datasource.count":
                Long ds = dataSourceMapper.selectCount(null);
                return Optional.of(BigDecimal.valueOf(ds == null ? 0L : ds));
            default:
                return Optional.empty();
        }
    }

    /**
     * 业务指标取值（{@code metric:<id>}）：重跑已存 SQL 取首行首列数值。
     *
     * <p>任何异常（指标不存在/越权/数据源不可用/列非数值）都归一为 empty，交由引擎跳过，
     * 不让单条规则的取值失败拖垮整轮检测。
     */
    private Optional<BigDecimal> resolveBusinessMetric(String idText) {
        long id;
        try {
            id = Long.parseLong(idText == null ? "" : idText.trim());
        } catch (NumberFormatException e) {
            log.debug("业务指标键非法（无法解析 id）：metric:{}", idText);
            return Optional.empty();
        }
        try {
            MetricDataVO data = metricService.refresh(id);
            QueryResult qr = data == null ? null : data.result();
            if (qr == null || qr.rows() == null || qr.rows().isEmpty()) {
                return Optional.empty();
            }
            List<Object> first = qr.rows().get(0);
            if (first == null || first.isEmpty()) {
                return Optional.empty();
            }
            // 取"首个可解析为数值的单元格"而非严格首列：
            // 时序指标（如 SELECT order_month, SUM(amount) ... GROUP BY order_month）首列是期间字符串，
            // 严格取首列会让这类指标永远解析不出数值 → 规则静默不触发，表现为"配了规则却从不报警"。
            // 跨列扫描后，时序指标取同行的度量值（如 total_sales），单值指标行为不变（首列本就是数值）。
            for (Object cell : first) {
                Optional<BigDecimal> v = toBigDecimal(cell);
                if (v.isPresent()) {
                    return v;
                }
            }
            log.debug("业务指标[{}]首行无可解析数值列，跳过", id);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("业务指标[{}]取值失败，跳过预警：{}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** 单元格 → 数值：非数值（期间字符串、脱敏后的 ***、NULL）一律返回 empty 交由调用方继续找下一列。 */
    private Optional<BigDecimal> toBigDecimal(Object cell) {
        if (cell == null) {
            return Optional.empty();
        }
        try {
            if (cell instanceof Number n) {
                return Optional.of(new BigDecimal(n.toString()));
            }
            String text = String.valueOf(cell).trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private long countQuery(LocalDateTime since, String status) {
        LambdaQueryWrapper<QueryHistory> w = new LambdaQueryWrapper<QueryHistory>()
                .ge(QueryHistory::getCreatedAt, since);
        if (status != null) {
            w.eq(QueryHistory::getStatus, status);
        }
        Long c = queryHistoryMapper.selectCount(w);
        return c == null ? 0L : c;
    }
}
