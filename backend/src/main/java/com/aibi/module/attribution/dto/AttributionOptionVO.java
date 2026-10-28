package com.aibi.module.attribution.dto;

import com.aibi.module.attribution.DimensionResolver;

import java.util.List;

/**
 * 归因可选项（维度候选 + 指标沉淀的归因默认值）。
 *
 * <p>P1「指标保存增强」：保存指标时一并沉淀归因默认维度与默认对比基准，
 * 下次打开该指标的归因面板时直接回填，无需用户重新选择。
 *
 * @param dimensions        可用维度（已剔除敏感列，见 {@link DimensionResolver#dimensionOptions}）
 * @param defaultDimension  指标沉淀的默认归因维度（物理列名）；未沉淀时为 null
 * @param defaultBaseline   指标沉淀的默认对比基准（MOM/YOY/PEAK）；未沉淀时为 null，前端回落 MOM
 * @param hasDefinition     指标是否已沉淀 canonical 口径；false 时前端应提示先去调优中心补口径
 */
public record AttributionOptionVO(
        List<DimensionResolver.DimensionOption> dimensions,
        String defaultDimension,
        String defaultBaseline,
        boolean hasDefinition) {
}
