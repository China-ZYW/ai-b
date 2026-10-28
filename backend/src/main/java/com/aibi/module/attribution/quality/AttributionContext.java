package com.aibi.module.attribution.quality;

/**
 * 归因质量校验的上下文载体——把一次归因请求的可静态判定信息打包，交给 {@link AttributionQualityRule} 链检查。
 *
 * <p>设计为不可变值对象：由阶段 1 的 {@code AttributionService} 在跑归因 SQL 前组装（问题/口径/维度/基准/成员行数），
 * 规则只读取、不修改。{@code additivityKnown/additive} 允许指标预先声明口径可加性，未声明时由
 * {@code AdditivityCheckRule} 启发式兜底。
 *
 * @param question          用户原始问题（用于缺基准词识别）
 * @param definition        canonical 指标口径表达式（如 {@code SUM(amount)}，用于可加性启发式）
 * @param dimension         归因维度列名
 * @param baseline          对比基准类型：{@code MoM}/{@code YoY}/{@code peak}/空白
 * @param rowCount          本期维度成员行数（用于稀疏度判断）
 * @param additivityKnown   指标口径可加性是否已显式声明
 * @param additive          当 {@code additivityKnown=true} 时：是否为可加度量
 */
public record AttributionContext(
        String question,
        String definition,
        String dimension,
        String baseline,
        int rowCount,
        boolean additivityKnown,
        boolean additive) {

    public static AttributionContext of(String question, String definition, String dimension,
                                         String baseline, int rowCount) {
        return new AttributionContext(question, definition, dimension, baseline, rowCount, false, false);
    }

    public static AttributionContext withAdditivity(String question, String definition, String dimension,
                                                     String baseline, int rowCount, boolean additive) {
        return new AttributionContext(question, definition, dimension, baseline, rowCount, true, additive);
    }
}
