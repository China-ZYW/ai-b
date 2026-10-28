package com.aibi.module.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 口径澄清数据契约（T10-2）纯逻辑单测：验证给 {@link ChatQueryResponse} 新增第 10 组件 {@code clarify} 后，
 * 4/7/8/9 参兼容构造器一律 {@code clarify==null}（守住现有单意图/多意图调用点零回归），
 * 澄清短路工厂 {@link ChatQueryResponse#clarify} 只带会话与提案、执行字段全空（未执行、未扣配额）。
 */
class ClarifyContractTest {

    @Test
    @DisplayName("4 参兼容构造器：clarify=null（老调用方无感）")
    void ctor4ClarifyNull() {
        ChatQueryResponse r = new ChatQueryResponse("conv", 1L, null, null);
        assertNull(r.clarify());
    }

    @Test
    @DisplayName("8 参兼容构造器：clarify=null、skill 齐全（现有单意图调用点走此重载）")
    void ctor8ClarifyNull() {
        SkillInfo skill = new SkillInfo("metric", "指标直达", true, "命中指标");
        ChatQueryResponse r = new ChatQueryResponse("conv", 3L, null, null, null, null, null, skill);
        assertEquals("metric", r.skill().code());
        assertNull(r.clarify());
    }

    @Test
    @DisplayName("9 参兼容构造器（含 parts）：clarify=null——多意图与澄清互不串场")
    void ctor9PartsClarifyNull() {
        List<SubAnswer> parts = List.of(new SubAnswer("子问1", null, null, null, null, null, true));
        ChatQueryResponse r = new ChatQueryResponse("conv", null, null, null, null, null, null, null, parts);
        assertEquals(1, r.parts().size());
        assertNull(r.clarify());
    }

    @Test
    @DisplayName("澄清短路工厂：只带 conversationId + clarify 提案，result/historyId/parts 等执行字段全空")
    void clarifyFactoryCarriesOnlyProposal() {
        ClarifyOption opt = new ClarifyOption("term:营收", "流水（口径：营收）", "本月营收多少");
        ClarifyProposal proposal = new ClarifyProposal(
                ClarifyProposal.REASON_MULTI_CALIBER, List.of(opt), "term:营收");
        ChatQueryResponse r = ChatQueryResponse.clarify("conv", proposal);

        assertEquals("conv", r.conversationId());
        assertNull(r.historyId());
        assertNull(r.result());
        assertNull(r.parts());
        assertEquals(ClarifyProposal.REASON_MULTI_CALIBER, r.clarify().reason());
        assertEquals(1, r.clarify().options().size());
        assertEquals("本月营收多少", r.clarify().options().get(0).refineQuestion());
        assertEquals("term:营收", r.clarify().defaultOptionCode());
    }
}
