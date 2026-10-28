package com.aibi.module.chat.service;

import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结果展示装配协作类（T8.3 自 ChatService 抽出，主链路与历史回放共用）。
 *
 * <p>= 字段脱敏（{@link DataMaskGate}）+ 展示名/枚举字典装配（语义层业务别名，无别名兜底列名、
 * 码值→中文标签）：明细表表头、图表图例/系列名与 AI 解读文案统一讲"业务话"。
 * 只作用于返回体——历史快照、图表规格与质量校验用的仍是物理列名，
 * 故日后调整别名/字典不会让已存指标、快照与图表字段失配。
 * 语义层或脱敏异常不得拖垮主链路，失败时降级为原样返回。
 */
@Component
public class ResultDecorator {

    private static final Logger log = LoggerFactory.getLogger(ResultDecorator.class);

    private final SemanticService semanticService;
    private final DataMaskGate dataMaskGate;

    public ResultDecorator(SemanticService semanticService, DataMaskGate dataMaskGate) {
        this.semanticService = semanticService;
        this.dataMaskGate = dataMaskGate;
    }

    /** 脱敏 + 展示名装配（与原 ChatService 主链路 {@code withDisplayLabels(mask(...))} 同序等价）。 */
    public QueryResult decorate(QueryResult result, Long datasourceId) {
        if (result == null) {
            return null;
        }
        return withDisplayLabels(dataMaskGate.mask(result, datasourceId), datasourceId);
    }

    /**
     * 为结果装配列展示名与枚举字典：仅影响返回前端的展示（表头别名、码值→中文标签），
     * SQL、快照与图表字段身份始终是物理列名。语义层异常不得拖垮主链路，故失败时降级为无别名。
     */
    private QueryResult withDisplayLabels(QueryResult result, Long datasourceId) {
        if (result == null) {
            return null;
        }
        try {
            QueryResult labeled = result.withLabels(semanticService.displayLabels(datasourceId, result.columns()));
            // 码值替换只对明细表与图表生效，导出与解读仍用原始 rows，故只下发展示映射。
            return labeled.withEnumDicts(semanticService.enumDicts(datasourceId, result.columns()));
        } catch (RuntimeException e) {
            log.warn("列展示名装配失败（回落物理列名）：{}", e.getMessage());
            return result;
        }
    }
}
