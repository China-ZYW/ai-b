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
 * 对话内报告拆题器纯逻辑单测：不起 Spring、不连库——{@code AibiConfig} 以 Mockito 桩门控与阈值，
 * {@code LlmRouter} 以桩替代辅助模型调用，覆盖四道门全关回落、展开生效、越界/非法/异常回落（全程 fail-open）。
 */
class ReportSplitterTest {

    private static final String AUX = "qwen-aux";

    /** 装配一个拆题器：enabled=总开关，splitLlm=是否放行 LLM 拆题，maxParts=上限，llm=模型桩。 */
    private static ReportSplitter splitter(boolean enabled, boolean splitLlm, int maxParts, LlmRouter llm) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.reportEnabled()).thenReturn(enabled);
        when(cfg.reportSplitLlmEnabled()).thenReturn(splitLlm);
        when(cfg.reportMaxParts()).thenReturn(maxParts);
        when(cfg.crossCheckModel()).thenReturn(AUX);      // 辅助模型名（cross-check-model 优先）
        return new ReportSplitter(cfg, llm);
    }

    /** 无辅助模型：cross-check-model 与 analysis-model 皆空 → 不调用 LLM。 */
    private static ReportSplitter noModelSplitter(LlmRouter llm) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.reportEnabled()).thenReturn(true);
        when(cfg.reportSplitLlmEnabled()).thenReturn(true);
        when(cfg.reportMaxParts()).thenReturn(5);
        when(cfg.crossCheckModel()).thenReturn("");
        when(cfg.aiAnalysisModel()).thenReturn("");
        return new ReportSplitter(cfg, llm);
    }

    @Test
    @DisplayName("①总开关关闭 → 原样单元素、绝不调用 LLM")
    void disabledReturnsSingle() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(false, true, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
        assertEquals("本月销售报告", out.get(0));
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("②关键词预筛：非报告型单问不命中 → 不拆、不调 LLM")
    void prefilterPassesNonReport() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(true, true, 5, llm).expand("上个月销售额是多少");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("③命中报告但 report-split=false → 不调 LLM、原样单元素")
    void gateOffSkipsLlm() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = splitter(true, false, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("④LLM 桩返回 4 段 → 展开成 4 段")
    void llmExpandsFour() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn(
                "[\"本月销售总额是多少\",\"本月销售额环比上月变化如何\",\"本月销售额TopN区域排名\",\"本月销售额日趋势\"]");
        List<String> out = splitter(true, true, 5, llm).expand("本月销售报告");
        assertEquals(4, out.size());
        assertEquals("本月销售总额是多少", out.get(0));
    }

    @Test
    @DisplayName("④′ 模型带代码围栏/前后缀噪声 → 仍截取 JSON 数组解析")
    void llmWithFenceNoise() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX)))
                .thenReturn("拆题结果：[\"问A\",\"问B\"] 以上。");
        List<String> out = splitter(true, true, 5, llm).expand("华东销售分析一下");
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("⑤LLM 返回非法 JSON → 回落单元素（fail-open）")
    void llmInvalidJsonFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn("我无法拆题");
        List<String> out = splitter(true, true, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑤′ LLM 只返回 1 句 → 视为未拆、回落单元素")
    void llmSingleElementFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenReturn("[\"就这一个分析\"]");
        List<String> out = splitter(true, true, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑤″ LLM 返回 6 句(>maxParts=5) → 越界保守回落单元素")
    void llmOverMaxPartsFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX)))
                .thenReturn("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]");
        List<String> out = splitter(true, true, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑥LLM 调用抛异常 → 回落单元素（绝不让问数报错）")
    void llmThrowsFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        when(llm.chatWithModel(anyString(), eq(AUX))).thenThrow(new RuntimeException("模型不可用"));
        List<String> out = splitter(true, true, 5, llm).expand("本月销售报告");
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("⑦无辅助模型（两个 model 皆空）→ 不调 LLM、回落单元素")
    void noAuxModelFallback() {
        LlmRouter llm = mock(LlmRouter.class);
        List<String> out = noModelSplitter(llm).expand("本月销售报告");
        assertEquals(1, out.size());
        verify(llm, never()).chatWithModel(anyString(), anyString());
    }

    @Test
    @DisplayName("⑧looksLikeReport 直测：报告/汇总/分析一下命中，普通单问不误判")
    void prefilterPredicate() {
        LlmRouter llm = mock(LlmRouter.class);
        ReportSplitter s = splitter(true, true, 5, llm);
        assertTrue(s.looksLikeReport("本月销售报告"));
        assertTrue(s.looksLikeReport("给我汇总一下各区域业绩"));
        assertTrue(s.looksLikeReport("分析一下用户增长"));
        assertFalse(s.looksLikeReport("上个月销售额是多少"));   // 普通单问不误判为报告
    }

    @Test
    @DisplayName("⑨parseSubQuestions 直测：越界返回空、非数组返回空、去空 trim")
    void parseEdgeCases() {
        LlmRouter llm = mock(LlmRouter.class);
        ReportSplitter s = splitter(true, true, 5, llm);
        assertEquals(0, s.parseSubQuestions("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]", 5).size()); // 越界
        assertEquals(0, s.parseSubQuestions("{\"k\":\"v\"}", 5).size());                          // 非数组
        assertEquals(3, s.parseSubQuestions("[\"a\", \" b \",\"\",\"c\"]", 5).size());            // 去空 trim
    }
}
