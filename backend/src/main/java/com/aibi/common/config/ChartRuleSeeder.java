package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 图表推荐规则播种（T8.1 拆分自 {@link DataSeeder}，配置化升级 V2/V3）：
 * 关键词→图型（含 AREA/HBAR，修正"条形"误映射为竖柱）与结构阈值，
 * 与 {@code ChartRuleService.SEED_KEYWORD_RULES} 保持一致，使 DB 成为可运营事实源。
 */
@Component
public class ChartRuleSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ChartRuleSeeder.class);

    private final JdbcTemplate metaJdbc;

    public ChartRuleSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    @Override
    public String name() {
        return "chart-rules";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void seed() {
        seedChartRules();
        seedChartKeywordsV3();
    }

    private void seedChartRules() {
        Integer count = metaJdbc.queryForObject("SELECT COUNT(*) FROM chart_rule", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        int p = 10;
        insertChartKeyword("面积", "AREA", p++, "面积图（趋势+量感）");
        insertChartKeyword("条形,横向柱状,横向柱形,hbar", "HBAR", p++, "横向条形图");
        insertChartKeyword("折线,曲线,走势,趋势,line", "LINE", p++, "折线图");
        insertChartKeyword("柱状,柱形,柱图,bar", "BAR", p++, "柱状图");
        insertChartKeyword("饼图,饼状,环形,圆环,pie,donut", "PIE", p++, "饼图/环形图");
        insertChartKeyword("明细表,表格,列表,table", "TABLE", p++, "明细表");
        insertChartThreshold("max-pie-slices", 8, "饼图最大切片数");
        insertChartThreshold("max-measures", 5, "单图最多度量数");
        log.info("已播种图表推荐规则（6 关键词 + 2 阈值）");
    }

    private void insertChartKeyword(String pattern, String chartType, int priority, String desc) {
        metaJdbc.update(
                "INSERT INTO chart_rule(tenant_id, rule_type, pattern, chart_type, priority, enabled, description) "
                        + "VALUES (NULL, 'KEYWORD', ?, ?, ?, 1, ?)",
                pattern, chartType, priority, desc);
    }

    private void insertChartThreshold(String key, int value, String desc) {
        metaJdbc.update(
                "INSERT INTO chart_rule(tenant_id, rule_type, pattern, chart_type, priority, enabled, description) "
                        + "VALUES (NULL, 'THRESHOLD', ?, NULL, ?, 1, ?)",
                key, value, desc);
    }

    /**
     * 补齐 V3 新图表类型的显式关键词规则（GLOBAL，按 chart_type 幂等）：独立于 {@link #seedChartRules()} 的
     * "全表非空即跳过"，使已运行、chart_rule 已有数据的老库在启动时也能补齐 HEATMAP/MAP/RADAR/FUNNEL/GAUGE/SCATTER
     * 的关键词（与 {@code ChartRuleService.SEED_KEYWORD_RULES} 一致）。优先级 4~9 靠前，令具体图型名先于通用描述词命中。
     */
    private void seedChartKeywordsV3() {
        insertChartKeywordIfMissing("热力图,热力,矩阵", "HEATMAP", 4, "热力图/矩阵");
        insertChartKeywordIfMissing("地图,地域,省份分布,区域分布,地理分布", "MAP", 5, "地图（地理分布）");
        insertChartKeywordIfMissing("雷达图,雷达", "RADAR", 6, "雷达图");
        insertChartKeywordIfMissing("漏斗图,漏斗,转化漏斗", "FUNNEL", 7, "漏斗图（转化）");
        insertChartKeywordIfMissing("仪表盘,仪表,达成率,gauge", "GAUGE", 8, "仪表盘（达成率）");
        insertChartKeywordIfMissing("散点图,散点,气泡图,气泡,相关性,scatter", "SCATTER", 9, "散点/气泡图（相关性）");
        log.info("已校验/补齐 V3 图表类型关键词规则（按 chart_type 幂等）");
    }

    /** 按 (GLOBAL KEYWORD, chart_type) 幂等插入：该图型的关键词规则不存在才插入，供老库补齐 V3 新图型。 */
    private void insertChartKeywordIfMissing(String pattern, String chartType, int priority, String desc) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM chart_rule WHERE tenant_id IS NULL AND rule_type = 'KEYWORD' AND chart_type = ?",
                Integer.class, chartType);
        if (count != null && count > 0) {
            return;
        }
        insertChartKeyword(pattern, chartType, priority, desc);
    }
}
