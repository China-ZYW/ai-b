package com.aibi.module.chat.distill;

/**
 * 治理问题处置请求（T11-1 二期 B 三写端点共用）。
 *
 * @param datasourceId 数据源 id（convert/dismiss 必填）
 * @param normalizedKey 归一键（convert/dismiss 必填）
 * @param signal 信号枚举名（convert/dismiss 必填）
 * @param refType 产出类型 METRIC/FEWSHOT（仅 convert）
 * @param refId 产出资源 id（仅 convert）
 * @param reason 忽略原因（仅 dismiss，可空）
 * @param id 台账行 id（仅 reopen）
 */
public record DispositionReq(Long datasourceId,
                             String normalizedKey,
                             String signal,
                             String refType,
                             Long refId,
                             String reason,
                             Long id) {
}
