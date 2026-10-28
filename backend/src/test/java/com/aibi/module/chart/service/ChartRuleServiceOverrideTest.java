package com.aibi.module.chart.service;

import com.aibi.common.tenant.PlanType;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chart.entity.ChartRule;
import com.aibi.module.chart.mapper.ChartRuleMapper;
import com.aibi.module.config.service.AibiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ChartRule 覆盖语义锁定测试：KEYWORD 规则「租户整体覆盖全局」（非合并）。
 *
 * <p>见 {@code ChartRuleService.resolve} 的覆盖语义注释——本测试把"整体替换"行为钉死，
 * 防止日后误改成合并而破坏租户对图表推荐的完全掌控（或反之）。THRESHOLD 为合并，单测覆盖。
 */
@ExtendWith(MockitoExtension.class)
class ChartRuleServiceOverrideTest {

    @Mock
    private ChartRuleMapper mapper;
    @Mock
    private AibiConfig aibiConfig;

    private ChartRuleService service;

    @BeforeEach
    void init() {
        service = new ChartRuleService(mapper, aibiConfig, 1800L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChartRule kw(Long tenantId, String pattern, String chartType, int priority, String type) {
        ChartRule r = new ChartRule();
        r.setId(System.nanoTime());
        r.setTenantId(tenantId);
        r.setRuleType(type);
        r.setPattern(pattern);
        r.setChartType(chartType);
        r.setPriority(priority);
        r.setEnabled(1);
        return r;
    }

    private ChartRule keyword(Long tenantId, String pattern, String chartType, int priority) {
        return kw(tenantId, pattern, chartType, priority, ChartRule.TYPE_KEYWORD);
    }

    private ChartRule threshold(Long tenantId, String key, int value) {
        ChartRule r = new ChartRule();
        r.setId(System.nanoTime());
        r.setTenantId(tenantId);
        r.setRuleType(ChartRule.TYPE_THRESHOLD);
        r.setPattern(key);
        r.setPriority(value);
        r.setEnabled(1);
        return r;
    }

    @Test
    void noTenantKeywordRules_usesGlobalKeywords() {
        TenantContext.set(1L, PlanType.FREE);
        when(mapper.selectList(any())).thenReturn(List.of(
                keyword(null, "热力图", "HEATMAP", 10),
                keyword(null, "地图", "MAP", 11)
        ));
        List<ChartRule> rules = service.keywordRules();
        assertTrue(rules.stream().anyMatch(r -> "HEATMAP".equals(r.getChartType())));
        assertTrue(rules.stream().anyMatch(r -> "MAP".equals(r.getChartType())));
    }

    @Test
    void tenantKeywordRules_fullyOverrideGlobal() {
        TenantContext.set(7L, PlanType.FREE);
        when(mapper.selectList(any())).thenReturn(List.of(
                keyword(null, "热力图", "HEATMAP", 10),
                keyword(null, "地图", "MAP", 11),
                keyword(7L, "柱状", "BAR", 20)
        ));
        List<ChartRule> rules = service.keywordRules();
        // 租户一旦有任何启用 KEYWORD 行，全局 KEYWORD 行整体被忽略
        assertEquals(1, rules.size());
        assertEquals("BAR", rules.get(0).getChartType());
        assertFalse(rules.stream().anyMatch(r -> "HEATMAP".equals(r.getChartType())));
        assertFalse(rules.stream().anyMatch(r -> "MAP".equals(r.getChartType())));
    }

    @Test
    void tenantOnlyThresholdRules_globalKeywordsStillUsed() {
        TenantContext.set(9L, PlanType.FREE);
        when(mapper.selectList(any())).thenReturn(List.of(
                keyword(null, "热力图", "HEATMAP", 10),
                threshold(9L, "max-pie-slices", 8)
        ));
        List<ChartRule> rules = service.keywordRules();
        // 租户只有 THRESHOLD 行、无 KEYWORD 行 → 全局 KEYWORD 继续生效
        assertTrue(rules.stream().anyMatch(r -> "HEATMAP".equals(r.getChartType())));
    }

    @Test
    void thresholdRules_mergeTenantOverGlobal() {
        TenantContext.set(3L, PlanType.FREE);
        when(mapper.selectList(any())).thenReturn(List.of(
                threshold(null, "max-pie-slices", 8),
                threshold(3L, "max-pie-slices", 12)
        ));
        // 同 key 的全局值被租户值覆盖（合并语义，与 KEYWORD 不同）
        assertEquals(12, service.maxPieSlices());
    }
}
