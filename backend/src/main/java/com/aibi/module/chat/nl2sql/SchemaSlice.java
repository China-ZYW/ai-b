package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;

import java.util.List;

/**
 * Schema 切片（§3.6 SchemaLinker 的产物）：本次问数实际进入 Prompt 的相关表集合。
 *
 * @param tables         选中进入 Prompt 的表（已按相关性排序）
 * @param totalTables    该数据源扫描到的表总数
 * @param truncated      是否发生了裁剪（total &gt; 选中数）
 * @param degraded       是否为"无任何命中退化为按序取前 N 张"的降级切片（中文问法大库场景常见，
 *                       调用方可据此回落 LLM 目录选表）
 * @param inferRelations 是否允许按命名约定推断关联（见 {@link JoinGraph}）；库里没有外键约束时
 *                       这是唯一能产出连接键的途径，由配置 {@code NL2SQL/relation-infer} 门控
 * @param extraRelations 额外注入的关联（人工在「表关联」里登记的口径），与外键 / 推断结果合并去重，
 *                       可信度最高（见 {@link TableRelation.Source}）
 */
public record SchemaSlice(List<TableMeta> tables, int totalTables, boolean truncated, boolean degraded,
                          boolean inferRelations, List<TableRelation> extraRelations) {

    public SchemaSlice {
        extraRelations = extraRelations == null ? List.of() : extraRelations;
    }

    /** 兼容构造器：未标注降级（默认非降级）。 */
    public SchemaSlice(List<TableMeta> tables, int totalTables, boolean truncated) {
        this(tables, totalTables, truncated, false, false, List.of());
    }

    /** 兼容构造器：不推断关联、无额外关联。 */
    public SchemaSlice(List<TableMeta> tables, int totalTables, boolean truncated, boolean degraded) {
        this(tables, totalTables, truncated, degraded, false, List.of());
    }

    /**
     * 换一批表但沿用本切片的关联策略（引擎合并 / 重选表后复用，避免各处重复传配置）。
     */
    public SchemaSlice withTables(List<TableMeta> newTables) {
        return new SchemaSlice(newTables, totalTables, truncated, degraded, inferRelations, extraRelations);
    }

    /** 渲染为供 LLM 理解的紧凑多行文本。 */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        for (TableMeta t : tables) {
            sb.append(t.toSchemaLine()).append("\n");
        }
        if (truncated) {
            sb.append("（已按相关性从 ").append(totalTables).append(" 张表中挑选 ")
                    .append(tables.size()).append(" 张；若这些表不足以回答，请输出 UNKNOWN）\n");
        }
        return sb.toString().trim();
    }

    /** 选中表是否为空。 */
    public boolean isEmpty() {
        return tables == null || tables.isEmpty();
    }

    /**
     * 切片内可用的关联（人工登记 + 外键 + 可选命名推断，已去重排序）。
     *
     * <p>供 JoinGuard 静态校验复用：Prompt 里告知 LLM 的连接键与执行前校验用的连接键必须是同一份，
     * 否则会出现"提示词没给这条键、校验却按它判错"的自相矛盾。
     */
    public List<TableRelation> relations() {
        return JoinGraph.relations(tables, inferRelations, extraRelations);
    }

    /**
     * 切片内可用的关联关系文本（见 {@link JoinGraph}）；无关联时返回空串。
     *
     * <p>供 Prompt 的【关联关系】段与辅助模型交叉校验复用——多表问数下这两处都必须看到连接键，
     * 否则生成端会自造 join 条件、复核端也无从判断是否错连。
     */
    public String toRelationsText() {
        return JoinGraph.relationsText(tables, relations());
    }
}
