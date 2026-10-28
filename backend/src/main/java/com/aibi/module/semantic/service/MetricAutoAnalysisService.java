package com.aibi.module.semantic.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.dashboard.dto.DashboardGroupForm;
import com.aibi.module.dashboard.dto.DashboardGroupVO;
import com.aibi.module.dashboard.dto.MetricForm;
import com.aibi.module.dashboard.dto.MetricVO;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.entity.ResourceGroup;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.dashboard.mapper.ResourceGroupMapper;
import com.aibi.module.dashboard.service.MetricService;
import com.aibi.module.dashboard.service.ResourceGroupService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标后台自动分析 Skill：扫描数据源库表结构，自动识别指标 / 维度 / 时间列，
 * 在语义层打上 {@code source='AI'} 标识，并（按决策）同步自动建指标，且在指标库按「数据源 → 表」建两级目录。
 *
 * <p><b>幂等</b>：语义行按 (datasourceId, table, column) 去重（存在则仅在角色仍为 AUTO 时升级、并补 source）；
 * 指标按天然问句（question）去重（已存在则跳过）；分组按 (resourceType, name, parentId) 复用，绝不重复建树。
 *
 * <p><b>开关</b>：本 Skill 是否自动跑由 {@link AibiConfig#metricAutoAnalysisEnabled()}（METRIC / auto-analysis）控制；
 * 入口（语义管理页按钮 / init 串联）负责在开关关闭时隐藏或不串联，本类本身不强制拦截手动触发。
 */
@Service
public class MetricAutoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MetricAutoAnalysisService.class);

    static final String TYPE_TABLE = "TABLE";
    static final String TYPE_COLUMN = "COLUMN";
    static final String ROLE_AUTO = "AUTO";
    static final String ROLE_MEASURE = "MEASURE";
    static final String ROLE_DIMENSION = "DIMENSION";
    static final String ROLE_TEMPORAL = "TEMPORAL";
    static final String SRC_AI = "AI";

    /** 单表最多生成的度量指标数（避免指标爆炸）。 */
    private static final int MAX_MEASURES = 3;
    /** 单表最多生成的「各维度」分组指标数。 */
    private static final int MAX_DIMS = 3;
    /** 单表最多生成的趋势指标数（大屏图表行有限，趋势最多 2 条）。 */
    private static final int MAX_TRENDS = 2;

    private static final Set<String> NUMERIC = Set.of(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT",
            "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL", "DOUBLE PRECISION");
    private static final Set<String> TEMPORAL = Set.of("DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR");

    /**
     * 英文列名 token → 中文 词表（指标自动分析用于生成中文别名 / 中文维度别名）。
     * 仅做启发式翻译：命中则用中文，未命中 token 原样保留；含中文的列名直接原样返回。
     */
    private static final Map<String, String> CN_DICT = Map.ofEntries(
            Map.entry("total", "总"), Map.entry("sum", "合计"), Map.entry("avg", "平均"), Map.entry("average", "平均"),
            Map.entry("amount", "金额"), Map.entry("amt", "金额"), Map.entry("sales", "销售额"), Map.entry("sale", "销售"),
            Map.entry("revenue", "收入"), Map.entry("income", "收入"), Map.entry("profit", "利润"), Map.entry("cost", "成本"),
            Map.entry("fee", "费用"), Map.entry("price", "单价"), Map.entry("balance", "余额"), Map.entry("pay", "支付"),
            Map.entry("paid", "已付"), Map.entry("refund", "退款"), Map.entry("tax", "税额"),
            Map.entry("count", "数量"), Map.entry("cnt", "数量"), Map.entry("quantity", "数量"), Map.entry("qty", "数量"),
            Map.entry("num", "数量"), Map.entry("number", "数量"), Map.entry("nums", "数量"),
            Map.entry("order", "订单"), Map.entry("orders", "订单"), Map.entry("user", "用户"), Map.entry("users", "用户"),
            Map.entry("customer", "客户"), Map.entry("member", "会员"), Map.entry("vip", "会员"),
            Map.entry("product", "商品"), Map.entry("goods", "商品"), Map.entry("item", "商品"), Map.entry("sku", "商品"),
            Map.entry("category", "类别"), Map.entry("cate", "类别"), Map.entry("type", "类型"), Map.entry("kind", "种类"),
            Map.entry("brand", "品牌"), Map.entry("model", "型号"), Map.entry("spec", "规格"),
            Map.entry("status", "状态"), Map.entry("state", "状态"), Map.entry("level", "等级"), Map.entry("grade", "等级"),
            Map.entry("region", "地区"), Map.entry("province", "省份"), Map.entry("city", "城市"), Map.entry("area", "区域"),
            Map.entry("district", "区县"), Map.entry("country", "国家"), Map.entry("zone", "片区"),
            Map.entry("channel", "渠道"), Map.entry("store", "门店"), Map.entry("shop", "店铺"), Map.entry("warehouse", "仓库"),
            Map.entry("department", "部门"), Map.entry("dept", "部门"), Map.entry("team", "团队"), Map.entry("org", "组织"),
            Map.entry("date", "日期"), Map.entry("day", "日"), Map.entry("month", "月份"), Map.entry("year", "年份"),
            Map.entry("time", "时间"), Map.entry("hour", "小时"), Map.entry("week", "周"), Map.entry("quarter", "季度"),
            Map.entry("create", "创建"), Map.entry("update", "更新"), Map.entry("birth", "出生"),
            Map.entry("name", "名称"), Map.entry("title", "标题"), Map.entry("remark", "备注"), Map.entry("note", "备注"),
            Map.entry("age", "年龄"), Map.entry("gender", "性别"), Map.entry("sex", "性别"),
            Map.entry("rate", "比率"), Map.entry("ratio", "比值"), Map.entry("percent", "百分比"), Map.entry("score", "评分"),
            Map.entry("visit", "访问"), Map.entry("view", "浏览"), Map.entry("click", "点击"), Map.entry("pv", "浏览量"),
            Map.entry("uv", "访客数"), Map.entry("impression", "曝光"), Map.entry("conversion", "转化"),
            Map.entry("id", "编号"), Map.entry("no", "号"), Map.entry("code", "编码"), Map.entry("sn", "序列号"),
            Map.entry("phone", "电话"), Map.entry("mobile", "手机"), Map.entry("email", "邮箱"), Map.entry("addr", "地址"),
            Map.entry("stock", "库存"), Map.entry("inventory", "库存"), Map.entry("supply", "供货"), Map.entry("demand", "需求"),
            Map.entry("spend", "消费"), Map.entry("expense", "支出"), Map.entry("points", "积分"), Map.entry("credit", "积分"),
            Map.entry("join", "加入"), Map.entry("register", "注册"), Map.entry("login", "登录"), Map.entry("active", "活跃"),
            Map.entry("purchase", "购买"), Map.entry("buy", "购买"), Map.entry("salary", "薪资"), Map.entry("wage", "薪资"),
            Map.entry("duration", "时长"), Map.entry("energy", "能耗"), Map.entry("power", "功率"), Map.entry("speed", "速度"),
            Map.entry("temperature", "温度"), Map.entry("humidity", "湿度"), Map.entry("efficiency", "效率"),
            Map.entry("output", "产量"), Map.entry("weight", "重量"), Map.entry("distance", "距离"),
            Map.entry("frequency", "频次"), Map.entry("times", "次数")
    );
    /** ID 类列（语义上是维度而非可累加度量），不作为指标、但保留 DIMENSION 角色。 */
    private static final Pattern ID_COLUMN = Pattern.compile("(?i)^id$|.*_id$");

    /**
     * 比值/水平类列 token（年龄、比率、评分、单价、湿度等）：语义上不可跨行累加，
     * 只生成 AVG 指标（SUM 出来的「总年龄 4.5 万」是荒谬指标）。
     */
    private static final Set<String> AVG_ONLY_TOKENS = Set.of(
            "age", "rate", "ratio", "percent", "pct", "score", "price", "level",
            "star", "rating", "discount", "temperature", "humidity", "avg", "average");

    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SemanticMetaMapper semanticMetaMapper;
    private final MetricService metricService;
    private final MetricMapper metricMapper;
    private final ResourceGroupService resourceGroupService;
    private final ResourceGroupMapper resourceGroupMapper;
    private final AibiConfig aibiConfig;
    /** 后台异步线程池（提交即返回，实际扫描在异步线程跑）。 */
    private final ThreadPoolTaskExecutor analysisExecutor;
    /** 事务模板：异步线程无法依赖 @Transactional 代理（自调用失效），改用模板显式包裹。 */
    private final TransactionTemplate txTemplate;

    public MetricAutoAnalysisService(DataSourceService dataSourceService,
                                     DataSourceAdapterRegistry adapterRegistry,
                                     SemanticMetaMapper semanticMetaMapper,
                                     MetricService metricService,
                                     MetricMapper metricMapper,
                                     ResourceGroupService resourceGroupService,
                                     ResourceGroupMapper resourceGroupMapper,
                                     AibiConfig aibiConfig,
                                     ThreadPoolTaskExecutor analysisExecutor,
                                     PlatformTransactionManager txManager) {
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.semanticMetaMapper = semanticMetaMapper;
        this.metricService = metricService;
        this.metricMapper = metricMapper;
        this.resourceGroupService = resourceGroupService;
        this.resourceGroupMapper = resourceGroupMapper;
        this.aibiConfig = aibiConfig;
        this.analysisExecutor = analysisExecutor;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 对指定数据源跑一次自动分析。
     *
     * @param datasourceId 数据源 id
     * @return 分析结果摘要（扫描表数 / 打标列数 / 新建指标数 / 新建分组数）
     */
    /**
     * 同步跑一次自动分析（事务由模板包裹）。init 串联等少量需要"等结果"的调用方使用。
     */
    public AiAnalysisResult analyze(Long datasourceId) {
        return txTemplate.execute(status -> doAnalyze(datasourceId));
    }

    /**
     * 后台异步版：立即返回"已提交"，实际扫描在 {@code analysisExecutor} 线程跑，
     * 并手动把当前完整访问上下文（租户 / 用户 / 团队 / 角色）传播到异步线程——
     * 避免裸 {@code @Async} 丢失 {@code AccessContextHolder} 线程局部导致跨租户或拿不到创建者
     * （参考 {@code AuditService} 的教训）。异常只在异步线程内记录，绝不反压调用方。
     */
    public AiAnalysisResult analyzeAsync(Long datasourceId) {
        analysisExecutor.submit(TenantContext.wrap(() -> {
            try {
                txTemplate.execute(status -> doAnalyze(datasourceId));
            } catch (Exception e) {
                log.error("指标后台自动分析失败 ds={}: {}", datasourceId, e.getMessage(), e);
            }
        }));
        return new AiAnalysisResult(datasourceId, 0, 0, 0, 0, true);
    }

    /** 同步核心（扫描库表 → 打语义标 → 建指标 → 建两级目录），事务由调用方包裹。 */
    private AiAnalysisResult doAnalyze(Long datasourceId) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源 id 不能为空");
        }
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> tables = adapter.scanSchema(config);

        int tablesTagged = 0;
        int columnsTagged = 0;
        int metricsCreated = 0;
        int groupsCreated = 0;

        Long rootId = ensureRootGroup(config.name());
        for (TableMeta t : tables) {
            upsertTableRow(datasourceId, t.name(), t.comment());
            tablesTagged++;

            // 先分类全部列，再为度量选轴、建指标
            List<ColEx> cols = new ArrayList<>();
            for (TableMeta.ColumnMeta c : t.columns()) {
                String role = classify(c.type());
                boolean idCol = ID_COLUMN.matcher(c.name()).matches();
                if (idCol && ROLE_MEASURE.equals(role)) {
                    role = ROLE_DIMENSION;
                }
                upsertColumnRow(datasourceId, t.name(), c.name(), c.comment(), role);
                columnsTagged++;
                cols.add(new ColEx(c, role, idCol));
            }

            String axis = pickAxis(cols);
            boolean axisTemporal = false;
            String axisLabel = null;
            if (axis != null) {
                for (ColEx x : cols) {
                    if (axis.equals(x.col.name())) {
                        axisTemporal = ROLE_TEMPORAL.equals(x.role);
                        axisLabel = x.col.comment();
                        break;
                    }
                }
            }

            // 多种类型指标 + 各个维度指标：限量避免指标爆炸
            List<ColEx> measures = cols.stream()
                    .filter(x -> ROLE_MEASURE.equals(x.role) && !x.idCol)
                    .limit(MAX_MEASURES).toList();
            List<ColEx> dims = cols.stream()
                    .filter(x -> ROLE_DIMENSION.equals(x.role) && !x.idCol)
                    .limit(MAX_DIMS).toList();

            Long tableGroupId = null;
            if (!measures.isEmpty() || !dims.isEmpty()) {
                tableGroupId = ensureChildGroup(rootId, t.name());
                groupsCreated++;
            }

            String tableCn = cnLabel(t.comment() != null && !t.comment().isBlank() ? t.comment() : t.name());

            // ① 多类型指标：KPI（比值类列仅 AVG，其余 SUM+AVG）+ 趋势（时间轴按月聚合，每表最多 2 条）
            int trends = 0;
            for (ColEx m : measures) {
                String colCn = cnLabel(m.col.comment() != null && !m.col.comment().isBlank() ? m.col.comment() : m.col.name());
                String baseCn = stripAggPrefix(colCn);
                boolean avgOnly = avgOnlyMeasure(m);
                if (!avgOnly && createMetric(datasourceId, t.name(), tableCn, m.col.name(), colCn,
                        "SUM", null, null, "number", sumKpiName(colCn), tableGroupId, false)) metricsCreated++;
                if (createMetric(datasourceId, t.name(), tableCn, m.col.name(), colCn,
                        "AVG", null, null, "number", "平均" + baseCn, tableGroupId, false)) metricsCreated++;
                if (axis != null && trends < MAX_TRENDS) {
                    String axisCn = cnLabel(axisLabel != null && !axisLabel.isBlank() ? axisLabel : axis);
                    String agg = avgOnly ? "AVG" : "SUM";
                    String trendName = axisTemporal ? baseCn + "月度趋势" : baseCn + "趋势";
                    if (createMetric(datasourceId, t.name(), tableCn, m.col.name(), colCn,
                            agg, axis, axisCn, "line", trendName, tableGroupId, axisTemporal)) metricsCreated++;
                    trends++;
                }
            }

            // ② 各个维度指标：以主度量为基准（比值类主度量用 AVG），对每个维度生成分组柱状(排名)指标
            if (!measures.isEmpty()) {
                ColEx primary = measures.get(0);
                String pCn = cnLabel(primary.col.comment() != null && !primary.col.comment().isBlank() ? primary.col.comment() : primary.col.name());
                String pBase = stripAggPrefix(pCn);
                String pAgg = avgOnlyMeasure(primary) ? "AVG" : "SUM";
                for (ColEx d : dims) {
                    String dimCn = cnLabel(d.col.comment() != null && !d.col.comment().isBlank() ? d.col.comment() : d.col.name());
                    if (createMetric(datasourceId, t.name(), tableCn, primary.col.name(), pCn,
                            pAgg, d.col.name(), dimCn, "bar", pBase + "按" + dimCn, tableGroupId, false)) metricsCreated++;
                }
            }

            // ③ 无度量表的兜底：记录数 KPI
            if (measures.isEmpty()) {
                if (createMetric(datasourceId, t.name(), tableCn, "*", "记录", "COUNT", null, null, "number", "总记录数", tableGroupId, false)) metricsCreated++;
            }
        }
        log.info("指标自动分析完成: ds={} 表={} 列={} 指标={} 分组={}",
                datasourceId, tablesTagged, columnsTagged, metricsCreated, groupsCreated);
        return new AiAnalysisResult(datasourceId, tablesTagged, columnsTagged, metricsCreated, groupsCreated);
    }

    // ==================== 语义层打标 ====================

    private void upsertTableRow(Long datasourceId, String table, String comment) {
        SemanticMeta row = semanticMetaMapper.selectOne(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId)
                .eq(SemanticMeta::getObjectType, TYPE_TABLE)
                .eq(SemanticMeta::getTableName, table)
                .isNull(SemanticMeta::getColumnName));
        if (row == null) {
            row = new SemanticMeta();
            row.setDatasourceId(datasourceId);
            row.setObjectType(TYPE_TABLE);
            row.setTableName(table);
            row.setColumnName(null);
            row.setAliasCn(comment);
            row.setColumnRole(ROLE_AUTO);
            row.setEnabled(1);
            row.setSource(SRC_AI);
            semanticMetaMapper.insert(row);
        } else {
            row.setSource(SRC_AI);
            if (row.getAliasCn() == null && comment != null) {
                row.setAliasCn(comment);
            }
            semanticMetaMapper.updateById(row);
        }
    }

    private void upsertColumnRow(Long datasourceId, String table, String column, String comment, String role) {
        SemanticMeta row = semanticMetaMapper.selectOne(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId)
                .eq(SemanticMeta::getObjectType, TYPE_COLUMN)
                .eq(SemanticMeta::getTableName, table)
                .eq(SemanticMeta::getColumnName, column));
        if (row == null) {
            row = new SemanticMeta();
            row.setDatasourceId(datasourceId);
            row.setObjectType(TYPE_COLUMN);
            row.setTableName(table);
            row.setColumnName(column);
            row.setAliasCn(comment);
            row.setColumnRole(role);
            row.setEnabled(1);
            row.setSource(SRC_AI);
            semanticMetaMapper.insert(row);
        } else {
            // 仅当角色仍是 AUTO（未被人工编辑过）时才由 AI 升级；人工改过的保留
            if (row.getColumnRole() == null || ROLE_AUTO.equals(row.getColumnRole())) {
                row.setColumnRole(role);
            }
            row.setSource(SRC_AI);
            semanticMetaMapper.updateById(row);
        }
    }

    // ==================== 指标自动建 ====================

    /**
     * 建一个指标（幂等：天然问句去重）。
     *
     * @param col         物理列名（COUNT(*) 传 "*"）
     * @param colCn       列中文名（用于指标名 / 维度语义列）
     * @param agg         聚合函数 SUM/AVG/COUNT
     * @param axis        分组维度列名（null=单值 KPI）
     * @param axisCn      分组维度中文名（用于"中文维度别名"）
     * @param type        图表类型 number/line/bar
     * @param nameCn      指标中文名（同时作为别名）
     * @param axisMonthly 时间轴按月聚合（DATE_FORMAT %Y-%m）：天粒度几百个点会挤成噪声，月粒度 ~18 个点可读
     */
    private boolean createMetric(Long datasourceId, String table, String tableCn,
                                  String col, String colCn, String agg,
                                  String axis, String axisCn, String type, String nameCn,
                                  Long groupId, boolean axisMonthly) {
        String question = "查看" + tableCn + "的" + nameCn;
        // 天然问句去重：已存在同名问句指标则跳过（保证重跑幂等，绝不生成一模一样的指标）
        Metric dup = metricMapper.selectOne(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getDatasourceId, datasourceId)
                .eq(Metric::getQuestion, question).last("LIMIT 1"));
        if (dup != null) {
            return false;
        }

        // 口径表达式不带标识符引用符（反引号只在 sql_text 里用）：definition 会被归因外包 SUM()，
        // 反引号属归因 SQL 构建器拦截的字符，写进去会让该指标一归因就 500。
        String def = col.equals("*") ? "COUNT(*)" : agg + "(" + col + ")";
        String sql;
        Map<String, Object> chartSpec;
        Map<String, String> columnLabels;
        if (axis == null) {
            // 单值 KPI：仅一个度量列 v
            sql = "SELECT " + def + " AS `v` FROM `" + table + "`";
            chartSpec = Map.of("type", type, "yFields", List.of("v"));
            columnLabels = new LinkedHashMap<>();
            columnLabels.put("v", colCn);
        } else {
            // 分组指标：时间轴按月聚合（DATE_FORMAT %Y-%m，字典序即时间序），避免天粒度几百个点
            sql = axisMonthly
                    ? "SELECT DATE_FORMAT(`" + axis + "`, '%Y-%m') AS `dim`, " + def + " AS `v` FROM `" + table
                            + "` GROUP BY `dim` ORDER BY `dim`"
                    : "SELECT `" + axis + "` AS `dim`, " + def + " AS `v` FROM `" + table
                            + "` GROUP BY `" + axis + "` ORDER BY `" + axis + "`";
            chartSpec = Map.of("type", type, "xField", "dim", "yFields", List.of("v"));
            columnLabels = new LinkedHashMap<>();
            columnLabels.put("dim", axisCn != null ? axisCn : colCn);
            columnLabels.put("v", colCn);
        }

        String desc = "AI 自动分析「" + tableCn + "」识别的指标：" + nameCn
                + (axis != null ? "（按 " + (axisCn != null ? axisCn : axis) + " 汇总）" : "");

        MetricForm form = new MetricForm(
                nameCn,                                     // name（中文图表标题）
                desc,                                       // description（带表/维度上下文）
                nameCn,                                     // alias（中文展示别名）
                def,                                        // definition（聚合表达式）
                axis,                                       // attributionDimension：分组维度=归因维度
                "MOM",                                      // baselineType：默认环比
                null,                                       // freshnessHours
                datasourceId,
                question,
                sql,
                chartSpec,
                columnLabels,
                SRC_AI);                                    // source='AI'
        MetricVO vo = metricService.create(form);
        if (groupId != null && vo.id() != null) {
            Metric m = metricMapper.selectById(vo.id());
            if (m != null) {
                m.setGroupId(groupId);
                metricMapper.updateById(m);
            }
        }
        return true;
    }

    /** 列名可读化：纯下划线英文（如 total_amount）→ 首字母大写的空格分隔（Total Amount）；含中文/混合字符原样返回。 */
    private static String humanize(String raw) {
        if (raw == null) return "指标";
        String s = raw.trim();
        if (s.isEmpty()) return "指标";
        if (!s.matches("(?i)[a-z0-9_]+")) return s;   // 含中文 / 大写混合等，原样
        StringBuilder sb = new StringBuilder();
        for (String p : s.split("_")) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase()).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * 中文标签：用于指标名 / 指标别名 / 中文维度别名。
     * 规则：①已含中文 → 原样返回；②纯英文下划线列名 → 逐 token 查 {@link #CN_DICT} 译中文，未命中 token 原样保留。
     */
    private static String cnLabel(String raw) {
        if (raw == null) return "指标";
        String s = raw.trim();
        if (s.isEmpty()) return "指标";
        if (s.matches(".*[\\u4e00-\\u9fff].*")) return s;   // 已含中文
        StringBuilder sb = new StringBuilder();
        for (String p : s.split("[_\\s]+")) {
            if (p.isEmpty()) continue;
            String cn = CN_DICT.get(p.toLowerCase(Locale.ROOT));
            sb.append(cn != null ? cn : p);
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? "指标" : r;
    }

    /** 比值/水平类度量判定：列名任一 token 命中 {@link #AVG_ONLY_TOKENS} → 不可跨行累加，仅生成 AVG 指标。 */
    private static boolean avgOnlyMeasure(ColEx m) {
        if (m.col.name() == null) return false;
        for (String p : m.col.name().split("[_\\s]+")) {
            if (AVG_ONLY_TOKENS.contains(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** SUM KPI 名：列中文已带 总/累计/合计 前缀则不再叠加（避免「总总消费」这类前缀重复）。 */
    private static String sumKpiName(String colCn) {
        return colCn.startsWith("总") || colCn.startsWith("累计") || colCn.startsWith("合计") ? colCn : "总" + colCn;
    }

    /** 去掉聚合语义前缀（总/累计/合计），用于派生「平均X」「X月度趋势」「X按Y」等名称（「总消费」→「消费」）。 */
    private static String stripAggPrefix(String colCn) {
        for (String p : new String[]{"累计", "合计", "总"}) {
            if (colCn.startsWith(p)) return colCn.substring(p.length());
        }
        return colCn;
    }

    // ==================== 指标库目录（数据源 → 表 两级） ====================

    private Long ensureRootGroup(String dsName) {
        String rootName = "AI 分析·" + (dsName == null ? "数据源" : dsName);
        ResourceGroup existing = resourceGroupMapper.selectOne(new LambdaQueryWrapper<ResourceGroup>()
                .eq(ResourceGroup::getResourceType, ResourceGroupService.TYPE_METRIC)
                .eq(ResourceGroup::getName, rootName)
                .isNull(ResourceGroup::getParentId));
        if (existing != null) {
            return existing.getId();
        }
        DashboardGroupVO vo = resourceGroupService.create(
                ResourceGroupService.TYPE_METRIC, new DashboardGroupForm(rootName, null));
        return vo.id();
    }

    private Long ensureChildGroup(Long rootId, String table) {
        ResourceGroup existing = resourceGroupMapper.selectOne(new LambdaQueryWrapper<ResourceGroup>()
                .eq(ResourceGroup::getResourceType, ResourceGroupService.TYPE_METRIC)
                .eq(ResourceGroup::getName, table)
                .eq(ResourceGroup::getParentId, rootId));
        if (existing != null) {
            return existing.getId();
        }
        DashboardGroupVO vo = resourceGroupService.create(
                ResourceGroupService.TYPE_METRIC, new DashboardGroupForm(table, rootId));
        return vo.id();
    }

    // ==================== 分类工具 ====================

    private static String classify(String typeName) {
        if (typeName == null) {
            return ROLE_DIMENSION;
        }
        String t = typeName.trim().toUpperCase(java.util.Locale.ROOT);
        if (NUMERIC.contains(t)) {
            return ROLE_MEASURE;
        }
        if (TEMPORAL.contains(t)) {
            return ROLE_TEMPORAL;
        }
        return ROLE_DIMENSION;
    }

    /** 为度量选轴：优先时间列（趋势），否则首个非 id 维度列；都没有返回 null（做单值 KPI）。 */
    private static String pickAxis(List<ColEx> cols) {
        for (ColEx x : cols) {
            if (ROLE_TEMPORAL.equals(x.role) && !x.idCol) {
                return x.col.name();
            }
        }
        for (ColEx x : cols) {
            if (ROLE_DIMENSION.equals(x.role) && !x.idCol) {
                return x.col.name();
            }
        }
        return null;
    }

    /** 分类过程中的列扩展信息（保留原始 ColumnMeta 以便取注释 / 名称）。 */
    private static final class ColEx {
        final TableMeta.ColumnMeta col;
        final String role;
        final boolean idCol;

        ColEx(TableMeta.ColumnMeta col, String role, boolean idCol) {
            this.col = col;
            this.role = role;
            this.idCol = idCol;
        }
    }

    /** 分析结果摘要。{@code submitted=true} 表示已提交后台异步执行，计数字段此时为 0。 */
    public record AiAnalysisResult(Long datasourceId, int tables, int columns, int metrics, int groups, boolean submitted) {
        public AiAnalysisResult(Long datasourceId, int tables, int columns, int metrics, int groups) {
            this(datasourceId, tables, columns, metrics, groups, false);
        }
    }
}
