package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.aibi.common.config.SeederSupport.jsonArray;

/**
 * 统一配置中心平台默认值播种（T8.1 拆分自 {@link DataSeeder}，GLOBAL scope）：
 * NL2SQL 策略与 Prompt 业务规则、图表阈值、结果展示、V3 AI 分析层、V5 归因/血缘——
 * 与 {@code AibiConfig} 代码默认一致，使管理页可见可改；租户可在 TENANT scope 覆盖。
 *
 * <p>{@link #insertGlobal} 与 {@link #insertIfMissing} 供 {@link ConfigCatalogSeeder} 复用（目录落地走同一插入语句）。
 */
@Component
public class SysConfigSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(SysConfigSeeder.class);

    private final JdbcTemplate metaJdbc;

    public SysConfigSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    @Override
    public String name() {
        return "sys-config";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void seed() {
        seedSysConfig();
        seedAiConfig();
        seedAttributionConfig();
    }

    /**
     * 播种统一配置中心的平台默认值（全表非空即跳过的"首批整批"语义）。
     * 与 {@code AibiConfig} 代码默认一致，使管理页可见可改；租户可在 TENANT scope 覆盖。
     */
    private void seedSysConfig() {
        Integer count = metaJdbc.queryForObject("SELECT COUNT(*) FROM sys_config", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        insertGlobal("NL2SQL", "prompt.rules", jsonArray(
                        "\"销售额/金额\"通常用金额字段或 单价*数量；涉及聚合必须写 GROUP BY。",
                        "涉及\"已支付/有效订单\"等语义时用对应状态字段过滤（如 status='paid'）。",
                        "多表查询必须用明确的关联键 JOIN，禁止逗号连接与笛卡尔积；"
                                + "一对多关联后要先去重或先按主表粒度在子查询里聚合，避免金额/计数被放大。"),
                "Prompt 严格要求业务规则清单");
        insertGlobal("NL2SQL", "few-shot-limit", "3", "少样本注入条数");
        insertGlobal("NL2SQL", "max-repair", "2", "SQL 执行报错自动修正最大次数");
        insertGlobal("NL2SQL", "conversation-turns", "3", "多轮对话注入历史轮数");
        insertGlobal("NL2SQL", "empty-result-repair", "true", "空结果自修复开关");
        insertGlobal("CHART", "max-pie-slices", "8", "饼图最大切片数");
        insertGlobal("CHART", "max-measures", "5", "单图最多度量数");
        insertGlobal("CHART", "value-format", "\"COMPACT\"", "图表数值格式默认 COMPACT/THOUSAND/PLAIN");
        insertGlobal("CHART", "legend-position", "\"bottom\"", "图表图例默认位置 top/bottom/left/right/hidden");
        insertGlobal("CHART", "value-decimals", "-1", "图表数值默认小数位（-1=自动）");
        insertGlobal("CHART", "auto-unit", "true", "度量单位自动推断（语义层 unit → 列名关键词）");
        insertGlobal("RESULT", "default-view", "\"chart\"", "结果面板默认视图 chart/table");
        insertGlobal("RESULT", "max-display-rows", "500", "结果面板最多展示行数");
        insertGlobal("RESULT", "number-format", "\"thousand\"", "数值格式化策略 raw/thousand");
        // 数据新鲜度（P2）：仅全新库在此播种；已运行的老库由 SchemaMigration V2026_09_freshness_config 补种
        insertGlobal("FRESHNESS", "sla-hours", "24",
                "数据新鲜度 SLA 阈值（小时）：指标数据超过该时长未刷新即标黄；指标可单独覆盖");
        log.info("已播种统一配置中心平台默认值（15 项，归因/血缘见 seedAttributionConfig）");
    }

    /**
     * 播种 V3 AI 分析层配置（GLOBAL scope，按 key 幂等）：独立于 {@link #seedSysConfig()} 的"全表非空即跳过"，
     * 使已运行、sys_config 已有数据的老库在启动时也能补齐这些新配置项（与 AibiConfig 代码默认一致）。
     */
    private void seedAiConfig() {
        insertIfMissing("AI", "chart-advisor", "true", "AI 图表决策开关（LLM 选图，规则兜底与结构校验）");
        insertIfMissing("AI", "insight", "true", "AI 结果解读开关（自然语言洞察：结论/要点/异常）");
        insertIfMissing("AI", "verify", "true", "AI 质量校验开关（是否回答问题 + 置信度）");
        insertIfMissing("AI", "analysis-model", "\"\"", "AI 分析环节模型覆盖（空=复用主问数模型）");
        insertIfMissing("NL2SQL", "self-consistency", "true", "self-consistency 多路生成择优开关");
        insertIfMissing("NL2SQL", "consistency-samples", "3", "self-consistency 候选 SQL 生成路数");
        log.info("已校验/补齐 V3 AI 分析层配置（按 key 幂等）");
    }

    /**
     * 播种 V5 归因分析 / 数据血缘配置（GLOBAL scope，按 key 幂等）。
     *
     * <p>与 {@link #seedAiConfig()} 同理：{@link #seedSysConfig()} 是"全表非空即跳过"，已运行的老库
     * （生产库 sys_config 已有 AI/CHART/NL2SQL/RESULT/FRESHNESS 行）不会走到，故这些键在没有独立
     * 播种逻辑时<b>在管理页根本看不到、也就改不了</b>。此处逐键幂等补齐，与 {@code AibiConfig} 代码默认一致，
     * 使「平台默认配置」页的归因与血缘分类开箱即用。
     */
    private void seedAttributionConfig() {
        insertIfMissing("ATTRIBUTION", "enabled", "true",
                "归因分析模块总开关（关闭后归因能力整体不可用，含调优页「维度归因」）");
        insertIfMissing("ATTRIBUTION", "result-panel", "true",
                "问数结果内展示「维度归因」面板（关闭后仅问数调优页「维度归因」可用）");
        insertIfMissing("ATTRIBUTION", "lineage-enabled", "true",
                "数据血缘模块开关（关闭后语义管理不展示/不计算指标血缘）");
        insertIfMissing("ATTRIBUTION", "clarify-enabled", "true",
                "归因置信度单轮澄清开关（关闭后 30~60% 区间不弹澄清窗，直接执行）");
        insertIfMissing("ATTRIBUTION", "sql-viewable-roles", "[]",
                "允许查看归因原始 SQL 的角色码白名单（空数组=沿用语义层可见性）");
        log.info("已校验/补齐 V5 归因与血缘配置（按 key 幂等）");
    }

    /** 插入 GLOBAL scope 配置行（version=1、enabled=1）。 */
    public void insertGlobal(String category, String key, String valueJson, String description) {
        metaJdbc.update(
                "INSERT INTO sys_config(scope_type, scope_id, category, config_key, value_json, description, version, enabled) "
                        + "VALUES ('GLOBAL', NULL, ?, ?, ?, ?, 1, 1)",
                category, key, valueJson, description);
    }

    /** 按 (GLOBAL, category, config_key) 幂等插入：不存在才插入，供老库补齐 V3 新配置。 */
    public void insertIfMissing(String category, String key, String valueJson, String description) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE scope_type = 'GLOBAL' AND category = ? AND config_key = ?",
                Integer.class, category, key);
        if (count != null && count > 0) {
            return;
        }
        insertGlobal(category, key, valueJson, description);
    }
}
