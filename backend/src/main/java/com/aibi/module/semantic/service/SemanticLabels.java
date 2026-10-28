package com.aibi.module.semantic.service;

import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.semantic.entity.SemanticMeta;
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

import static com.aibi.module.semantic.service.SemanticViewStore.ROLE_MEASURE;

/**
 * 别名补全协作类（T8.4 自 SemanticService 抽出）：结果列展示名与码值字典——
 * 供问数结果、历史回放与指标刷新在返回前端前统一换取"业务话"展示名。
 *
 * <p>与 {@code columnRole} 同策略：结果列就是 SQL 别名或原列名，按列名跨表匹配已启用语义行
 * （反解它来自哪张表的哪列既贵又不可靠——子查询/表达式/同名列）。
 * 沿用"无命中则不下发"约定：返回 null，调用方不下发字段，前端自然回落列名。
 */
@Component
class SemanticLabels {

    private static final Logger log = LoggerFactory.getLogger(SemanticLabels.class);

    private final SemanticViewStore store;
    private final ObjectMapper objectMapper;

    SemanticLabels(SemanticViewStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * 结果列展示名（业务别名优先、物理列名兜底）：让明细表表头与图表坐标轴/图例/系列名讲
     * "业务话"而不是 {@code sum_amount} 这类物理列名。
     *
     * <p>别名为空、别名等于列名（骨架初始化默认 alias=原名）或语义行停用时兜底列名。多列落到同一展示名时
     * （两列共用一个别名，或别名恰好等于另一列的列名），首个用别名、其余依次回落列名与"列名(2)"，
     * 保证展示名唯一——前端以列名作行数据的 key，重复会相互覆盖。
     *
     * @param datasourceId 数据源 id（可空；为空时不做任何映射）
     * @param columns      结果物理列名（可空）
     * @return 与 {@code columns} 等长同序的展示名；<b>没有任何列取到别名时返回 null</b>，
     *         调用方据此不下发该字段，前端自然回落列名
     */
    List<String> displayLabels(Long datasourceId, List<String> columns) {
        if (datasourceId == null || columns == null || columns.isEmpty()) {
            return null;
        }
        SemanticView view = store.view(datasourceId);
        if (view.columns().isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>(columns.size());
        Set<String> used = new HashSet<>();
        boolean aliased = false;
        for (String column : columns) {
            String alias = aliasOf(view, column);
            if (alias != null && used.add(alias)) {
                labels.add(alias);
                aliased = true;
            } else {
                labels.add(uniqueName(column, used));
            }
        }
        return aliased ? labels : null;
    }

    /**
     * 结果列展示名（带指标级自定义覆盖）：优先级<b>自定义 {@code overrides} &gt; 语义层别名 &gt; 物理列名</b>。
     *
     * <p>汇总/派生列（{@code SUM(amount) AS total_amount}、{@code COUNT(*) AS cnt}）在 schema 里不存在，
     * 骨架初始化扫不出语义行，因此永远拿不到别名；指标级自定义正是这类列的命名入口
     * （存于 {@code metric.display_config}）。覆盖后同样做展示名唯一化；无别名且无自定义时返回 null，
     * 与 {@link #displayLabels(Long, List)} 的"不下发冗余字段"约定一致。
     *
     * @param overrides 物理列名 → 自定义展示名（可空；空白值视为未配置，回落语义层）
     */
    List<String> displayLabels(Long datasourceId, List<String> columns, Map<String, String> overrides) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        List<String> semantic = displayLabels(datasourceId, columns);
        boolean customized = false;
        List<String> labels = new ArrayList<>(columns.size());
        Set<String> used = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            String custom = overrides == null ? null : trimToNull(overrides.get(column));
            if (custom != null) {
                customized = true;
            }
            String wanted = custom != null ? custom : (semantic == null ? column : semantic.get(i));
            labels.add(uniqueName(wanted, used));
        }
        return customized || semantic != null ? labels : null;
    }

