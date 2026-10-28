package com.aibi.module.chat.service;

import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多意图拆分器（T10-4）纯逻辑单测：不起 Spring、不连库——{@code AibiConfig} 以 Mockito 桩门控与阈值，
 * {@code LlmRouter} 以桩替代辅助模型调用，覆盖设计稿 §7 用例 1~7 与全程 fail-open 回落（对应 §4）。
 */
class MultiIntentSplitterTest {

    private static final String AUX = "qwen-aux";

    /** 装配一个拆分器：enabled=总开关，splitLlm=是否放行 LLM 拆分，maxParts=上限，llm=模型桩。 */
    private static MultiIntentSplitter splitter(boolean enabled, boolean splitLlm, int maxParts, LlmRouter llm) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.multiIntentEnabled()).thenReturn(enabled);
        when(cfg.multiIntentSplitLlmEnabled()).thenReturn(splitLlm);
        when(cfg.multiIntentMaxParts()).thenReturn(maxParts);
        when(cfg.crossCheckModel()).thenReturn(AUX);      // 辅助模型名（cross-check-model 优先）
        return new MultiIntentSplitter(cfg, llm);
    }

    /** 无辅助模型：cross-check-model 与 analysis-model 皆空 → 不调用 LLM。 */
    private static MultiIntentSplitter noModelSplitter(LlmRouter llm) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.multiIntentEnabled()).thenReturn(true);
        when(cfg.multiIntentSplitLlmEnabled()).thenReturn(true);
        when(cfg.multiIntentMaxParts()).thenReturn(3);
        when(cfg.crossCheckModel()).thenReturn("");
        when(cfg.aiAnalysisModel()).thenReturn("");
        return new MultiIntentSplitter(cfg, llm);
    }

    @Test
    @DisplayName("①总开关关闭 → 原样单意图、绝不调用 LLM")
    void disabledReturnsSingle() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(false, true, 3, llm)
                .split("上月销售额多少，以及华东和华南哪个高");
        assertEquals(1, out.size());
        assertEquals("上月销售额多少，以及华东和华南哪个高", out.get(0));
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("②规则预筛：单意图问题不命中并列信号 → 不拆、不调 LLM")
    void rulePrefilterPassesSingle() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(true, true, 3, llm).split("上个月销售额是多少");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("③疑似多子句但 multi-intent-split=false → 不调 LLM、原样单意图")
    void gateOffSkipsLlm() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(true, false, 3, llm)
                .split("上月销售额，以及华东和华南哪个高");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("④LLM 桩返回 3 句 → 拆成 3 段")
    void llmSplitsThree() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX)))
                .thenReturn("[\"上月销售额是多少\",\"华东和华南哪个高\",\"销售额环比如何\"]");
        List<String> out = splitter(true, true, 3, llm)
                .split("上月销售额，以及华东和华南哪个高，环比如何");
        assertEquals(3, out.size());
        assertEquals("上月销售额是多少", out.get(0));
    }

    @Test
    @DisplayName("④′ 模型带代码围栏/前后缀噪声 → 仍截取 JSON 数组解析")
    void llmWithFenceNoise() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX)))
                .thenReturn("拆分结果：[\"问A\",\"问B\"] 以上。");
        List<String> out = splitter(true, true, 3, llm)
                .split("问A，另外问B");
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("⑤LLM 返回非法 JSON → 回落单意图（fail-open）")
    void llmInvalidJsonFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn("我无法拆分");
        List<String> out = splitter(true, true, 3, llm)
                .split("上月销售额，以及华东哪个高");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑤′ LLM 只返回 1 句 → 视为未拆、回落单意图")
    void llmSingleElementFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn("[\"就这一个问题\"]");
        List<String> out = splitter(true, true, 3, llm)
                .split("上月销售额，以及华东哪个高");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑤″ LLM 返回 5 句(>maxParts=3) → 越界保守回落单意图")
    void llmOverMaxPartsFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX)))
                .thenReturn("[\"a\",\"b\",\"c\",\"d\",\"e\"]");
        List<String> out = splitter(true, true, 3, llm)
                .split("上月销售额，以及华东哪个高");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑥LLM 调用抛异常 → 回落单意图（绝不让问数报错）")
    void llmThrowsFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenThrow(new RuntimeException("模型不可用"));
        List<String> out = splitter(true, true, 3, llm)
                .split("上月销售额，以及华东哪个高");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑦无辅助模型（两个 model 皆空）→ 不调 LLM、回落单意图")
    void noAuxModelFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = noModelSplitter(llm)
                .split("上月销售额，以及华东哪个高");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("⑧两个问号也判疑似多子句 → 进 LLM")
    void twoQuestionMarksTriggersPrefilter() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn("[\"销售额多少\",\"环比如何\"]");
        List<String> out = splitter(true, true, 3, llm).split("销售额多少？环比如何？");
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("⑨looksMultiClause 直测：并列词/多问号命中，普通单问不误判")
    void prefilterPredicate() {
        LlmRouter llm = mock(LlmRouter.class);
        MultiIntentSplitter s = splitter(true, true, 3, llm);
        assertTrue(s.looksMultiClause("统计A，并且统计B"));
        assertTrue(s.looksMultiClause("A是多少？B呢？"));
        assertFalse(s.looksMultiClause("各省的销售额是多少"));   // "各"不再作触发词，单意图不误判
    }

    @Test
    @DisplayName("⑩parseSubQuestions 直测：越界返回空、非数组返回空")
    void parseEdgeCases() {
        LlmRouter llm = mock(LlmRouter.class);
        MultiIntentSplitter s = splitter(true, true, 3, llm);
        assertEquals(0, s.parseSubQuestions("[\"a\",\"b\",\"c\",\"d\"]", 3).size()); // 越界
        assertEquals(0, s.parseSubQuestions("{\"k\":\"v\"}", 3).size());              // 非数组
        assertEquals(3, s.parseSubQuestions("[\"a\", \" b \",\"\",\"c\"]", 5).size());// 去空 trim
    }
}
