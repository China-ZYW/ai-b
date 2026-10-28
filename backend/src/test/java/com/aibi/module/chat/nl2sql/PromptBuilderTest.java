package com.aibi.module.chat.nl2sql;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PromptBuilder 时间锚点（{@code ASK / time-anchor}）纯逻辑单测：不起 Spring、不连库——
 * 仅 mock {@code AibiConfig} 桩门控，验证开则在「用户问题」前注入当日日期与换算引导、关则 Prompt 不含锚点行。
 */
class PromptBuilderTest {

    private static final TableMeta ORDERS =
            new TableMeta("orders", "订单表", List.of(), List.of("id"), List.of());
    private static final SchemaSlice SLICE = new SchemaSlice(List.of(ORDERS), 1, false);
    private static final DataSourceConfig CFG = new DataSourceConfig(
            1L, 1L, "ds", DataSourceType.MYSQL, "localhost", 3306, "db",
            "u", "p", null, null, null, false, null);

    private static PromptBuilder builder(boolean timeAnchorEnabled) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.timeAnchorEnabled()).thenReturn(timeAnchorEnabled);
        when(cfg.promptRules()).thenReturn(List.of());   // 无业务规则条款，聚焦锚点断言
        return new PromptBuilder(cfg);
    }

    @Test
    @DisplayName("time-anchor 开 → Prompt 含当日日期串与「换算成绝对日期」引导")
    void anchorOnInjectsDate() {
        String prompt = builder(true).build(CFG, SLICE, Intent.AGGREGATION,
                null, null, "本月销售额是多少", null, null, null);
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        assertTrue(prompt.contains("【当前日期】"), "应含锚点标题");
        assertTrue(prompt.contains(today), "应含当日 ISO 日期 " + today);
        assertTrue(prompt.contains("换算成绝对日期"), "应含相对时间换算引导");
    }

    @Test
    @DisplayName("time-anchor 关 → Prompt 不含锚点行（与未接入逐字节等价）")
    void anchorOffOmitsDate() {
        String prompt = builder(false).build(CFG, SLICE, Intent.AGGREGATION,
                null, null, "本月销售额是多少", null, null, null);
        assertFalse(prompt.contains("【当前日期】"), "关闭时不应出现锚点行");
        assertFalse(prompt.contains("换算成绝对日期"));
    }
}
