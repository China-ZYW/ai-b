package com.aibi.module.alert.dto;

import java.math.BigDecimal;

/**
 * 预警归因摘要中的**一个头部贡献成员**。
 *
 * <p>只保留"看懂告警所需的最小信息"：成员名、变化量、占总变化比例。
 * 完整的维度明细（本期/基期值、占比等）请到归因面板查看——告警记录只回答"是谁"，
 * 不承载完整归因结果，避免记录表随维度基数膨胀。
 *
 * @param member        维度成员值（如"东北"）；空维度统一渲染为"(空)"
 * @param label         成员展示名（语义层别名优先，无别名回落成员值）
 * @param delta         拉动贡献 = 本期 − 基期（正=拉动，负=拖累）
 * @param share         贡献占总变化的比例（0~1）；总变化为 0 时为 null
 */
public record AlertDriverVO(
        String member,
        String label,
        BigDecimal delta,
        Double share
) {
}