    /**
     * 结果列的枚举字典（码值 → 中文标签），供前端把 {@code status=1} 显示成 {@code 已支付}。
     *
     * <p><b>只换显示，不换数据</b>：返回的是展示映射，导出的 Excel、AI 解读与后续聚合仍用原始码值，
     * 保证结果可追溯。与 {@link #displayLabels} 同一套"无命中则不下发"约定（返回 null，前端零改动）。
     *
     * <p>匹配口径同 {@code aliasOf}——<b>按列名跨表匹配已启用语义行</b>：结果集里的列名就是 SQL 别名，
     * 反解它来自哪张表的哪列既贵又不可靠（子查询/表达式/同名列），按列名匹配是唯一稳定口径。
     * 代价是同名列只取第一个命中的字典；需要区分时得靠给 SQL 起别名，属于语义层约定而非缺陷。
     *
     * @param datasourceId 数据源 id
     * @param columns      结果的列名（与 {@link #displayLabels} 同一批）
     * @return 「结果列名 →（码值→标签）」；无任何列命中时返回 null
     */
    Map<String, Map<String, String>> enumDicts(Long datasourceId, List<String> columns) {
        if (datasourceId == null || columns == null || columns.isEmpty()) {
            return null;
        }
        SemanticView view = store.view(datasourceId);
        if (view.columns().isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String column : columns) {
            Map<String, String> dict = enumDictOf(view, column);
            if (dict != null && !dict.isEmpty()) {
                out.put(column, dict);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 结果列的码值字典：跨表按列名匹配已启用语义行；度量列不换（换成中文会把图表类目变成文本）。 */
    private Map<String, String> enumDictOf(SemanticView view, String column) {
        if (column == null) {
            return null;
        }
        for (Map<String, SemanticMeta> cols : view.columns().values()) {
            SemanticMeta m = cols.get(column);
            if (m == null || !SemanticViewStore.enabled(m) || !SemanticViewStore.notBlank(m.getEnumLabels())) {
                continue;
            }
            // 度量列：码值替换会把数值轴上的类目变成中文文本，破坏数值语义，宁可不换。
            if (ROLE_MEASURE.equalsIgnoreCase(m.getColumnRole())) {
                continue;
            }
            Map<String, String> dict = parseEnumLabels(m.getEnumLabels());
            if (dict != null && !dict.isEmpty()) {
                return dict;
            }
        }
        return null;
    }

    /**
     * 枚举字典 JSON → {@code {码值: 标签}}；非法 JSON / 空值一律返回空 Map（静默降级，让调用方回落原样输出）。
     *
     * <p>与 {@code SemanticPromptSupplier} 的字典拼装互为逆运算：那边把字典拼成 Prompt 里的
     * 「字典:1=已支付」，这边把它还原成查表用的映射。码值统一按字符串取（JDBC 可能给 Integer/BigDecimal/String）。
     */
    private Map<String, String> parseEnumLabels(String json) {
        if (!SemanticViewStore.notBlank(json)) {
            return Map.of();
        }
        Map<?, ?> map;
        try {
            map = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("枚举字典 JSON 解析失败，按未配置处理：{}", json);
            return Map.of();
        }
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            String label = String.valueOf(v).trim();
            if (label.isEmpty()) {
                return;
            }
            out.put(String.valueOf(k).trim(), label);
        });
        return out;
    }

    /** 列的中文别名：跨表按列名匹配已启用语义行；无别名或别名与列名相同时返回 null（由调用方兜底列名）。 */
    private String aliasOf(SemanticView view, String column) {
        if (column == null) {
            return null;
        }
        for (Map<String, SemanticMeta> cols : view.columns().values()) {
            SemanticMeta m = cols.get(column);
            if (m == null || !SemanticViewStore.enabled(m) || !SemanticViewStore.notBlank(m.getAliasCn())) {
                continue;
            }
            String alias = m.getAliasCn().trim();
            if (!alias.equals(column)) {
                return alias;
            }
        }
        return null;
    }

    /** 展示名唯一化兜底：先用列名，已被占用则补 (2)/(3) 序号，并将结果登记为已用。 */
    private static String uniqueName(String column, Set<String> used) {
        if (used.add(column)) {
            return column;
        }
        for (int i = 2; ; i++) {
            String candidate = column + "(" + i + ")";
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    /** 空白归一为 null（自定义展示名未填时据此回落语义层别名）。 */
    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
