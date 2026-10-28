package com.aibi.module.chat.clarify;

import com.aibi.module.chat.dto.ClarifyProposal;
import com.aibi.module.chat.entity.TermMap;
import com.aibi.module.chat.skill.SkillIndexCache;
import com.aibi.module.chat.term.TermDictionary;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 口径歧义反问门（T10-2）纯逻辑单测：不起 Spring、不连库——{@code AibiConfig} 桩阈值与开关，
 * {@code TermDictionary}/{@code SkillIndexCache} 桩候选集，覆盖两类歧义信号、打分后果与全程 fail-open。
 *
 * <p><b>钉死的关键行为</b>：① 总开关关闭 / 已澄清 → 短路返回 null 且<b>绝不加载候选</b>（逐字节等价未接入）；
 * ② 单一信号（1.0−0.35 或 1.0−0.30）仍 ≥ 直出阈值 0.60 → DIRECT（一期保守、不误打扰）；
 * ③ 两类信号同时命中（1.0−0.65=0.35 落入 [0.30,0.60)）→ CLARIFY 且候选非空；
 * ④ 阈值可配——调低直出阈值可让单一信号即反问，无需改码；⑤ 候选加载异常 → 降级 null 不报错。
 */
class ClarifyGateTest {

    private static final Long TENANT = 1L;
    private static final Long DS = 100L;

