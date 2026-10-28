package com.aibi.common.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aibi.common.gate.ConfidenceGate.Decision;

/**
 * 置信度三态门的边界护栏（T10-2 泛化自 {@code AttributionGateTest} 的判定内核）。
 *
 * <p>归因侧（{@code blockBelowMin=true}）的行为回归由<b>零修改</b>的 {@code AttributionGateTest}
 * 兜底；本测试专注泛化后新增的<b>问数侧</b>分支——低于下限不走 BLOCK 而是 DIRECT 放弃反问，
 * 以及参数化阈值后区间边界仍按「下含上不含」归属。
 */
class ConfidenceGateTest {

    private static final boolean ON = true;
    private static final boolean OFF = false;
    /** 归因/问数同源默认阈值：30% / 60%。 */
    private static final double MIN = 0.30;
    private static final double DIRECT = 0.60;

    @Test
    @DisplayName("高置信（≥directThreshold）：与开关、是否已澄清都无关，一律 DIRECT")
    void highConfidenceAlwaysDirect() {
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.60, false, ON, MIN, DIRECT, true));
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.60, false, OFF, MIN, DIRECT, false));
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.95, true, ON, MIN, DIRECT, false));
    }

    @Test
    @DisplayName("中等区间 + 开关开：未澄清 CLARIFY，已澄清 DIRECT（最多 1 轮契约）")
    void midRangeClarifyOnce() {
        assertEquals(Decision.CLARIFY, ConfidenceGate.of(0.45, false, ON, MIN, DIRECT, true));
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.45, true, ON, MIN, DIRECT, true));
    }

    @Test
    @DisplayName("中等区间 + 开关关闭：一律 DIRECT，全区间不得出现 CLARIFY")
    void disabledNeverYieldsClarify() {
        for (int i = 0; i <= 100; i++) {
            double c = i / 100.0;
            assertFalse(Decision.CLARIFY == ConfidenceGate.of(c, false, OFF, MIN, DIRECT, true),
                    "开关关闭仍返回 CLARIFY，置信度=" + c);
            assertFalse(Decision.CLARIFY == ConfidenceGate.of(c, true, OFF, MIN, DIRECT, false),
                    "开关关闭仍返回 CLARIFY（blockBelowMin=false），置信度=" + c);
        }
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.45, false, OFF, MIN, DIRECT, false));
    }

    @Test
    @DisplayName("★低于下限：blockBelowMin 决定 BLOCK（归因）还是 DIRECT（问数放弃反问）")
    void belowMinBranch() {
        // 归因：结论不可信宁可不算，且不受澄清开关影响
        assertEquals(Decision.BLOCK, ConfidenceGate.of(0.29, false, ON, MIN, DIRECT, true));
        assertEquals(Decision.BLOCK, ConfidenceGate.of(0.0, true, OFF, MIN, DIRECT, true));
        // 问数：算错不比不算更糟，低于下限放弃反问直接答，永远不 BLOCK
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.29, false, ON, MIN, DIRECT, false));
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.0, false, ON, MIN, DIRECT, false));
    }

    @Test
    @DisplayName("边界值：minThreshold 与 directThreshold 的归属（下含上不含），两侧一致")
    void boundaries() {
        assertEquals(Decision.CLARIFY, ConfidenceGate.of(0.30, false, ON, MIN, DIRECT, true));
        // 归因侧低于下限 → BLOCK（与 AttributionGateTest 0.2999→BLOCK 一致，不受开关影响）
        assertEquals(Decision.BLOCK, ConfidenceGate.of(0.2999, false, ON, MIN, DIRECT, true));
        // 问数侧低于下限 → DIRECT（放弃反问）
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.2999, false, ON, MIN, DIRECT, false));
        assertEquals(Decision.DIRECT, ConfidenceGate.of(0.60, false, ON, MIN, DIRECT, true));
        assertEquals(Decision.CLARIFY, ConfidenceGate.of(0.5999, false, ON, MIN, DIRECT, true));
    }

    @Test
    @DisplayName("clarifySuppressed：仅当「开关关闭 + 中等区间」为真")
    void clarifySuppressedTruthTable() {
        assertTrue(ConfidenceGate.clarifySuppressed(0.45, OFF, MIN, DIRECT));
        assertTrue(ConfidenceGate.clarifySuppressed(0.30, OFF, MIN, DIRECT));
        assertFalse(ConfidenceGate.clarifySuppressed(0.60, OFF, MIN, DIRECT));
        assertFalse(ConfidenceGate.clarifySuppressed(0.29, OFF, MIN, DIRECT));
        assertFalse(ConfidenceGate.clarifySuppressed(0.45, ON, MIN, DIRECT));
    }
}
