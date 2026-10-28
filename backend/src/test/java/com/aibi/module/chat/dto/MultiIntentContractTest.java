package com.aibi.module.chat.dto;

import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.QueryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 多意图数据契约（T10-4）纯逻辑单测：验证给 {@link ChatQueryResponse} 新增第 9 组件 {@code parts} 后，
 * 4/7/8 参兼容构造器一律 {@code parts==null} 且旧字段齐全（守住现有调用点零回归），9 参规范构造器可带子答；
 * 以及 {@link QueryContext#withQuestion} 为子问派生上下文时配<b>全新 ExecStats</b>、环境维度不变。
 */
class MultiIntentContractTest {

    @Test
    @DisplayName("4 参兼容构造器：parts=null，旧字段齐全")
    void ctor4() {
        ChatQueryResponse r = new ChatQueryResponse("conv", 1L, null, null);
        assertEquals("conv", r.conversationId());
        assertEquals(1L, r.historyId());
        assertNull(r.parts());
    }

    @Test
    @DisplayName("7 参兼容构造器：parts=null、skill=null")
    void ctor7() {
        ResultMeta meta = new ResultMeta(false, 1, 1, false, false, 0, null, false);
        ChatQueryResponse r = new ChatQueryResponse("conv", 2L, null, null, meta, null, null);
        assertNull(r.skill());
        assertNull(r.parts());
    }

    @Test
    @DisplayName("8 参兼容构造器：parts=null、skill 齐全（现有两处调用点走此重载）")
    void ctor8() {
        SkillInfo skill = new SkillInfo("nl2sql", "智能生成", false, "reason");
        ChatQueryResponse r = new ChatQueryResponse("conv", 3L, null, null, null, null, null, skill);
        assertEquals("nl2sql", r.skill().code());
        assertNull(r.parts());
    }

    @Test
    @DisplayName("9 参规范构造器：可承载多段 parts")
    void ctor9Canonical() {
        List<SubAnswer> parts = List.of(new SubAnswer("子问1", null, null, null, null, null, true));
        ChatQueryResponse r = new ChatQueryResponse("conv", null, null, null, null, null, null, null, parts);
        assertNull(r.result());
        assertEquals(1, r.parts().size());
        assertEquals("子问1", r.parts().get(0).subQuestion());
    }

    @Test
    @DisplayName("SubAnswer.unanswered：answered=false 且结果为空")
    void subAnswerUnanswered() {
        SubAnswer s = SubAnswer.unanswered("未答子问");
        assertEquals("未答子问", s.subQuestion());
        assertEquals(false, s.answered());
        assertNull(s.result());
    }

    @Test
    @DisplayName("QueryContext.withQuestion：换新问句 + 全新 ExecStats，会话/历史/进度/图表要求不变")
    void queryContextWithQuestion() {
        QueryContext base = new QueryContext("原始复合问", "conv", List.of(),
                ProgressListener.NOOP, new com.aibi.module.chat.nl2sql.ExecStats(), null, "热力图");
        QueryContext sub = base.withQuestion("上月销售额是多少");

        assertEquals("上月销售额是多少", sub.question());
        assertEquals("conv", sub.conversationId());
        assertEquals("热力图", sub.requestedChart());
        assertSame(base.listener(), sub.listener());
        // 关键：子问独立统计载体，不与原问/前序子问共享
        assertNotSame(base.stats(), sub.stats());
        assertNull(sub.retryHint());
    }
}
