package com.aibi.module.query.chart;

import com.aibi.module.chat.chart.ChartRecommender;
import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.chart.ChartType;
import com.aibi.module.datasource.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置图型注册表（P2）：把"支持哪些图型"收敛为一条权威清单，推荐逻辑<b>复用</b>既有
 * {@link ChartRecommender}（免 LLM 规则引擎），不另写一套——重复实现只会与既有结果漂移。
 *
 * <p>两层自检：① 推荐结果落在注册表内才采纳，否则回落明细表（防止将来某实现推荐了未声明的图型，
 * 前端渲染不出东西）；② 声明的清单即 {@code GET /api/completion/chart-types} 的下拉来源，
 * 前端无需再硬编码图型列表。
 */
@Component
public class BuiltinChartTypeRegistry implements ChartTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltinChartTypeRegistry.class);

    private static final Set<ChartTypeDef> TYPES = Arrays.stream(ChartType.values())
            .map(t -> new ChartTypeDef(t.name(), t.displayName()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    private final ChartRecommender recommender;

    public BuiltinChartTypeRegistry(ChartRecommender recommender) {
        this.recommender = recommender;
    }

    @Override
    public Set<ChartTypeDef> registeredTypes() {
        return TYPES;
    }

    @Override
    public ChartTypeDef recommend(QueryResult data, String hint, Long datasourceId) {
        // hint 同时喂给推荐引擎做"用户显式指定图型"判定（与问数链路同一语义）
        ChartSpec spec = recommender.recommendNoAi(data, hint == null ? "" : hint, datasourceId);
        if (spec == null || spec.type() == null) {
            return null;
        }
        return defOf(spec.type().name());
    }

    /**
     * 把推荐结果落成"带字段绑定的图型规格"：既返回图型，也返回该图型该拿哪些列当 X/系列/Y，
     * 前端图表选择器据此把字段拖到正确位置，不必再猜。
     */
    public ChartSpec recommendSpec(QueryResult data, String hint, Long datasourceId) {
        if (data == null || data.columns() == null || data.columns().isEmpty()) {
            return null;
        }
        ChartSpec spec = recommender.recommendNoAi(data, hint == null ? "" : hint, datasourceId);
        return spec == null ? null : spec;
    }

    @Override
    public List<FieldRole> fieldRoles(ChartTypeDef type) {
        if (type == null) {
            return List.of();
        }
        ChartType t;
        try {
            t = ChartType.valueOf(type.code().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return switch (t) {
            case TABLE -> List.of(new FieldRole("X", "分组列", false), new FieldRole("Y", "数值列", false));
            case PIE, FUNNEL, GAUGE -> List.of(
                    new FieldRole("X", "类别列", true),
                    new FieldRole("Y", "数值列", true));
            case HEATMAP -> List.of(
                    new FieldRole("X", "列维度", true),
                    new FieldRole("SERIES", "行维度", true),
                    new FieldRole("Y", "数值", true));
            case SCATTER -> List.of(
                    new FieldRole("X", "横轴数值", true),
                    new FieldRole("Y", "纵轴数值", true),
                    new FieldRole("SERIES", "系列", false));
            case MAP -> List.of(
                    new FieldRole("X", "地理维度", true),
                    new FieldRole("Y", "数值列", true));
            case RADAR -> List.of(
                    new FieldRole("X", "指标名", true),
                    new FieldRole("Y", "数值列", true),
                    new FieldRole("SERIES", "对象", false));
            case AREA, HBAR -> List.of(
                    new FieldRole("X", "维度列", true),
                    new FieldRole("Y", "数值列", true),
                    new FieldRole("SERIES", "系列", false));
            case LINE, BAR -> List.of(
                    new FieldRole("X", "维度列", true),
                    new FieldRole("Y", "数值列", true),
                    new FieldRole("SERIES", "系列", false));
        };
    }

    /** 图型编码 → 定义（不在清单内返回 null，调用方据此回落明细表）。 */
    private ChartTypeDef defOf(String code) {
        for (ChartTypeDef d : TYPES) {
            if (d.code().equalsIgnoreCase(code)) {
                return d;
            }
        }
        return null;
    }

    /** 调试用：当前清单（含中文名），供管理页/日志核对。 */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        TYPES.forEach(t -> out.add(t.code() + "=" + t.displayName()));
        return out;
    }
}
