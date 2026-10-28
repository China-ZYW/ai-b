package com.aibi.module.query.chart;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;

import java.util.List;
import java.util.Set;

/**
 * 图型注册表 SPI（P2，可扩展点）：声明"本平台支持哪些图型"，以及"给定结果该选哪个"。
 *
 * <p><b>为什么需要它</b>：图型清单会随前端渲染能力增长（桑基图、旭日图、关系图……），
 * 而推荐算法、前端下拉、埋点统计三处都需要同一份"权威清单"。此前这份清单散落在
 * {@code ChartType} 枚举与推荐引擎的 switch 里，加一种图要改多处。注册表把它收敛为一处：
 * <b>新增一个图型 = 加一个 {@code @Component}</b>，前端下拉自动就多一项。
 *
 * <p>内置注册表 {@link BuiltinChartTypeRegistry} 覆盖当前前端 {@code BaseChart} 已实装的全部图型
 * （折线/柱状/饼/面积/条形/热力/雷达/漏斗/仪表盘/散点/明细表），行为与既有推荐引擎完全一致。
 */
public interface ChartTypeRegistry {

    /**
     * 本注册表负责的图型集合。
     *
     * @return 不可变集合；空集合表示"本注册表不声明任何图型"（仅作监听用途）
     */
    Set<ChartTypeDef> registeredTypes();

    /**
     * 为给定结果挑一个图型。
     *
     * @param data          执行结果
     * @param hint          用户显式指定的图型编码（可选，为空表示交给实现自行判断）
     * @param datasourceId  数据源 id（列角色判定需按语义层/ACL 判断列是否为敏感度量）
     * @return 图型定义；无法决定时返回 {@code null}，由调用方回落到明细表
     */
    ChartTypeDef recommend(QueryResult data, String hint, Long datasourceId);

    /**
     * 可选的字段角色建议（供前端"拖字段到 X/系列/Y"时提示该图型需要什么）。
     *
     * @return 图型声明；不在本注册表内返回空集合
     */
    default List<FieldRole> fieldRoles(ChartTypeDef type) {
        return List.of();
    }

    /** 图型元数据：编码 + 中文名。 */
    record ChartTypeDef(String code, String displayName) {
    }

    /** 字段角色：某图型需要哪一类输入字段。 */
    record FieldRole(String code, String displayName, boolean required) {
    }
}
