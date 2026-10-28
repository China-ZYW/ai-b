package com.aibi.common.gate;

/**
 * 置信度三态门（T10-2 从归因 {@code AttributionGate} 泛化而来，归因/问数共用一套判定逻辑）。
 *
 * <p><b>为什么泛化</b>：归因的"要不要打扰用户多问一轮"与问数口径消歧的"要不要反问澄清"是同一种
 * 置信度门控，阈值与"最多 1 轮"契约完全同构。集中成一处纯函数，两侧各自注入自己的阈值与
 * "低于下限的处置"，避免判定逻辑二次实现后走样（归因历史上就吃过"哑配置不生效"的亏，见 {@code AttributionGate} 注释）。
 *
 * <p><b>三态</b>：
 * <ul>
 *   <li>{@code confidence ≥ directThreshold} → {@link Decision#DIRECT}：直接放行。</li>
 *   <li>{@code minThreshold ≤ confidence < directThreshold} → 中等区间：
 *       开关关闭({@code clarifyOn=false})或已澄清({@code clarified=true})时 {@code DIRECT}，否则 {@code CLARIFY}
 *       （{@code clarified} 从机制上保证<b>最多 1 轮</b>）。</li>
 *   <li>{@code confidence < minThreshold} → 下限以下：由 {@code blockBelowMin} 决定
 *       {@code true}→{@link Decision#BLOCK}（归因："结论不可信宁可不算"）或
 *       {@code false}→{@code DIRECT}（问数："算错不比不算更糟"，放弃反问直接答）。</li>
 * </ul>
 */
public final class ConfidenceGate {

    /** 门控决策三态。 */
    public enum Decision { DIRECT, CLARIFY, BLOCK }

    private ConfidenceGate() {
    }

    /**
     * 计算门控决策。
     *
     * @param confidence      置信度（0~1，只由可观测信号打分，绝不由模型"感觉"）
     * @param clarified       本次请求是否已完成单轮澄清（true 则中等区间不再回 CLARIFY，锁死最多 1 轮）
     * @param clarifyOn       澄清开关是否开启；关闭时中等区间不再触发澄清、直接放行
     * @param minThreshold    中等区间下限（&lt; 此值走 {@code blockBelowMin} 分支）
     * @param directThreshold 高置信下限（≥ 此值恒 DIRECT）
     * @param blockBelowMin   低于下限时 BLOCK（归因）还是 DIRECT 放弃反问（问数）
     */
    public static Decision of(double confidence, boolean clarified, boolean clarifyOn,
                              double minThreshold, double directThreshold, boolean blockBelowMin) {
        if (confidence >= directThreshold) {
            return Decision.DIRECT;
        }
        if (confidence >= minThreshold) {
            // 开关关闭：不弹澄清窗直接给结论（调用方会补不确定性提示）；已澄清过亦直接放行（杜绝无限多轮）
            if (!clarifyOn) {
                return Decision.DIRECT;
            }
            return clarified ? Decision.DIRECT : Decision.CLARIFY;
        }
        return blockBelowMin ? Decision.BLOCK : Decision.DIRECT;
    }

    /**
     * 中等区间本应触发澄清、却被 {@code clarifyOn=false} 抑制（开关关闭）。调用方据此在文案里补不确定性提示。
     */
    public static boolean clarifySuppressed(double confidence, boolean clarifyOn,
                                            double minThreshold, double directThreshold) {
        return !clarifyOn && confidence >= minThreshold && confidence < directThreshold;
    }
}
