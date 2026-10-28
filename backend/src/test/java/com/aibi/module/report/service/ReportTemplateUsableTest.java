package com.aibi.module.report.service;

import com.aibi.module.query.entity.SavedQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表视角的模板可用性（{@link ReportService#templateUsable} 纯函数）：
 * 属主本人 / 同租户组织共享可用；跨租户共享、他人私有、存量 NULL 不可用。
 */
class ReportTemplateUsableTest {

    private static SavedQuery tpl(long uid, Long tenantId, String visibility) {
        SavedQuery q = new SavedQuery(tenantId, uid, 7L, "模板", "SELECT 1", null, null);
        q.setVisibility(visibility);
        return q;
    }

    @Test
    void ownTemplateAlwaysUsable() {
        assertTrue(ReportService.templateUsable(tpl(100L, 1L, "PRIVATE"), 1L, 100L));
        assertTrue(ReportService.templateUsable(tpl(100L, 1L, null), 1L, 100L),
                "属主的存量 NULL 行仍可用（旧行为不变）");
    }

    @Test
    void sharedSameTenantUsable() {
        assertTrue(ReportService.templateUsable(tpl(9L, 1L, "SHARED"), 1L, 100L));
        assertTrue(ReportService.templateUsable(tpl(9L, 1L, "shared"), 1L, 100L), "大小写宽容");
    }

    @Test
    void othersPrivateOrCrossTenantNotUsable() {
        assertFalse(ReportService.templateUsable(tpl(9L, 1L, "PRIVATE"), 1L, 100L), "他人私有不可用");
        assertFalse(ReportService.templateUsable(tpl(9L, 1L, null), 1L, 100L), "存量 NULL 按私有");
        assertFalse(ReportService.templateUsable(tpl(9L, 2L, "SHARED"), 1L, 100L), "跨租户共享不可用");
        assertFalse(ReportService.templateUsable(null, 1L, 100L), "模板被删不可用");
        assertFalse(ReportService.templateUsable(tpl(9L, null, "SHARED"), 1L, 100L),
                "模板无租户归属时不认共享");
    }
}
