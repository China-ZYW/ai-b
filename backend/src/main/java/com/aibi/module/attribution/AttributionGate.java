package com.aibi.module.attribution;

import com.aibi.common.gate.ConfidenceGate;
import com.aibi.module.attribution.dto.AttributionVO;

/**
 * 归因置信度门控（需求 §3.2）：把「置信度 + 是否已澄清 + 澄清开关」映射为门控结果。
 *
 * <p><b>为什么单独抽成一个类</b>：它是纯函数，却决定"要不要打扰用户多问一轮"。抽出来后单测可以
 * 直接把边界钉死——本类存在的直接原因就是 {@code ATTRIBUTION / clarify-enabled} 曾长期是
 * <b>哑配置</b>（{@code AibiConfig.clarifyEnabled()} 有定义、无任何调用方，关掉开关仍会弹澄清窗，
 * 见变更记录 §47）。把"判定"与"谁调用"解耦，这类"配了不生效"才有可能被测试发现。
 *
 * <p><b>T10-2 泛化</b>：三态判定逻辑已抽到 {@link ConfidenceGate}（归因/问数口径消歧共用），
 * 本类只保留归因侧的阈值常量与 {@link AttributionVO} 字符串映射——判定行为与历史逐字节等价，
 * {@code AttributionGateTest} 零修改通过即是回归护栏。
 *
 * <p><b>三档语义</b>：
 * <ul>
 *   <li>{@code ≥60%} → {@link AttributionVO#GATE_DIRECT}：直接出结论，保持一键体验。</li>
 *   <li>{@code [30%, 60%)} → 默认 {@link AttributionVO#GATE_CLARIFY}（单轮澄清）；<b>已澄清过</b>
 *       或 <b>澄清开关关闭</b>时改为 {@code DIRECT}。</li>
 *   <li>{@code <30%} → {@link AttributionVO#GATE_BLOCK}：结论不可信，禁止自动归因。
 *       <b>不受澄清开关影响</b>——"不许出结论"与"要不要多问一轮"是两件事。</li>
 * </ul>
 */
public final class AttributionGate {

    /** 低置信度下限：＜此值禁止自动归因（硬保护）。 */
    public static final double MIN_CONFIDENCE = 0.30;

    /** 高置信度下限：≥此值直接出结论。 */
    public static final double DIRECT_CONFIDENCE = 0.60;

    private AttributionGate() {
    }

    /**
     * 计算门控结果。
     *
     * @param confidence 置信度（0~1，只由可观测信号扣分得出，绝不由模型"感觉"打分）
     * @param clarified  本次请求是否已完成单轮澄清（true 则不再回 CLARIFY，从机制上保证最多 1 轮）
     * @param clarifyOn  {@code ATTRIBUTION / clarify-enabled} 是否开启；关闭时中等区间不再弹澄清窗、直接出结论
     * @return {@link AttributionVO#GATE_DIRECT} / {@link AttributionVO#GATE_CLARIFY} / {@link AttributionVO#GATE_BLOCK}
     */
    public static String of(double confidence, boolean clarified, boolean clarifyOn) {
        // 归因侧：低于下限走 BLOCK（"结论不可信宁可不算"），阈值沿用本类常量，行为与泛化前等价
        ConfidenceGate.Decision decision = ConfidenceGate.of(
                confidence, clarified, clarifyOn, MIN_CONFIDENCE, DIRECT_CONFIDENCE, true);
        switch (decision) {
            case CLARIFY:
                return AttributionVO.GATE_CLARIFY;
            case BLOCK:
                return AttributionVO.GATE_BLOCK;
            default:
                return AttributionVO.GATE_DIRECT;
        }
    }

    /**
     * 中等置信度本应触发澄清、但被 {@code clarify-enabled} 关闭而抑制了澄清窗。
     *
     * <p>调用方据此在解读文案里补上不确定性提示：用户没被追问，不代表结论就高置信。
     *
     * @return 仅当"开关关闭且置信度落在中等区间（{@code [30%, 60%)}）"时为 {@code true}
     */
    public static boolean clarifySuppressed(double confidence, boolean clarifyOn) {
        return ConfidenceGate.clarifySuppressed(confidence, clarifyOn, MIN_CONFIDENCE, DIRECT_CONFIDENCE);
    }
}
