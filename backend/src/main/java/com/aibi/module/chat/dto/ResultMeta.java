package com.aibi.module.chat.dto;

/**
 * 结果完整度元信息（问数准确率与图表能力升级 V3 - 数据完整度）：让前端展示“数据可信度”，
 * 而非只有行数与耗时。由 {@code ChatService} 依引擎回填的 {@code ExecStats} + 结果组装；
 * T10-3 附溯源 {@code lineage}，T10-4b 附环比波动标记 {@code marks}（两者均可缺省 null）。
 *
 * @param truncated    结果是否被行数上限截断
 * @param totalRows    命中总行数；截断时无法精确知，为 {@code null}（前端据 truncated 显示“≥上限”）
 * @param returnedRows 实际返回行数
 * @param repaired     本次 SQL 是否经过报错自动修正
 * @param emptyRetried 是否触发过空结果自修复（放宽过滤重试）
 * @param sqlAttempts  SQL 生成/执行尝试次数
 * @param confidence   结果置信度（0~1，来自 AI 质量校验；未启用/未产生时为 {@code null}）
 * @param crossChecked 本次 SQL 是否经过辅助模型交叉校验并采纳了修正版（V5，提升问数准确率）
 * @param lineage      结果溯源元信息（T10-3：来源表清单 + 执行 SQL）；开关关闭 / 无表 / 装配失败时为 {@code null}
 * @param marks        环比/异常自动标记（T10-4b：时间序列相邻周期波动超阈值格清单 + 事实结论）；
 *                     开关关闭 / 非时间序列形态 / 无命中 / 装配失败时为 {@code null}
 */
public record ResultMeta(
        boolean truncated,
        Integer totalRows,
        int returnedRows,
        boolean repaired,
        boolean emptyRetried,
        int sqlAttempts,
        Double confidence,
        boolean crossChecked,
        Lineage lineage,
        AnomalyMarks marks
) {

    /**
     * 兼容构造器（T10-3 之前的 8 参形式）：不携带溯源与波动标记，{@code lineage}/{@code marks} 置 {@code null}。
     * 护栏响应、既有测试与不需这两块信息的构造点走此入口，签名与行为保持不变。
     */
    public ResultMeta(boolean truncated, Integer totalRows, int returnedRows, boolean repaired,
                      boolean emptyRetried, int sqlAttempts, Double confidence, boolean crossChecked) {
        this(truncated, totalRows, returnedRows, repaired, emptyRetried, sqlAttempts, confidence, crossChecked, null, null);
    }

    /**
     * 兼容构造器（T10-3 形态、T10-4b 新增 marks 前的 9 参形式）：{@code marks} 置 {@code null}。
     * T10-3 期间的测试与仅需 lineage 的构造点继续零改动。
     */
    public ResultMeta(boolean truncated, Integer totalRows, int returnedRows, boolean repaired,
                      boolean emptyRetried, int sqlAttempts, Double confidence, boolean crossChecked,
                      Lineage lineage) {
        this(truncated, totalRows, returnedRows, repaired, emptyRetried, sqlAttempts, confidence, crossChecked,
                lineage, null);
    }
}