    /** 装配一个反问门：enabled=总开关，direct/min=百分比阈值，metrics=指标候选（词典由参数单独桩）。 */
    private static Harness harness(boolean enabled, int direct, int min, int maxOptions) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.askClarifyEnabled()).thenReturn(enabled);
        when(cfg.askClarifyDirectThreshold()).thenReturn(direct);
        when(cfg.askClarifyMinThreshold()).thenReturn(min);
        when(cfg.askClarifyMaxOptions()).thenReturn(maxOptions);
        TermDictionary termDict = mock(TermDictionary.class);
        SkillIndexCache cache = mock(SkillIndexCache.class);
        MetricMapper mapper = mock(MetricMapper.class);
        ClarifyGate gate = new ClarifyGate(termDict, mapper, cache, cfg);
        return new Harness(gate, termDict, cache);
    }

    private record Harness(ClarifyGate gate, TermDictionary termDict, SkillIndexCache cache) {
        void terms(TermMap... ts) {
            when(termDict.activeTerms(eq(TENANT), eq(DS))).thenReturn(List.of(ts));
        }

        void metrics(Metric... ms) {
            when(cache.<Metric>list(anyString(), any())).thenReturn(List.of(ms));
        }
    }

    private static TermMap term(String term, String canonical) {
        TermMap t = new TermMap();
        t.setTerm(term);
        t.setCanonical(canonical);
        return t;
    }

    private static Metric metric(long id, String name, String question) {
        Metric m = new Metric();
        m.setId(id);
        m.setName(name);
        m.setQuestion(question);
        return m;
    }

    @Test
    @DisplayName("①总开关关闭 → 返回 null 且绝不加载词典/指标候选（逐字节等价未接入、零成本）")
    void disabledShortCircuitsWithoutLoading() {
        Harness h = harness(false, 60, 30, 3);
        assertNull(h.gate().evaluate("本月流水多少", TENANT, DS, false));
        verify(h.termDict(), never()).activeTerms(anyLong(), anyLong());
        verify(h.cache(), never()).list(anyString(), any());
    }

    @Test
    @DisplayName("②已澄清（clarified=true）→ 直接 null，锁死最多 1 轮，不加载候选")
    void clarifiedSkipsGate() {
        Harness h = harness(true, 60, 30, 3);
        assertNull(h.gate().evaluate("本月流水多少", TENANT, DS, true));
        verify(h.termDict(), never()).activeTerms(anyLong(), anyLong());
    }

    @Test
    @DisplayName("③空提问 → null")
    void blankQuestionReturnsNull() {
        Harness h = harness(true, 60, 30, 3);
        assertNull(h.gate().evaluate("  ", TENANT, DS, false));
    }

    @Test
    @DisplayName("④无歧义信号（词典空 + 指标唯一命中）→ null 直接执行")
    void noSignalReturnsNull() {
        Harness h = harness(true, 60, 30, 3);
        h.terms(); // 空词典
        h.metrics(metric(1L, "营收", "营收")); // 提问只命中一个指标名 → 非歧义
        assertNull(h.gate().evaluate("营收多少", TENANT, DS, false));
    }

    @Test
    @DisplayName("⑤单一信号（一词多口径 −0.35→0.65 ≥0.60）→ DIRECT、null（一期保守不误打扰）")
    void singleCaliberSignalStaysDirect() {
        Harness h = harness(true, 60, 30, 3);
        h.terms(term("流水", "营收"), term("流水", "回款")); // 一词两口径
        h.metrics(); // 无近似指标
        assertNull(h.gate().evaluate("本月流水多少", TENANT, DS, false));
    }

    @Test
    @DisplayName("⑤b 单一信号（近似指标名 −0.30→0.70 ≥0.60）→ DIRECT、null")
    void singleSimilarMetricSignalStaysDirect() {
        Harness h = harness(true, 60, 30, 3);
        h.terms(); // 无多口径
        h.metrics(metric(1L, "营收", null), metric(2L, "成本", null)); // 提问含两者 → 近似指标歧义
        assertNull(h.gate().evaluate("营收和成本的情况", TENANT, DS, false));
    }

    @Test
    @DisplayName("⑥两类信号同时命中（1.0−0.65=0.35 ∈ [0.30,0.60)）→ CLARIFY，候选非空、成因优先多口径")
    void bothSignalsTriggerClarify() {
        Harness h = harness(true, 60, 30, 5);
        h.terms(term("流水", "营收"), term("流水", "回款")); // 一词多口径
        h.metrics(metric(1L, "营收", null), metric(2L, "成本", null)); // 近似指标名
        ClarifyProposal p = h.gate().evaluate("流水营收成本", TENANT, DS, false);
        assertNotNull(p);
        assertEquals(ClarifyProposal.REASON_MULTI_CALIBER, p.reason());
        assertTrue(p.options().size() >= 2);
        assertEquals(p.options().get(0).code(), p.defaultOptionCode());
    }

    @Test
    @DisplayName("⑦候选数超上限被截断至 max-options")
    void optionsCappedAtMaxOptions() {
        Harness h = harness(true, 60, 30, 2);
        h.terms(term("流水", "营收"), term("流水", "回款"));
        h.metrics(metric(1L, "营收", null), metric(2L, "成本", null));
        ClarifyProposal p = h.gate().evaluate("流水营收成本", TENANT, DS, false);
        assertNotNull(p);
        assertEquals(2, p.options().size());
    }

    @Test
    @DisplayName("⑧阈值可配：直出阈值调到 70，单一口径信号 0.65 落入 [0.30,0.70) → CLARIFY（无需改码即可让单信号反问）")
    void tunableThresholdMakesSingleSignalClarify() {
        Harness h = harness(true, 70, 30, 3);
        h.terms(term("流水", "营收"), term("流水", "回款"));
        h.metrics();
        ClarifyProposal p = h.gate().evaluate("本月流水多少", TENANT, DS, false);
        assertNotNull(p);
        assertEquals(ClarifyProposal.REASON_MULTI_CALIBER, p.reason());
    }

    @Test
    @DisplayName("⑨低于下限不阻断：直出 100/下限 80，单信号 0.65＜下限 0.80 → blockBelowMin=false → DIRECT。问数侧恒不硬阻断")
    void belowMinNeverBlocks() {
        // 直出阈值 100、下限阈值 80：单信号 0.65 < 下限 0.80 → 走 blockBelowMin=false → DIRECT → null（放弃反问而非硬阻断）
        Harness h = harness(true, 100, 80, 3);
        h.terms(term("流水", "营收"), term("流水", "回款"));
        h.metrics();
        assertNull(h.gate().evaluate("本月流水多少", TENANT, DS, false));
    }

    @Test
    @DisplayName("⑩fail-open：候选加载抛异常 → 降级 null，绝不把异常抛给问数主链路")
    void failOpenOnLoadingException() {
        Harness h = harness(true, 60, 30, 3);
        when(h.termDict().activeTerms(eq(TENANT), eq(DS))).thenThrow(new RuntimeException("库挂了"));
        assertNull(h.gate().evaluate("本月流水多少", TENANT, DS, false));
    }
}
