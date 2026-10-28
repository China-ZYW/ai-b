package com.aibi.module.query.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.chart.ChartRecommender;
import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.chart.ChartType;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.query.chart.ChartTypeRegistry;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.query.entity.QueryRecord;
import com.aibi.module.query.entity.SavedQuery;
import com.aibi.module.query.mapper.QueryRecordMapper;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.aibi.module.query.processor.ResultProcessor;
import com.aibi.module.query.processor.ResultProcessorChain;
import com.aibi.module.query.template.SqlTemplateParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 高级查询服务（SQL 编辑器 P1 + 结果后处理 P3）。
 *
 * <p><b>执行内核刻意复用 {@code DirectSqlSkill} 的同一条链</b>：{@code SqlValidator}（单条只读）
 * → {@code DataMaskGate.assertTablesAllowed}（表级硬拦截）→ 适配器执行 → {@code DataMaskGate.mask}
 * （字段脱敏）。编辑器不是"新开一条数据通道"，而是给同一条内核换一个入口——这样"看不见的表"、
 * "需要脱敏的字段"在编辑器里同样逃不掉。
 *
 * <p><b>三条安全红线</b>：① 只读（{@code SqlValidator} 拒 DDL/DML/多语句）；
 * ② 参数化（模板 {@code :param} 一律 {@code PreparedStatement} 绑定，禁止拼接）；
 * ③ 脱敏唯一出口（结果必须过 {@code DataMaskGate.mask}，不另开通道）。
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** 历史列表默认条数。 */
    private static final int HISTORY_DEFAULT_LIMIT = 50;
    private static final int HISTORY_MAX_LIMIT = 200;

    /** 模板可见性：仅本人（默认，存量行 NULL 也按此处理）。 */
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    /** 模板可见性：组织共享（成员可查/可跑/可设定时，执行仍走成员自身 ACL）。 */
    public static final String VISIBILITY_SHARED = "SHARED";

    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final SavedQueryMapper savedQueryMapper;
    private final QueryRecordMapper queryRecordMapper;
    private final ChartRecommender chartRecommender;
    private final ChartTypeRegistry chartTypeRegistry;
    private final ResultProcessorChain resultProcessors;
    private final AibiConfig aibiConfig;
    private final long defaultMaxRows;
    private final int timeoutSeconds;

    public QueryService(DataSourceService dataSourceService,
                        DataSourceAdapterRegistry adapterRegistry,
                        SqlValidator sqlValidator,
                        DataMaskGate dataMaskGate,
                        SavedQueryMapper savedQueryMapper,
                        QueryRecordMapper queryRecordMapper,
                        ChartRecommender chartRecommender,
                        ChartTypeRegistry chartTypeRegistry,
                        ResultProcessorChain resultProcessors,
                        AibiConfig aibiConfig,
                        @Value("${aibi.query.max-rows:10000}") long defaultMaxRows,
                        @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.dataMaskGate = dataMaskGate;
        this.savedQueryMapper = savedQueryMapper;
        this.queryRecordMapper = queryRecordMapper;
        this.chartRecommender = chartRecommender;
        this.chartTypeRegistry = chartTypeRegistry;
        this.resultProcessors = resultProcessors;
        this.aibiConfig = aibiConfig;
        this.defaultMaxRows = defaultMaxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    // ==================== 执行 ====================

    /**
     * 执行一条 SQL（编辑器「运行」）。
     *
     * @param datasourceId 数据源
     * @param sql          待执行的只读 SQL
     * @param maxRows      行上限（null 或 <=0 取配置默认）
     * @param chartType    显式图型（null 时按结果结构推荐）
     * @param templateId   来源模板 id（来自模板运行时非空；历史埋点用）
     * @return 执行结果 + 图表规格
     */
    public QueryDtos.QueryRunVO runSql(Long datasourceId, String sql, Long maxRows,
                                       String chartType, Long templateId) {
        long t0 = System.currentTimeMillis();
        if (!StringUtils.hasText(sql)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SQL 不能为空");
        }
        DataSourceConfig config = loadOwnDatasource(datasourceId);
        String safeSql;
        try {
            // 只读护栏：拒 DDL/DML/多语句；解析失败直接抛出，不让可疑语句进入执行层
            safeSql = sqlValidator.validate(sql.trim());
        } catch (RuntimeException e) {
            // 校验未过也要留痕，便于排查"用户到底在写什么"
            writeHistory(datasourceId, sql, 0, System.currentTimeMillis() - t0, "FAILED",
                    skillCode(), chartType, templateId, null);
            throw e;
        }
        QueryResult raw = execute(config, safeSql, null, maxRows);
        return finish(raw, datasourceId, sql, t0, chartType, null, null);
    }

    /**
     * 执行一个保存的模板（{@code :param} 走 PreparedStatement 绑定）。
     *
     * @param params 参数名 → 参数值；缺参按 {@code null} 绑定（MySQL 侧表现为 NULL）
     */
    public QueryDtos.QueryRunVO runTemplate(Long templateId, Map<String, Object> params) {
        long t0 = System.currentTimeMillis();
        SavedQuery tpl = requireUsableTemplate(templateId);
        Long datasourceId = tpl.getDatasourceId();
        DataSourceConfig config = loadOwnDatasource(datasourceId);

        SqlTemplateParser.Parsed parsed = SqlTemplateParser.parse(tpl.getSqlTemplate());
        List<Object> bound = bindParams(parsed.paramNames(), params);
        String safeSql;
        try {
            safeSql = sqlValidator.validate(parsed.sql());
        } catch (RuntimeException e) {
            writeHistory(datasourceId, parsed.sql(), 0, System.currentTimeMillis() - t0, "FAILED",
                    skillCode(), null, templateId, null);
            throw e;
        }
        QueryResult raw = execute(config, safeSql, bound, null);
        return finish(raw, datasourceId, parsed.sql(), t0, null, templateId, null);
    }

    /**
     * 构造器专用执行入口：跑一条<b>已由 {@code SqlBuilder} 生成</b>的 SQL（带绑定参数）。
     *
     * <p>生成结果<b>再过一次 {@code SqlValidator}</b>：构造器这条路径虽然不接收任何自由文本标识符，
     * 但"生成出来的东西仍然是只读单条"不该由生成器的自觉来保证——第二道闸让任何未来的生成器改动
     * 都无法悄悄把写操作带进执行层。参数 {@code params} 与 {@code ?} 占位符按出现顺序一一对应，
     * 值全程走 {@code PreparedStatement}，不经过 SQL 文本。
     */
    public QueryDtos.QueryRunVO runPrepared(Long datasourceId, String generatedSql,
                                             List<Object> params, Long maxRows, String chartType,
                                             String spec) {
        long t0 = System.currentTimeMillis();
        DataSourceConfig config = loadOwnDatasource(datasourceId);
        String safeSql = sqlValidator.validate(generatedSql);
        QueryResult raw = execute(config, safeSql, params, maxRows);
        return finish(raw, datasourceId, generatedSql, t0, chartType, null, spec);
    }

    private QueryResult execute(DataSourceConfig config, String safeSql,
                               List<Object> params, Long maxRows) {
        // 编辑器是"任何人可写任意 SQL"的入口，必须先堵死系统库。
        // DataMaskGate.assertTablesAllowed 只管"租户自己隐藏的表"，管不到库内置的 system schema；
        // 而 information_schema.columns 能顺着数据源凭据扫出同实例上其他库（可能是别的租户）的表结构。
        assertNoSystemSchema(safeSql);
        // 表级授权硬拦截：手填 SQL 也可能指向被隐藏的表
        dataMaskGate.assertTablesAllowed(config.id(), safeSql);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        long rows = (maxRows == null || maxRows <= 0) ? defaultMaxRows : maxRows;
        return adapter.execute(config, safeSql, params, rows, timeoutSeconds);
    }

    /**
     * 拒绝引用数据库内置系统 schema。
     *
     * <p><b>为什么单独一道墙</b>：{@code DataMaskGate.assertTablesAllowed} 拦截的是"租户自己隐藏的表"，
     * 内置系统库不在其隐藏清单里，于是 {@code SELECT * FROM information_schema.columns} 能顺着数据源
     * 凭据扫出同实例上其他库（可能是别的租户）的表结构。编辑器让人手写任意 SQL，这条路径因此变得随手可及。
     *
     * <p><b>为什么不放进 {@code SqlValidator}</b>：那里的异常文案是"换个问法"，对着手填 SQL 的人说这句话
     * 是误导，且会顺带把 NL2SQL 链路的报错语义绑死在系统库上。
     *
     * <p><b>误伤面</b>：只认这三个词本身作为表名（{@code \b} 边界），{@code sys_config}、
     * {@code mysql_log} 这类带后缀的业务表不受影响；业务库恰好有一张叫 {@code sys} 的表时会被拦并给明确提示。
     */
    private static final Pattern SYSTEM_SCHEMA_REF = Pattern.compile(
            "(?i)(?:from|join)\\s+[`\"\\[]?[\\]\"]?\\s*"
                    + "(?:information_schema|mysql|performance_schema|sys)\\b");

    private void assertNoSystemSchema(String safeSql) {
        if (SYSTEM_SCHEMA_REF.matcher(safeSql).find()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不允许查询数据库系统库（information_schema / mysql / performance_schema / sys）");
        }
    }

    /** 收尾：脱敏 → 结果处理器链 → 图型决定 → 落历史。 */
    private QueryDtos.QueryRunVO finish(QueryResult raw, Long datasourceId, String question,
                                        long t0, String chartType, Long templateId, String specJson) {
        QueryResult masked = dataMaskGate.mask(raw, datasourceId);
        boolean maskedFlag = !masked.rows().equals(raw.rows());
        QueryResult processed = resultProcessors.process(masked,
                new ResultProcessor.RunContext(datasourceId, question, chartType, templateId));

        ChartSpec spec = decideChart(processed, chartType, datasourceId);
        String finalChart = spec == null ? null : spec.type().name();
        writeHistory(datasourceId, question, processed.rowCount(), System.currentTimeMillis() - t0,
                "OK", skillCode(), finalChart, templateId, specJson);
        return new QueryDtos.QueryRunVO(processed, spec, skillCode(), maskedFlag, templateId);
    }

    /**
     * 决定图型。
     *
     * <p><b>为什么"先自动推荐、再按需换型"而不是直接指定</b>：自动推荐这一步同时给出了字段绑定
     * （哪个列当 X、哪几个当 Y、哪个当系列），这是推荐引擎里最花算力的部分；用户换图型时应该
     * <b>保留绑定、只换渲染形态</b>，而不是什么都不猜。{@code ChartSpec#withType} 正是为此存在。
     *
     * <p>显式图型由前端下拉传入（值即 {@code ChartType} 编码），不依赖图表关键词规则匹配，
     * 因此行为完全确定性。
     */
    private ChartSpec decideChart(QueryResult data, String chartType, Long datasourceId) {
        if (data == null || data.columns() == null || data.columns().isEmpty()
                || data.rows() == null || data.rows().isEmpty()) {
            return ChartSpec.table("查询结果为空，使用明细表");
        }
        ChartSpec base;
        try {
            base = chartRecommender.recommendNoAi(data, null, datasourceId);
        } catch (RuntimeException e) {
            log.warn("图型推荐失败，回落明细表：{}", e.getMessage());
            return ChartSpec.table("图型推荐失败，使用明细表");
        }
        if (base == null) {
            return ChartSpec.table("无法识别结果结构，使用明细表");
        }
        if (!StringUtils.hasText(chartType)) {
            return base;
        }
        ChartType wanted = parseChartType(chartType);
        if (wanted == null || wanted == base.type() || wanted == ChartType.TABLE) {
            return base;
        }
        return base.withType(wanted);
    }

    /** 图型编码 → 枚举（同时接受编码与中文名，前端兼容两者）。 */
    private ChartType parseChartType(String chartType) {
        for (ChartType t : ChartType.values()) {
            if (t.name().equalsIgnoreCase(chartType) || t.displayName().equals(chartType)) {
                return t;
            }
        }
        return null;
    }

    // ==================== 模板 CRUD ====================

    /** 保存模板（参数名从模板自动解析，前端无需回传）。 */
    public Long saveTemplate(QueryDtos.SavedQueryCreateCmd cmd, Long tenantId, Long uid) {
        if (cmd == null || !StringUtils.hasText(cmd.name()) || !StringUtils.hasText(cmd.sqlTemplate())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称与 SQL 不能为空");
        }
        if (cmd.name().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称不能超过 64 个字符");
        }
        List<String> paramNames = SqlTemplateParser.extractParamNames(cmd.sqlTemplate());
        // 先过一次只读校验，别把一条根本跑不通的语句存成"标准答案"
        String safeSql = sqlValidator.validate(cmd.sqlTemplate().trim());
        Long dup = savedQueryMapper.selectCount(new LambdaQueryWrapper<SavedQuery>()
                .eq(SavedQuery::getTenantId, tenantId)
                .eq(SavedQuery::getUid, uid)
                .eq(SavedQuery::getName, cmd.name()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同名模板已存在，请换个名称或先删除");
        }
        SavedQuery tpl = new SavedQuery(tenantId, uid, cmd.datasourceId(), cmd.name(),
                safeSql, String.join(",", paramNames), cmd.description());
        tpl.setSpec(cmd.spec());
        savedQueryMapper.insert(tpl);
        log.info("保存查询模板 id={} tenant={} uid={} name={} params={}",
                tpl.getId(), tenantId, uid, cmd.name(), paramNames);
        return tpl.getId();
    }

    /** 模板列表：本人全部 + 组织共享（门控关时不合并）；可按数据源过滤。 */
    public List<QueryDtos.SavedQueryVO> listTemplates(Long uid, Long datasourceId) {
        LambdaQueryWrapper<SavedQuery> w = new LambdaQueryWrapper<>();
        if (aibiConfig.sharedTemplatesEnabled()) {
            w.and(q -> q.eq(SavedQuery::getUid, uid)
                    .or().eq(SavedQuery::getVisibility, VISIBILITY_SHARED));
        } else {
            w.eq(SavedQuery::getUid, uid);
        }
        w.orderByDesc(SavedQuery::getUpdatedAt);
        if (datasourceId != null) {
            w.eq(SavedQuery::getDatasourceId, datasourceId);
        }
        List<SavedQuery> rows = savedQueryMapper.selectList(w);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> dsNames = datasourceNames(rows);
        List<QueryDtos.SavedQueryVO> out = new ArrayList<>(rows.size());
        for (SavedQuery t : rows) {
            out.add(new QueryDtos.SavedQueryVO(
                    t.getId(), t.getName(), t.getDescription(), t.getDatasourceId(),
                    dsNames.get(t.getDatasourceId()), t.getSqlTemplate(),
                    t.getParamNames() == null || t.getParamNames().isBlank()
                            ? List.of() : List.of(t.getParamNames().split(",")),
                    t.getUpdatedAt(), t.getSpec(),
                    isShared(t) ? VISIBILITY_SHARED : VISIBILITY_PRIVATE, t.getUid()));
        }
        return out;
    }

    /**
     * 切换模板可见性（组织共享查询模板）：仅属主本人且为租户管理员（OWNER/ADMIN）可操作。
     *
     * <p>共享只放开"可见可跑"：编辑/删除仍走 {@link #requireOwnTemplate}；运行时的表级 ACL/脱敏
     * 按<b>使用者本人</b>上下文执行，共享 SQL 本身不升权。收回（SHARED→PRIVATE）不受门控限制，
     * 门控关闭时管理员仍可把已共享模板收回去。
     */
    public void setTemplateVisibility(Long templateId, String visibility, Long tenantId, Long uid) {
        String v = visibility == null ? "" : visibility.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VISIBILITY_PRIVATE.equals(v) && !VISIBILITY_SHARED.equals(v)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "可见性仅支持 PRIVATE / SHARED");
        }
        if (VISIBILITY_SHARED.equals(v) && !aibiConfig.sharedTemplatesEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "组织共享模板未启用（ASK / shared-templates）");
        }
        SavedQuery tpl = requireOwnTemplate(templateId);
        AccessContext ctx = AccessContextHolder.require();
        if (!ctx.canManageMembers()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅组织管理员可共享查询模板");
        }
        int updated = savedQueryMapper.update(null, new LambdaUpdateWrapper<SavedQuery>()
                .eq(SavedQuery::getId, tpl.getId())
                .set(SavedQuery::getVisibility, v));
        if (updated <= 0) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "模板不存在或无权修改");
        }
        log.info("查询模板可见性已切换 id={} tenant={} uid={} visibility={}", templateId, tenantId, uid, v);
    }

    /**
     * 覆盖式更新一个模板（改 SQL / 改名 / 换数据源 / 改说明）。
     *
     * <p>与 {@link #saveTemplate} 同构：改完的语句要重新过一次只读校验和参数解析，
     * 避免把"存的时候能跑、改完就报错"的模板留在列表里。
     *
     * <p>写入走 {@code LambdaUpdateWrapper} 精确 SET 目标列，不用 {@code updateById}——
     * 后者会把整个实体回写，把 {@code updated_at} 冻成上一次读到的旧值。
     */
    public void updateTemplate(Long templateId, QueryDtos.SavedQueryUpdateCmd cmd,
                               Long tenantId, Long uid) {
        if (cmd == null || !StringUtils.hasText(cmd.name()) || !StringUtils.hasText(cmd.sqlTemplate())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称与 SQL 不能为空");
        }
        if (cmd.name().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称不能超过 64 个字符");
        }
        SavedQuery old = requireOwnTemplate(templateId);
        Long datasourceId = cmd.datasourceId() == null ? old.getDatasourceId() : cmd.datasourceId();
        // 换数据源同样要校验归属，防止把模板挪到别人库上
        loadOwnDatasource(datasourceId);

        List<String> paramNames = SqlTemplateParser.extractParamNames(cmd.sqlTemplate());
        String safeSql = sqlValidator.validate(cmd.sqlTemplate().trim());

        Long dup = savedQueryMapper.selectCount(new LambdaQueryWrapper<SavedQuery>()
                .eq(SavedQuery::getTenantId, tenantId)
                .eq(SavedQuery::getUid, uid)
                .eq(SavedQuery::getName, cmd.name())
                .ne(SavedQuery::getId, templateId));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同名模板已存在，请换个名称");
        }

        SavedQuery patch = new SavedQuery();
        patch.setName(cmd.name());
        patch.setDatasourceId(datasourceId);
        patch.setSqlTemplate(safeSql);
        patch.setParamNames(String.join(",", paramNames));
        patch.setDescription(cmd.description());
        patch.setSpec(cmd.spec());

        int updated = savedQueryMapper.update(patch, new LambdaUpdateWrapper<SavedQuery>()
                .eq(SavedQuery::getId, templateId)
                .eq(SavedQuery::getTenantId, tenantId)
                .eq(SavedQuery::getUid, uid));
        if (updated <= 0) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "模板不存在或无权修改");
        }
        log.info("更新查询模板 id={} tenant={} uid={} params={}", templateId, tenantId, uid, paramNames);
    }

    public void deleteTemplate(Long templateId, Long tenantId, Long uid) {
        SavedQuery tpl = requireOwnTemplate(templateId);
        int deleted = savedQueryMapper.delete(new LambdaQueryWrapper<SavedQuery>()
                .eq(SavedQuery::getId, templateId)
                .eq(SavedQuery::getTenantId, tenantId)
                .eq(SavedQuery::getUid, uid));
        if (deleted <= 0) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "模板不存在或无权删除");
        }
        log.info("删除查询模板 id={} tenant={} uid={}", templateId, tenantId, uid);
    }

    // ==================== 历史 ====================

    /** 我的执行历史（只看自己最近若干条）。 */
    public List<QueryDtos.QueryRecordVO> recentHistory(Long uid, Integer limit, Long datasourceId) {
        int lim = (limit == null || limit <= 0) ? HISTORY_DEFAULT_LIMIT
                : Math.min(limit, HISTORY_MAX_LIMIT);
        LambdaQueryWrapper<QueryRecord> w = new LambdaQueryWrapper<QueryRecord>()
                .eq(QueryRecord::getUid, uid)
                .orderByDesc(QueryRecord::getId)
                .last("LIMIT " + lim);
        if (datasourceId != null) {
            w.eq(QueryRecord::getDatasourceId, datasourceId);
        }
        List<QueryRecord> rows = queryRecordMapper.selectList(w);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> dsNames = datasourceNames(rows);
        List<QueryDtos.QueryRecordVO> out = new ArrayList<>(rows.size());
        for (QueryRecord r : rows) {
            out.add(new QueryDtos.QueryRecordVO(
                    r.getId(), r.getDatasourceId(), dsNames.get(r.getDatasourceId()),
                    r.getSqlText(), r.getRowCount(), r.getElapsedMs(), r.getStatus(),
                    r.getSkillCode(), r.getChartType(), r.getCreatedAt(), r.getSpec()));
        }
        return out;
    }

    // ==================== 内部工具 ====================

    /** 数据源必须存在且属于当前租户（跨租户按"不存在"处理，不泄露其他租户数据源是否存在）。 */
    private DataSourceConfig loadOwnDatasource(Long datasourceId) {
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        Long current = TenantContext.tenantId();
        if (current != null && config.tenantId() != null && !current.equals(config.tenantId())) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源不存在或无权访问");
        }
        return config;
    }

    /** 模板必须属于当前租户 + 本人。 */
    private SavedQuery requireOwnTemplate(Long templateId) {
        SavedQuery tpl = savedQueryMapper.selectById(templateId);
        if (tpl == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        Long current = TenantContext.tenantId();
        if (current != null && tpl.getTenantId() != null && !current.equals(tpl.getTenantId())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        AccessContext ctx = AccessContextHolder.get();
        if (ctx != null && ctx.isTenantScope() && tpl.getUid() != null
                && !tpl.getUid().equals(ctx.uid())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        return tpl;
    }

    /**
     * 运行入口的归属判定：本人模板，或<b>同租户组织共享</b>模板。
     *
     * <p>共享判定<b>不检门控</b>：门控（ASK / shared-templates）只收入口面（列表合并/切换共享），
     * 已共享模板对成员保持可跑——数据权限本就按使用者自身 ACL/脱敏收敛，门控不是安全边界。
     * 调度器重跑共享模板的报表时同样经此判定（执行身份是报表属主=使用者，不是模板属主）。
     */
    private SavedQuery requireUsableTemplate(Long templateId) {
        SavedQuery tpl = savedQueryMapper.selectById(templateId);
        if (tpl == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        Long current = TenantContext.tenantId();
        if (current != null && tpl.getTenantId() != null && !current.equals(tpl.getTenantId())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        if (isShared(tpl)) {
            return tpl;
        }
        AccessContext ctx = AccessContextHolder.get();
        if (ctx != null && ctx.isTenantScope() && tpl.getUid() != null
                && !tpl.getUid().equals(ctx.uid())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "查询模板不存在");
        }
        return tpl;
    }

    /** 共享判定（null 安全）：存量行无 visibility 按 PRIVATE。 */
    public static boolean isShared(SavedQuery tpl) {
        return tpl != null && VISIBILITY_SHARED.equalsIgnoreCase(
                tpl.getVisibility() == null ? VISIBILITY_PRIVATE : tpl.getVisibility().trim());
    }

    /** 按出现顺序把参数值绑进 {@code ?} 占位符——值只经 {@code setObject}，永不进 SQL 文本。 */
    private List<Object> bindParams(List<String> names, Map<String, Object> params) {
        Map<String, Object> src = params == null ? Map.of() : params;
        List<Object> out = new ArrayList<>(names.size());
        for (String name : names) {
            Object v = src.get(name);
            out.add(v == null || v instanceof String && ((String) v).isBlank() ? null : v);
        }
        return out;
    }

    /** 收集本次用到的数据源名称（一次查询，避免逐行查库）。 */
    private Map<Long, String> datasourceNames(List<?> rows) {
        Map<Long, String> names = new HashMap<>();
        try {
            for (var vo : dataSourceService.listViewos()) {
                names.put(vo.id(), vo.name());
            }
        } catch (RuntimeException e) {
            log.debug("数据源名称解析失败，历史项仅显示 id：{}", e.getMessage());
        }
        return names;
    }

    /** 落一条执行历史（失败也记，便于复盘"用户到底想查什么"）。 */
    private void writeHistory(Long datasourceId, String sqlText, int rowCount, long elapsedMs,
                              String status, String skillCode, String chartType, Long templateId,
                              String spec) {
        try {
            AccessContext ctx = AccessContextHolder.get();
            if (ctx == null || !ctx.isTenantScope()) {
                return;
            }
            QueryRecord r = new QueryRecord();
            r.setTenantId(ctx.tenantId());
            r.setUid(ctx.uid());
            r.setDatasourceId(datasourceId);
            r.setQuestion(sqlText == null ? null : sqlText.length() > 500 ? sqlText.substring(0, 500) : sqlText);
            r.setSqlText(sqlText);
            r.setRowCount(rowCount);
            r.setElapsedMs(elapsedMs);
            r.setStatus(status);
            r.setSkillCode(skillCode);
            r.setChartType(chartType);
            r.setTemplateId(templateId);
            r.setSpec(spec);
            queryRecordMapper.insert(r);
        } catch (RuntimeException e) {
            // 埋点失败绝不影响主流程
            log.debug("查询历史落库失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 本次执行归属的技能编码。编辑器是手填 SQL 通道，与 {@code DirectSqlSkill} 同属"确定性执行"，
     * 埋点里统一记为 {@code direct_sql}，便于与 NL2SQL 链路的命中率做对比。
     */
    private String skillCode() {
        return "direct_sql";
    }
}
