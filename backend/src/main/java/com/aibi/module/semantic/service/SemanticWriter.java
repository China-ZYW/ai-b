package com.aibi.module.semantic.service;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 语义写路径协作类（T8.4 自 SemanticService 抽出）：骨架初始化（同步/懒异步）、
 * 管理页编辑/删除、枚举值采样（单列/全量）——全部写操作完成后失效视图缓存。
 *
 * <p>{@link #initSkeleton} 为全部表/列补"骨架行"（alias=原名、role=AUTO），
 * 管理员只需"补语义"而无需逐行新建。
 */
@Component
class SemanticWriter {

    private static final Logger log = LoggerFactory.getLogger(SemanticWriter.class);

    /** 标识符合法性护栏（枚举采样拼 SQL 前的注入防护）。 */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$\\u4e00-\\u9fa5]+$");

    private final SemanticViewStore store;
    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final ObjectMapper objectMapper;

    /** 已触发过骨架懒初始化的数据源（避免每次问数重复起线程）。 */
    private final Set<Long> skeletonAttempted = ConcurrentHashMap.newKeySet();

    SemanticWriter(SemanticViewStore store,
                   DataSourceService dataSourceService,
                   DataSourceAdapterRegistry adapterRegistry,
                   SchemaProvider schemaProvider,
                   ObjectMapper objectMapper) {
        this.store = store;
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 骨架初始化：为数据源全部表/列补语义骨架行（已存在则跳过），返回新增行数。
     * 别名默认取列注释/原名、角色 AUTO，管理员后续仅需补全语义。
     */
    int initSkeleton(Long datasourceId) {
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> tables = schemaProvider.getTables(adapter, config);
        // 存在性检查须覆盖全部行（含 enabled=0）且大小写不敏感，与唯一索引 uk_ds_obj 对齐
        //（MySQL utf8mb4 默认排序规则大小写不敏感）；否则重复初始化会漏判已存在行而再次 INSERT，
        // 触发 DuplicateKeyException 使整个操作 500（200 表级大 schema 尤其明显）。
        Set<String> seenTables = new HashSet<>();
        Set<String> seenColumns = new HashSet<>();
        for (SemanticMeta r : store.mapper().selectList(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId))) {
            if (SemanticViewStore.TYPE_TABLE.equals(r.getObjectType())) {
                seenTables.add(SemanticViewStore.norm(r.getTableName()));
            } else {
                seenColumns.add(SemanticViewStore.colKey(r.getTableName(), r.getColumnName()));
            }
        }
        int added = 0;
        for (TableMeta t : tables) {
            if (seenTables.add(SemanticViewStore.norm(t.name()))
                    && store.safeInsert(datasourceId, SemanticViewStore.TYPE_TABLE, t.name(), null,
                            SemanticViewStore.notBlank(t.comment()) ? t.comment() : null)) {
                added++;
            }
            for (TableMeta.ColumnMeta c : t.columns()) {
                if (seenColumns.add(SemanticViewStore.colKey(t.name(), c.name()))
                        && store.safeInsert(datasourceId, SemanticViewStore.TYPE_COLUMN, t.name(), c.name(),
                                SemanticViewStore.notBlank(c.comment()) ? c.comment() : null)) {
                    added++;
                }
            }
        }
        store.invalidate(datasourceId);
        log.info("语义骨架初始化: datasource={} 新增 {} 行", datasourceId, added);
        return added;
    }

    /**
     * 骨架懒初始化（异步）：问数链路首次用到某数据源且语义行为空时后台补齐骨架，
     * 不阻塞主链路；租户上下文经 {@link TenantContext#wrap} 传播；每数据源仅触发一次。
     */
    void asyncInitSkeleton(Long datasourceId) {
        if (datasourceId == null || !skeletonAttempted.add(datasourceId)) {
            return;
        }
        if (!store.view(datasourceId).isEmpty()) {
            return; // 已有语义数据，无需骨架
        }
        Thread worker = new Thread(TenantContext.wrap(() -> {
            try {
                if (store.buildView(datasourceId).isEmpty()) {
                    initSkeleton(datasourceId);
                }
            } catch (Exception e) {
                log.warn("语义骨架异步初始化失败: datasource={} {}", datasourceId, e.getMessage());
            }
        }), "semantic-skeleton-" + datasourceId);
        worker.setDaemon(true);
        worker.start();
    }

    /** 更新语义行（管理页编辑别名/角色/单位/同义词/口径/枚举/启用态/字段级权限）。 */
    SemanticMeta update(SemanticMeta form) {
        SemanticMeta row = store.mapper().selectById(form.getId());
        if (row == null) {
            return null;
        }
        if (form.getAliasCn() != null) {
            row.setAliasCn(form.getAliasCn());
        }
        if (form.getDescription() != null) {
            row.setDescription(form.getDescription());
        }
        if (form.getSynonyms() != null) {
            row.setSynonyms(form.getSynonyms());
        }
        if (form.getColumnRole() != null) {
            row.setColumnRole(form.getColumnRole());
        }
        if (form.getUnit() != null) {
            row.setUnit(form.getUnit());
        }
        if (form.getEnumValues() != null) {
            row.setEnumValues(form.getEnumValues());
        }
        // 枚举字典：允许手工置空（清空映射），故仅按 null 判空跳过，不打折空串语义。
        if (form.getEnumLabels() != null) {
            row.setEnumLabels(form.getEnumLabels());
        }
        if (form.getSqlExpr() != null) {
            row.setSqlExpr(form.getSqlExpr());
        }
        if (form.getEnabled() != null) {
            row.setEnabled(form.getEnabled());
        }
        // 字段级权限（P1-3）：敏感列标记 + 可见角色。可见角色允许置空串（=仅 OWNER/ADMIN 可见），
        // 故仅按 null 判空跳过，不打折空串语义。
        if (form.getSensitive() != null) {
            row.setSensitive(form.getSensitive());
        }
        if (form.getVisibleRoles() != null) {
            row.setVisibleRoles(form.getVisibleRoles());
        }
        store.mapper().updateById(row);
        store.invalidate(row.getDatasourceId());
        return row;
    }

    /** 删除语义行。 */
    boolean delete(Long id) {
        SemanticMeta row = store.mapper().selectById(id);
        if (row == null) {
            return false;
        }
        store.mapper().deleteById(id);
        store.invalidate(row.getDatasourceId());
        return true;
    }

    /**
     * 枚举值采样：对指定列执行 {@code SELECT DISTINCT ... LIMIT 20} 回填 enum_values（JSON 数组），
     * 使 Prompt 与图表能感知"状态=审核通过/驳回"这类离散取值。标识符经白名单校验防注入。
     */
    Map<String, Object> sampleEnums(Long datasourceId, String table, String column) {
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(column).matches()) {
            throw new IllegalArgumentException("非法表名/列名");
        }
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        // 标识符加反引号，避免 order/status/level/key/type/desc/group/comment 等保留字列名导致语法错误
        String sql = "SELECT DISTINCT `" + column + "` FROM `" + table + "`"
                + " WHERE `" + column + "` IS NOT NULL LIMIT 20";
        QueryResult result = adapter.execute(config, sql, 20, 15);
        List<String> values = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            if (!row.isEmpty() && row.get(0) != null) {
                values.add(String.valueOf(row.get(0)));
            }
        }
        // 回填到语义行（存在则更新，不存在则建列级行）
        SemanticMeta row = store.mapper().selectOne(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId)
                .eq(SemanticMeta::getObjectType, SemanticViewStore.TYPE_COLUMN)
                .eq(SemanticMeta::getTableName, table)
                .eq(SemanticMeta::getColumnName, column));
        // 数值码列（status/type 存 1/2/3/4 这类）的 DISTINCT 取值本身没有信息量——采到的永远是那几个
        // 码值，回填成 enum_values 只会在 Prompt 里多一行裸数字，模型照样不懂含义。故未配字典时跳过
        // 回填（连语义行都不建，避免给每个数值列都插一行空壳）；已配字典的行照常覆盖取值清单。
        boolean numeric = allNumeric(values);
        boolean skipBackfill = numeric && (row == null || !SemanticViewStore.notBlank(row.getEnumLabels()));
        if (!skipBackfill) {
            String json;
            try {
                json = objectMapper.writeValueAsString(values);
            } catch (Exception e) {
                json = "[]";
            }
            if (row == null) {
                row = store.insertRow(datasourceId, SemanticViewStore.TYPE_COLUMN, table, column, null, null);
            }
            row.setEnumValues(json);
            store.mapper().updateById(row);
        }
        store.invalidate(datasourceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("numeric", numeric);
        out.put("backfilled", !skipBackfill);
        out.put("reason", skipBackfill ? "数值码列且未配字典，已跳过回填" : "已回填取值清单");
        return out;
    }

    /** 采样值是否全为纯数字（允许正负号与单个小数点）：据此识别 status/type/level 这类数字码列。 */
    private boolean allNumeric(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String v : values) {
            if (v == null || v.isEmpty()) {
                return false;
            }
            boolean dot = false;
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c >= '0' && c <= '9') {
                    continue;
                }
                if ((c == '-' || c == '+') && i == 0) {
                    continue;
                }
                if (c == '.' && !dot) {
                    dot = true;
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 一键采样该数据源下所有维度类列的枚举值（跳过度量列，避免数值列污染枚举语义），
     * 单列出错不影响其余列，返回汇总。复用 {@link #sampleEnums} 完成逐列采样与回填。
     */
    Map<String, Object> sampleAllEnums(Long datasourceId) {
        List<SemanticMeta> cols = store.mapper().selectList(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId)
                .eq(SemanticMeta::getObjectType, SemanticViewStore.TYPE_COLUMN));
        int total = cols.size();
        int sampled = 0, empty = 0, skipped = 0, failed = 0, numeric = 0;
        for (SemanticMeta c : cols) {
            if (SemanticViewStore.ROLE_MEASURE.equals(c.getColumnRole())) {
                skipped++;
                continue;
            }
            try {
                Map<String, Object> one = sampleEnums(datasourceId, c.getTableName(), c.getColumnName());
                if (!(Boolean) one.get("backfilled")) {
                    numeric++;   // 数值码列：取值清单未回填，等管理员配字典
                }
                List<String> vals = (List<String>) one.get("values");
                if (vals != null && !vals.isEmpty()) sampled++;
                else empty++;
            } catch (Exception e) {
                failed++;
                log.warn("枚举全采样失败，跳过 {}.{}: {}", c.getTableName(), c.getColumnName(), e.getMessage());
            }
        }
        store.invalidate(datasourceId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("sampled", sampled);
        r.put("empty", empty);
        r.put("skipped", skipped);
        r.put("numeric", numeric);
        r.put("failed", failed);
        return r;
    }
}
