package com.aibi.module.semantic.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.nl2sql.JoinGraph;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.chat.nl2sql.TableRelation;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.TableRelationDef;
import com.aibi.module.semantic.mapper.TableRelationDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表关联登记服务（语义层第三级「关系语义」）：维护人工登记的连接键，并向问数链路供给权威关联。
 *
 * <p>供给关系：{@link #relationsOf} 产出的 {@code MANUAL} 关联被引擎放进 {@code RelationHints}，
 * 与库中外键、命名推断三路合并（同键取最可信者），最终出现在 Prompt 的【关联关系】段、
 * 选表的关系扩散与静态校验里——<b>三处共用同一份口径</b>，不会出现"提示词没给这条键、校验却按它判错"。
 *
 * <p>{@link #suggest} 把系统按外键与命名约定推出来的关联列给管理员确认：一键采纳即从"推断（可能错）"
 * 升级为"人工登记（权威）"，这是治理推断误差的正规出口，也让多对多这类推不出来的基数有地方声明。
 *
 * <p>读路径按数据源缓存（TTL 同 schema 缓存），写操作即失效；{@link #relationsOf} <b>永不抛出</b>
 * ——表未就绪或查询异常时返回空列表，问数主链路退回"只有外键与推断"的行为。
 */
@Service
public class TableRelationService {

    private static final Logger log = LoggerFactory.getLogger(TableRelationService.class);

    private final TableRelationDefMapper mapper;
    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final long cacheTtlSeconds;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public TableRelationService(TableRelationDefMapper mapper,
                                DataSourceService dataSourceService,
                                DataSourceAdapterRegistry adapterRegistry,
                                SchemaProvider schemaProvider,
                                @Value("${aibi.schema.cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // ==================== 读路径 ====================

    /** 某数据源的全部登记行（管理页，含停用行）。 */
    public List<TableRelationDef> list(Long datasourceId) {
        return mapper.selectList(new LambdaQueryWrapper<TableRelationDef>()
                .eq(TableRelationDef::getDatasourceId, datasourceId)
                .orderByAsc(TableRelationDef::getLeftTable)
                .orderByAsc(TableRelationDef::getLeftColumn));
    }

    /**
     * 某数据源的人工关联（供问数链路注入 {@code RelationHints}）；带缓存、<b>永不抛出</b>。
     *
     * <p>只返回启用行。表不存在（老库尚未迁移）或查询异常时返回空列表——此时问数退回
     * "只有外键与命名推断"的行为，不会因登记表不可用而整条链路失败。
     */
    public List<TableRelation> relationsOf(Long datasourceId) {
        if (datasourceId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(datasourceId);
        if (hit != null && hit.expireAt > now) {
            return hit.relations;
        }
        List<TableRelation> relations = loadRelations(datasourceId);
        long ttl = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cache.put(datasourceId, new Cached(relations, now + ttl * 1000));
        return relations;
    }

    private List<TableRelation> loadRelations(Long datasourceId) {
        try {
            List<TableRelationDef> rows = mapper.selectList(new LambdaQueryWrapper<TableRelationDef>()
                    .eq(TableRelationDef::getDatasourceId, datasourceId)
                    .eq(TableRelationDef::getEnabled, 1));
            List<TableRelation> out = new ArrayList<>(rows.size());
            for (TableRelationDef d : rows) {
                TableRelation r = toRelation(d);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("表关联读取跳过（表未就绪）：ds={} {}", datasourceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 推荐清单：系统按外键与命名约定推出的关联，标注来源与是否已登记，供管理员一键采纳或纠正。
     *
     * <p>只推荐<b>尚未登记</b>与<b>已登记</b>两类状态的合集——已登记的带上 {@code registeredId}，
     * 前端据此显示"已登记"并可跳去编辑，避免同一条键被重复采纳。
     */
    public List<Suggestion> suggest(Long datasourceId) {
        List<TableMeta> tables = schema(datasourceId);
        if (tables.size() < 2) {
            return List.of();
        }
        Map<String, Long> registered = new HashMap<>();
        for (TableRelationDef d : list(datasourceId)) {
            TableRelation r = toRelation(d);
            if (r != null) {
                registered.put(r.pairKey(), d.getId());
            }
        }
        List<Suggestion> out = new ArrayList<>();
        // 外键与推断一起推（infer=true）：有外键的库以 FOREIGN_KEY 出现，没外键的库靠 INFERRED 兜底
        for (TableRelation r : JoinGraph.relations(tables, true)) {
            out.add(new Suggestion(r.leftTable(), r.leftColumn(), r.rightTable(), r.rightColumn(),
                    derivedType(tables, r), r.source().name(), registered.get(r.pairKey())));
        }
        return out;
    }

    // ==================== 写路径 ====================

    /**
     * 新增或更新一条登记（{@code id} 为空即新增）。
     *
     * <p>校验分两层：①硬性——四个标识符非空、基数取值合法、左右不能是"同表同列"（自连接无意义）；
     * ②尽力而为——表名与列名要真实存在于该数据源的结构里。<b>登记错的连接键比不登记更糟</b>：
     * 它以最高可信度压过外键与推断，会让 LLM 稳定地按错键 JOIN，产出"跑通但数值错"的结果。
     * 结构读不到（库连不上）时跳过第二层校验，只告警不阻断——否则库临时不可用就登记不了。
     */
    public TableRelationDef save(TableRelationDef form) {
        String lt = require(form.getLeftTable(), "左表名");
        String lc = require(form.getLeftColumn(), "左连接列");
        String rt = require(form.getRightTable(), "右表名");
        String rc = require(form.getRightColumn(), "右连接列");
        if (lt.equalsIgnoreCase(rt) && lc.equalsIgnoreCase(rc)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "左右两侧不能是同表同列（自连接无意义）");
        }
        String type = normalizeType(form.getRelationType());
        Long datasourceId = form.getDatasourceId();
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少数据源 id");
        }
        validateAgainstSchema(datasourceId, lt, lc, rt, rc);

        TableRelationDef row = form.getId() == null ? null : mapper.selectById(form.getId());
        if (row == null) {
            row = new TableRelationDef();
            row.setDatasourceId(datasourceId);
            row.setLeftTable(lt);
            row.setLeftColumn(lc);
            row.setRightTable(rt);
            row.setRightColumn(rc);
            row.setRelationType(type);
            row.setDescription(trimToNull(form.getDescription()));
            row.setEnabled(form.getEnabled() == null ? 1 : form.getEnabled());
            try {
                mapper.insert(row);   // 租户拦截器自动补 tenant_id
            } catch (DataIntegrityViolationException e) {
                // 命中唯一索引：同一对连接键已登记过（含大小写差异），按幂等处理返回既有行
                TableRelationDef exist = findSame(datasourceId, lt, lc, rt, rc);
                if (exist == null) {
                    throw e;
                }
                log.info("表关联已存在，跳过重复登记：ds={} {}.{} = {}.{}", datasourceId, lt, lc, rt, rc);
                return exist;
            }
        } else {
            row.setLeftTable(lt);
            row.setLeftColumn(lc);
            row.setRightTable(rt);
            row.setRightColumn(rc);
            row.setRelationType(type);
            if (form.getDescription() != null) {
                row.setDescription(trimToNull(form.getDescription()));
            }
            if (form.getEnabled() != null) {
                row.setEnabled(form.getEnabled());
            }
            mapper.updateById(row);
        }
        invalidate(datasourceId);
        return row;
    }

    /**
     * 全部采纳：把该数据源<b>尚未登记</b>的推荐一键落库为人工登记（已登记的跳过，绝不覆盖人工口径）。
     *
     * <p>与逐条「采纳」同口径——都走 {@link #save}，因此列名/表名的结构校验、重复键的幂等处理、
     * 缓存失效都一致；整批在一个事务里，中途任一条校验失败则整批回滚，不会留下"采纳了一半"的脏状态。
     *
     * <p>基数取推荐推导值；推不出来（{@code UNKNOWN}）时：传了合法的 {@code defaultType} 就用它，
     * 否则<b>按 UNKNOWN 保留</b>并在返回里报 {@code unknownTypes}——误判多对一会直接放大度量，
     * 留给用户确认比悄悄断言安全。
     *
     * @return {adopted=新增行数, unknownTypes=基数待确认的行数}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adoptAll(Long datasourceId, String defaultType) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少数据源 id");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int adopted = 0;
        int unknownTypes = 0;
        for (Suggestion s : suggest(datasourceId)) {
            if (s == null || s.registeredId() != null) {
                continue;   // 已登记的保留人工口径，重复采纳只会覆盖
            }
            String type = applyDefaultType(s.relationType(), defaultType);
            if ("UNKNOWN".equals(type)) {
                unknownTypes++;
            }
            TableRelationDef form = new TableRelationDef();
            form.setDatasourceId(datasourceId);
            form.setLeftTable(s.leftTable());
            form.setLeftColumn(s.leftColumn());
            form.setRightTable(s.rightTable());
            form.setRightColumn(s.rightColumn());
            form.setRelationType(type);
            form.setEnabled(1);
            save(form);
            adopted++;
        }
        out.put("adopted", adopted);
        out.put("unknownTypes", unknownTypes);
        log.info("推荐关联全部采纳：ds={} 新增={} 基数待确认={}", datasourceId, adopted, unknownTypes);
        return out;
    }

    /** 删除一条登记。 */
    public boolean delete(Long id) {
        TableRelationDef row = mapper.selectById(id);
        if (row == null) {
            return false;
        }
        mapper.deleteById(id);
        invalidate(row.getDatasourceId());
        return true;
    }

    /** 写操作后失效该数据源的关联缓存（与 {@code SemanticService.invalidate} 同约定）。 */
    public void invalidate(Long datasourceId) {
        if (datasourceId != null) {
            cache.remove(datasourceId);
        } else {
            cache.clear();
        }
    }

    // ==================== 内部 ====================

    /** 登记行 → 关联事实单元；标识符缺失或基数非法时返回 null（脏数据不进图谱）。 */
    private TableRelation toRelation(TableRelationDef d) {
        if (d == null || isBlank(d.getLeftTable()) || isBlank(d.getLeftColumn())
                || isBlank(d.getRightTable()) || isBlank(d.getRightColumn())) {
            return null;
        }
        return new TableRelation(d.getLeftTable().trim(), d.getLeftColumn().trim(),
                d.getRightTable().trim(), d.getRightColumn().trim(),
                TableRelation.Source.MANUAL, cardinalityOf(d.getRelationType()));
    }

    private TableRelation.Cardinality cardinalityOf(String type) {
        if (TableRelationDef.TYPE_ONE_TO_ONE.equalsIgnoreCase(trim(type))) {
            return TableRelation.Cardinality.ONE_TO_ONE;
        }
        if (TableRelationDef.TYPE_MANY_TO_MANY.equalsIgnoreCase(trim(type))) {
            return TableRelation.Cardinality.MANY_TO_MANY;
        }
        if (TableRelationDef.TYPE_MANY_TO_ONE.equalsIgnoreCase(trim(type))) {
            return TableRelation.Cardinality.MANY_TO_ONE;
        }
        // 未知取值按未声明处理：JoinGraph 会按"右列是否对方主键"自行推导，不会给出错误的基数断言
        return TableRelation.Cardinality.UNKNOWN;
    }

    /**
     * 基数定档：推导已给出确定值时直接用；推不出来（UNKNOWN）时，只有传了合法枚举才套用默认值，
     * 否则保留 UNKNOWN（登记行显示为"待确认"，由人工在列表里确认——误断言多对一会直接放大度量）。
     */
    private String applyDefaultType(String derived, String defaultType) {
        String d = trim(derived).toUpperCase(Locale.ROOT);
        if (!"UNKNOWN".equals(d)) {
            return d;
        }
        String t = trim(defaultType).toUpperCase(Locale.ROOT);
        if (TableRelationDef.TYPE_MANY_TO_ONE.equals(t)
                || TableRelationDef.TYPE_ONE_TO_ONE.equals(t)
                || TableRelationDef.TYPE_MANY_TO_MANY.equals(t)) {
            return t;
        }
        return "UNKNOWN";
    }

    private String normalizeType(String type) {
        String t = trim(type).toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return TableRelationDef.TYPE_MANY_TO_ONE;
        }
        if (!TableRelationDef.TYPE_MANY_TO_ONE.equals(t) && !TableRelationDef.TYPE_ONE_TO_ONE.equals(t)
                && !TableRelationDef.TYPE_MANY_TO_MANY.equals(t)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "关联基数只能是 MANY_TO_ONE / ONE_TO_ONE / MANY_TO_MANY，收到：" + type);
        }
        return t;
    }

    /** 推荐项的基数：连接列是对方主键 → 确定的多对一；否则交人工确认（前端显示"待确认"）。 */
    private String derivedType(List<TableMeta> tables, TableRelation r) {
        for (TableMeta t : tables) {
            if (t == null || !sameName(t.name(), r.rightTable())) {
                continue;
            }
            for (String pk : t.primaryKeys() == null ? List.<String>of() : t.primaryKeys()) {
                if (pk != null && pk.trim().equalsIgnoreCase(trim(r.rightColumn()))) {
                    return TableRelationDef.TYPE_MANY_TO_ONE;
                }
            }
            return "UNKNOWN";
        }
        return "UNKNOWN";
    }

    private void validateAgainstSchema(Long datasourceId, String lt, String lc, String rt, String rc) {
        List<TableMeta> tables;
        try {
            tables = schema(datasourceId);
        } catch (RuntimeException e) {
            log.warn("表关联校验跳过（读不到表结构）：ds={} {}", datasourceId, e.getMessage());
            return;
        }
        if (tables.isEmpty()) {
            log.warn("表关联校验跳过（表结构为空）：ds={}", datasourceId);
            return;
        }
        checkColumn(tables, lt, lc);
        checkColumn(tables, rt, rc);
    }

    private void checkColumn(List<TableMeta> tables, String table, String column) {
        for (TableMeta t : tables) {
            if (t == null || !sameName(t.name(), table)) {
                continue;
            }
            for (ColumnMeta c : t.columns() == null ? List.<ColumnMeta>of() : t.columns()) {
                if (c != null && c.name() != null && c.name().trim().equalsIgnoreCase(trim(column))) {
                    return;
                }
            }
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "表 " + table + " 里没有列 " + column + "，请核对后再登记");
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "数据源里没有表 " + table + "（跨库表名需带「库名.」前缀），请核对后再登记");
    }

    private TableRelationDef findSame(Long datasourceId, String lt, String lc, String rt, String rc) {
        for (TableRelationDef d : list(datasourceId)) {
            if (sameName(d.getLeftTable(), lt) && trim(d.getLeftColumn()).equalsIgnoreCase(trim(lc))
                    && sameName(d.getRightTable(), rt) && trim(d.getRightColumn()).equalsIgnoreCase(trim(rc))) {
                return d;
            }
        }
        return null;
    }

    /** 读该数据源的表结构（走 {@link SchemaProvider} 的缓存，与问数链路同源）。 */
    private List<TableMeta> schema(Long datasourceId) {
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> tables = schemaProvider.getTables(adapter, config);
        return tables == null ? List.of() : tables;
    }

    /** 表名比对：对「库.表」限定前缀与大小写不敏感（与 {@link JoinGraph} 的容错口径一致）。 */
    private static boolean sameName(String a, String b) {
        return shortOf(a).equals(shortOf(b));
    }

    private static String shortOf(String name) {
        String s = trim(name).toLowerCase(Locale.ROOT);
        int last = s.lastIndexOf('.');
        return last < 0 ? s : s.substring(last + 1);
    }

    private static String require(String value, String field) {
        String v = trim(value);
        if (v.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少" + field);
        }
        return v;
    }

    private static String trimToNull(String s) {
        String v = trim(s);
        return v.isEmpty() ? null : v;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record Cached(List<TableRelation> relations, long expireAt) {
    }

    /**
     * 一条推荐关联（{@link #suggest} 的产物）。
     *
     * @param relationType 推导出的基数；{@code UNKNOWN} 表示推不出来、需人工确认
     * @param source       {@code FOREIGN_KEY}=库中外键 / {@code INFERRED}=命名推断
     * @param registeredId 已登记时为该登记行 id，未登记为 {@code null}
     */
    public record Suggestion(String leftTable, String leftColumn, String rightTable, String rightColumn,
                             String relationType, String source, Long registeredId) {
    }
}
