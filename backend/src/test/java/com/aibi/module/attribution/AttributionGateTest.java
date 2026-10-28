package com.aibi.module.attribution;

import com.aibi.module.attribution.dto.AttributionVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因置信度门控的口径与「澄清开关」的回归护栏（v3.29 / §47）。
 *
 * <p><b>为什么必须有这个测试</b>：{@code ATTRIBUTION / clarify-enabled} 曾长期是<b>哑配置</b>——
 * 后端有 {@code AibiConfig.clarifyEnabled()} 定义却<b>没有任何调用方</b>，前端也只看后端返回的
 * {@code gate}。于是"把开关关掉"在链路上完全无效：30~60% 区间照旧返回 {@code CLARIFY}、照旧弹澄清窗。
 * 这种缺陷<b>编译能过、类型检查也过</b>，只有把行为边界钉成断言才拦得住，故单列一个测试类。
 *
 * <p>两个方向都要钉住：
 * <ul>
 *   <li>关闭开关后，中等区间<b>不得再出现</b> {@code CLARIFY}（否则等于没关）；</li>
 *   <li>关闭开关<b>不得放宽</b>置信度 ＜30% 的 {@code BLOCK}——那是"结论不可信"的硬保护，
 *       与"要不要多问一轮"是两件事，误放宽会把不可信的结论直接放给用户。</li>
 * </ul>
 */
class AttributionGateTest {

    private static final boolean ON = true;
    private static final boolean OFF = false;

    @Test
    @DisplayName("高置信度（≥60%）：与澄清开关、是否已澄清都无关，一律直出")
    void highConfidenceAlwaysDirect() {
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.60, false, ON));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.60, false, OFF));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.95, false, ON));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.95, true, OFF));
    }

    @Test
    @DisplayName("中等置信度 + 开关开 + 未澄清：应触发单轮澄清")
    void midConfidenceClarifiesWhenEnabled() {
        assertEquals(AttributionVO.GATE_CLARIFY, AttributionGate.of(0.45, false, ON));
    }

    @Test
    @DisplayName("中等置信度 + 开关开 + 已澄清：直接放行（从机制上保证最多 1 轮）")
    void midConfidencePassesAfterClarified() {
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.45, true, ON));
    }

    @Test
    @DisplayName("★回归：中等置信度 + 开关关闭 + 未澄清 → 直出（不再弹澄清窗，这正是开关的语义）")
    void clarifyDisabledSuppressesClarifyWindow() {
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.45, false, OFF));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.45, true, OFF));
    }

    @Test
    @DisplayName("★回归：开关关闭时，全区间都不得返回 CLARIFY")
    void disabledNeverYieldsClarify() {
        for (int i = 0; i <= 100; i++) {
            double c = i / 100.0;
            String gate = AttributionGate.of(c, false, OFF);
            assertFalse(AttributionVO.GATE_CLARIFY.equals(gate),
                    "关闭澄清开关后仍返回 CLARIFY，置信度=" + c);
            String gateClarified = AttributionGate.of(c, true, OFF);
            assertFalse(AttributionVO.GATE_CLARIFY.equals(gateClarified),
                    "关闭澄清开关后仍返回 CLARIFY（clarified=true），置信度=" + c);
        }
    }

    @Test
    @DisplayName("低置信度（＜30%）：禁止自动归因，且不受澄清开关影响")
    void lowConfidenceAlwaysBlocks() {
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.29, false, ON));
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.29, false, OFF));
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.0, true, ON));
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.0, true, OFF));
    }

    @Test
    @DisplayName("边界值：0.30 与 0.60 的归属（下含上不含）")
    void boundaries() {
        assertEquals(AttributionVO.GATE_CLARIFY, AttributionGate.of(0.30, false, ON));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.30, false, OFF));
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.2999, false, ON));
        assertEquals(AttributionVO.GATE_BLOCK, AttributionGate.of(0.2999, false, OFF));
        assertEquals(AttributionVO.GATE_DIRECT, AttributionGate.of(0.60, false, ON));
    }

    @Test
    @DisplayName("clarifySuppressed：仅当「开关关闭 + 中等区间」为真（解读文案据此补不确定性提示）")
    void clarifySuppressedTruthTable() {
        assertTrue(AttributionGate.clarifySuppressed(0.45, OFF));
        assertTrue(AttributionGate.clarifySuppressed(0.30, OFF));
        assertFalse(AttributionGate.clarifySuppressed(0.60, OFF));
        assertFalse(AttributionGate.clarifySuppressed(0.95, OFF));
        assertFalse(AttributionGate.clarifySuppressed(0.29, OFF));
        // 开关开着时不属于"被抑制"——该弹就弹，不该由这里补提示
        assertFalse(AttributionGate.clarifySuppressed(0.45, ON));
    }
}
