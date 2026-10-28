package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.UserContext;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.acl.entity.ResourceAcl;
import com.aibi.module.acl.service.ResourceAclService;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.dashboard.dto.DashboardFilter;
import com.aibi.module.dashboard.dto.MetricDataVO;
import com.aibi.module.dashboard.dto.MetricForm;
import com.aibi.module.dashboard.dto.MetricVO;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.dialect.SqlDialects;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.semantic.service.SemanticService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 指标服务（Feature 2）：把问数结果保存为可复用图表指标，并支持“实时重跑已存 SQL”刷新。
 *
 * <p><b>隔离</b>：{@code metric} 由租户拦截器自动注入 {@code tenant_id}，{@code selectById} 天然限本租户；
 * 刷新时 {@link DataSourceService#loadConfig} 再次拦截跨租户数据源访问，双重保险。
 *
 * <p><b>刷新不消耗 LLM、不计入问数配额</b>：直接 {@code SqlValidator} 复校已存 SQL → 适配器只读执行，
 * 与 {@code Nl2SqlEngine} 的执行段一致，但跳过意图/裁剪/Prompt/LLM/修正全过程。
 */
@Service
public class MetricService {

    private static final Logger log = LoggerFactory.getLogger(MetricService.class);

    private final MetricMapper metricMapper;
    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final SemanticService semanticService;
    private final DashboardJsonSupport json;
    private final DataMaskGate dataMaskGate;
    private final ResourceAclService resourceAclService;
    /** 分组校验走共享服务（指标域），与驾驶舱共用一套 {@code resource_group}。 */
    private final ResourceGroupService groupService;
    private final AibiConfig aibiConfig;
    private final long maxRows;
    private final int timeoutSeconds;

    public MetricService(MetricMapper metricMapper,
                         DataSourceService dataSourceService,
                         DataSourceAdapterRegistry adapterRegistry,
                         SqlValidator sqlValidator,
                         SemanticService semanticService,
                         DashboardJsonSupport json,
                         DataMaskGate dataMaskGate,
                         ResourceAclService resourceAclService,
                         ResourceGroupService groupService,
                         AibiConfig aibiConfig,
                         @Value("${aibi.query.max-rows:10000}") long maxRows,
                         @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.metricMapper = metricMapper;
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.semanticService = semanticService;
        this.json = json;
        this.dataMaskGate = dataMaskGate;
        this.resourceAclService = resourceAclService;
        this.groupService = groupService;
        this.aibiConfig = aibiConfig;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<MetricVO> list() {
        // SLA 是租户级全局阈值，对所有指标一致；只在循环外取一次。
        // 之前在 staleOf 内逐指标调用 aibiConfig.freshnessSlaHours()，而该方法默认每次查 sys_config
        // （config-cache-ttl-seconds 默认 0 不缓存）→ N+1 式串行跨机 DB 往返，634 指标吊死 ~35s。
        int sla = aibiConfig.freshnessSlaHours();
        return metricMapper.selectList(Wrappers.<Metric>lambdaQuery().orderByDesc(Metric::getId))
                .stream().map(m -> toVO(m, sla)).toList();
    }

    public MetricVO get(Long id) {
        return toVO(require(id));
    }

    @Transactional
    public MetricVO create(MetricForm form) {
        // 去重：同一数据源 + 同一问数 已保存为指标则拒绝，避免重复保存（问题1）；
        // 提示带上已存指标名，历史回放再存等场景用户能直接知道"存过了、存在哪"
        if (form.question() != null && !form.question().isBlank()) {
            Metric dup = metricMapper.selectOne(Wrappers.<Metric>lambdaQuery()
                    .eq(Metric::getDatasourceId, form.datasourceId())
                    .eq(Metric::getQuestion, form.question())
                    .last("LIMIT 1"));
            if (dup != null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "该问数已保存为指标「" + dup.getName() + "」，无需重复保存，可在指标管理中查看");
            }
        }
        Metric metric = new Metric();
        metric.setUid(safeUid());
        metric.setName(form.name());
        metric.setDescription(form.description());
        metric.setAlias(form.alias());
        // 口径归一：防止把 SQL 输出别名（如 total_quantity）当成真实表达式存下来——
        // 归因会把它拼成 SUM(total_quantity) 而业务库并无此列，一归因就报 Unknown column。
        metric.setDefinition(MetricDefinitionNormalizer.canonicalize(form.definition(), form.sql()));
        metric.setAttributionDimension(form.attributionDimension());
        metric.setBaselineType(form.baselineType());
        metric.setFreshnessHours(normalizeFreshness(form.freshnessHours()));
        metric.setDatasourceId(form.datasourceId());
        metric.setQuestion(form.question());
        metric.setSource(form.source());
        metric.setSqlText(form.sql());
        metric.setChartSpec(json.write(form.chartSpec()));
        metric.setDisplayConfig(json.writeColumnLabels(form.columnLabels()));
        // 保存即记一次取数：能被保存的 SQL 刚在问数链路真实执行成功过，refreshed_at 直接落当前时间，
        // 避免新指标一进库就「尚未取数」并计入顶部超期告警（历史回放再保存的场景略有乐观偏差，可接受）。
        // 数据展示永远实时重跑已存 SQL，此字段只服务新鲜度状态机；后续仍以指标链路真实取数回写为准。
        metric.setRefreshedAt(LocalDateTime.now());
        metricMapper.insert(metric);   // 租户拦截器自动补 tenant_id；IdType.AUTO 回填主键
        resourceAclService.grant(TenantContext.requireTenantId(), "METRIC", metric.getId(),
                metric.getUid(), null, ResourceAcl.VISIBILITY_ORG);
        return toVO(metric);
    }

    @Transactional
    public MetricVO update(Long id, MetricForm form) {
        require(id);
        metricMapper.update(null, Wrappers.<Metric>lambdaUpdate()
                .eq(Metric::getId, id)
                .set(Metric::getName, form.name())
                .set(Metric::getDescription, form.description())
                .set(Metric::getAlias, form.alias())
                // 同一道口径归一护栏：更新路径同样不允许把输出别名写成口径表达式
                .set(Metric::getDefinition, MetricDefinitionNormalizer.canonicalize(form.definition(), form.sql()))
                .set(Metric::getAttributionDimension, form.attributionDimension())
                .set(Metric::getBaselineType, form.baselineType())
                .set(Metric::getFreshnessHours, normalizeFreshness(form.freshnessHours()))
                .set(Metric::getDatasourceId, form.datasourceId())
                .set(Metric::getQuestion, form.question())
                .set(Metric::getSqlText, form.sql())
                .set(Metric::getChartSpec, json.write(form.chartSpec()))
                .set(Metric::getDisplayConfig, json.writeColumnLabels(form.columnLabels())));
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        metricMapper.deleteById(id);
        resourceAclService.deleteByResource("METRIC", id);
    }

    /**
     * 实时刷新：重跑该指标已存 SQL，返回最新结果 + 图表规格。不消耗 LLM、不计入配额。
     *
     * <p>结果列展示名按「指标级自定义 &gt; 语义层别名 &gt; 物理列名」合并：汇总/派生列
     * （{@code SUM(amount) AS total_amount}）在语义层没有物理列行，靠指标自带的 {@code display_config}
     * 取业务叫法，使驾驶舱 widget 与指标预览的表头、图表图例/系列名一致；图表规格存的是物理列名，
     * 故改展示名不会让已存指标失配。
     *
     * @throws BizException 指标不存在、SQL 复校失败或数据源执行失败
     */
    /**
     * 实时刷新（无下钻筛选）：重跑该指标已存 SQL，返回最新结果 + 图表规格。不消耗 LLM、不计入配额。
     */
    public MetricDataVO refresh(Long id) {
        return refresh(id, null);
    }

    /**
     * 实时刷新（支持下钻筛选，V4.44）：重跑该指标已存 SQL，并按 {@code filter} 对维度切片。
     *
     * <p>维度切片走服务端 SQL：把已校验的 {@code safeSql} 包成派生表后追加
     * {@code WHERE __src.`dim` = ?}（{@code ?} 由 JDBC 参数化绑定）。{@code dim} 必须是结果集的<b>真实列名</b>——
     * 先以一条零行探针查询取出实际列名做白名单校验，未命中即拒绝，从根本上杜绝把任意标识符拼进 SQL。
     * 语义层脱敏、字段级展示名装配与无筛选路径完全一致。
     *
     * @param filter 下钻筛选条件（{@code null}=不过滤，走原路径）
     * @throws BizException 指标不存在、维度不可下钻、SQL 复校失败或数据源执行失败
     */
    public MetricDataVO refresh(Long id, DashboardFilter filter) {
        Metric metric = require(id);
        DataSourceConfig config = dataSourceService.loadConfig(metric.getDatasourceId());
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        String safeSql = sqlValidator.validate(metric.getSqlText());
        if (filter == null) {
            QueryResult result = adapter.execute(config, safeSql, maxRows, timeoutSeconds);
            // 字段级脱敏与问数链路保持一致：按当前查看者角色对语义层敏感列置 ***
            QueryResult masked = dataMaskGate.mask(result, metric.getDatasourceId());
            markRefreshed(id);
            return new MetricDataVO(metric.getId(), metric.getName(), json.read(metric.getChartSpec()),
                    withDisplayLabels(masked, metric.getDatasourceId(), json.readColumnLabels(metric.getDisplayConfig())));
        }
        // 下钻切片：dim 白名单校验（命中实际列名）+ value 参数化绑定
        String probeSql = wrapProbe(safeSql);
        sqlValidator.validate(probeSql);   // 探针本身由已校验 SQL 派生，复校仅作纵深防御
        QueryResult probe = adapter.execute(config, probeSql, maxRows, timeoutSeconds);
        List<String> columns = probe.columns();
        if (columns == null || columns.stream().noneMatch(c -> c.equals(filter.dim()))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该指标不支持按维度「" + filter.dim() + "」下钻");
        }
        String wrappedSql = wrapFilter(safeSql, SqlDialects.of(config.type()).quote(filter.dim()));
        String validated = sqlValidator.validate(wrappedSql);
        QueryResult filtered = adapter.execute(config, validated, List.of(filter.value()), maxRows, timeoutSeconds);
        QueryResult masked = dataMaskGate.mask(filtered, metric.getDatasourceId());
        markRefreshed(id);
        return new MetricDataVO(metric.getId(), metric.getName(), json.read(metric.getChartSpec()),
                withDisplayLabels(masked, metric.getDatasourceId(), json.readColumnLabels(metric.getDisplayConfig())));
    }

    /** 包一层零行派生表，仅取列元数据（不传输数据），用于维度白名单校验。 */
    private static String wrapProbe(String safeSql) {
        return "SELECT * FROM (\n" + safeSql + "\n) __src WHERE 1 = 0";
    }

    /** 包一层派生表并按白名单维度参数化切片。{@code quotedDim} 已由方言 quote 转义，不做字符串拼接。 */
    private static String wrapFilter(String safeSql, String quotedDim) {
        return "SELECT * FROM (\n" + safeSql + "\n) __src WHERE __src." + quotedDim + " = ?";
    }

    /**
     * 记录"数据最后一次成功取数"的时间（P2 新鲜度）。只在真正取到数据之后调用，
     * 且失败一律吞掉——新鲜度是提示性信息，绝不能反噬刷新主流程。
     */
    private void markRefreshed(Long id) {
        try {
            metricMapper.update(null, Wrappers.<Metric>lambdaUpdate()
                    .eq(Metric::getId, id)
                    .set(Metric::getRefreshedAt, LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.warn("指标[{}]刷新时间回写失败（不影响本次刷新结果）：{}", id, e.getMessage());
        }
    }

    /** SLA 覆盖值归一：null 与 ≤0 均视为"未设置"（回落全局阈值），避免 0 小时让指标永远显示过期。 */
    private Integer normalizeFreshness(Integer hours) {
        return hours == null || hours <= 0 ? null : hours;
    }

    /**
     * 装配列展示名与枚举字典（指标级自定义优先、语义别名次之）；
     * 语义层异常不得拖垮指标刷新，失败时降级为物理列名。
     */
    private QueryResult withDisplayLabels(QueryResult result, Long datasourceId, Map<String, String> overrides) {
        try {
            QueryResult labeled =
                    result.withLabels(semanticService.displayLabels(datasourceId, result.columns(), overrides));
            return labeled.withEnumDicts(semanticService.enumDicts(datasourceId, result.columns()));
        } catch (RuntimeException e) {
            log.warn("指标列展示名装配失败（回落物理列名）：{}", e.getMessage());
            return result;
        }
    }

    private Metric require(Long id) {
        Metric metric = metricMapper.selectById(id);
        if (metric == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指标不存在或无权访问: " + id);
        }
        return metric;
    }

    /**
     * 移动指标到指定分组（{@code groupId} 空=移出分组）。
     *
     * <p>走专用端点而不并入 {@link #update}：指标编辑是<b>全量 set</b>，编辑器 payload 不携带分组字段，
     * 塞进去会被静默清空（{@code definition} 已经这样踩过一次）；归属变更必须是独立的窄操作。
     * 目标分组须存在且属 {@code METRIC} 域——否则能把指标挂到驾驶舱的分组下。
     */
    @Transactional
    public MetricVO moveToGroup(Long id, Long groupId) {
        require(id);
        if (groupId != null && !groupService.exists(ResourceGroupService.TYPE_METRIC, groupId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标分组不存在或无权访问: " + groupId);
        }
        metricMapper.update(null, Wrappers.<Metric>lambdaUpdate()
                .eq(Metric::getId, id)
                .set(Metric::getGroupId, groupId));
        return get(id);
    }

    private MetricVO toVO(Metric m) {
        // 单指标调用（get/create/update）：取一次 SLA 即可，无循环不放大。
        return toVO(m, aibiConfig.freshnessSlaHours());
    }

    private MetricVO toVO(Metric m, int defaultSlaHours) {
        Map<String, String> labels = json.readColumnLabels(m.getDisplayConfig());
        return new MetricVO(m.getId(), m.getName(), m.getDescription(), m.getAlias(), m.getDefinition(),
                m.getAttributionDimension(), m.getBaselineType(), m.getGroupId(), m.getFreshnessHours(), m.getDatasourceId(),
                m.getQuestion(), m.getSqlText(), json.read(m.getChartSpec()),
                labels.isEmpty() ? null : labels, m.getCreatedAt(), m.getUpdatedAt(),
                m.getRefreshedAt(), staleOf(m, defaultSlaHours), m.getRefreshedAt() == null, ageHoursOf(m),
                m.getSource());
    }

    /**
     * 新鲜度判定（P2）：SLA 取「指标级覆盖 &gt; 全局阈值」，超时即 true。
     *
     * <p><b>neverRefreshed 也算 stale</b>：从没拉过数的数据同样是"不可信"，前端按
     * {@code neverRefreshed} 区分文案（"尚未取数" vs "已过期 N 小时"），但两者都标黄。
     *
     * <p>{@code defaultSlaHours} 由调用方在循环外取一次后传入（见 {@link #list()}），
     * 避免逐指标查 sys_config 造成串行 DB 往返。
     */
    private boolean staleOf(Metric m, int defaultSlaHours) {
        if (m.getRefreshedAt() == null) {
            return true;
        }
        Integer override = normalizeFreshness(m.getFreshnessHours());
        int sla = override == null ? defaultSlaHours : override;
        return ageHoursOf(m) > sla;
    }

    /** 距今小时数（{@code refreshedAt} 为空时返回 -1，供前端区分"从未刷新"）。 */
    private long ageHoursOf(Metric m) {
        if (m.getRefreshedAt() == null) {
            return -1L;
        }
        return Duration.between(m.getRefreshedAt(), LocalDateTime.now()).toHours();
    }

    private long safeUid() {
        Long uid = UserContext.userId();
        return uid == null ? 0L : uid;
    }
}
