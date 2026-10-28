package com.aibi.module.query.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.chat.chart.ChartRecommender;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.query.entity.SavedQuery;
import com.aibi.module.query.mapper.QueryRecordMapper;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.aibi.module.query.chart.ChartTypeRegistry;
import com.aibi.module.query.processor.ResultProcessorChain;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织共享查询模板（租户管理员集中维护）· {@link QueryService} 侧单测：
 * 列表合并门控、可见性切换的管理员/门控判定、VO 共享字段透出。
 * 纯 Mockito 不起 Spring；mapper 全 mock，不触真实 SQL。
 */
class QueryTemplateSharedTest {

    private SavedQueryMapper mapper;
    private AibiConfig aibiConfig;
    private QueryService service;

    @BeforeAll
    static void initTableInfo() {
        // 脱离 Spring 时 LambdaQueryWrapper 没有实体列名缓存，手工初始化一次（getSqlSegment 需要）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SavedQuery.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(SavedQueryMapper.class);
        aibiConfig = mock(AibiConfig.class);
        service = new QueryService(
                mock(DataSourceService.class), mock(DataSourceAdapterRegistry.class),
                mock(SqlValidator.class), mock(DataMaskGate.class),
                mapper, mock(QueryRecordMapper.class),
                mock(ChartRecommender.class), mock(ChartTypeRegistry.class),
                mock(ResultProcessorChain.class), aibiConfig,
                10000L, 30);
        when(aibiConfig.sharedTemplatesEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        AccessContextHolder.clear();
    }

    private static SavedQuery tpl(long id, long uid, String visibility) {
        SavedQuery q = new SavedQuery(1L, uid, 7L, "模板" + id, "SELECT 1", null, null);
        q.setId(id);
        q.setVisibility(visibility);
        return q;
    }

    // ==================== 列表合并 ====================

    @Test
    void listMergesSharedWhenGateOnAndOwnOnlyWhenOff() {
        when(mapper.selectList(any())).thenReturn(List.of(tpl(1, 100, "SHARED")));
        service.listTemplates(200L, null);
        ArgumentCaptor<Wrapper<SavedQuery>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("visibility"), "门控开应带共享可见性 OR 条件");
        assertTrue(sql.contains("OR"), "门控开应为 本人 OR 共享");

        when(aibiConfig.sharedTemplatesEnabled()).thenReturn(false);
        service.listTemplates(200L, null);
        ArgumentCaptor<Wrapper<SavedQuery>> cap2 = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(2)).selectList(cap2.capture());
        assertFalse(cap2.getValue().getSqlSegment().contains("visibility"),
                "门控关只查本人，不合并共享");
    }

    @Test
    void voExposesVisibilityAndOwner() {
        when(mapper.selectList(any())).thenReturn(List.of(
                tpl(1, 100, "SHARED"), tpl(2, 200, null)));
        List<QueryDtos.SavedQueryVO> out = service.listTemplates(200L, null);
        assertEquals(2, out.size());
        assertEquals("SHARED", out.get(0).visibility());
        assertEquals(100L, out.get(0).ownerUid());
        // 存量行 visibility=NULL 按 PRIVATE 透出
        assertEquals("PRIVATE", out.get(1).visibility());
    }

    // ==================== 可见性切换 ====================

    @Test
    void shareRequiresOrgManager() {
        AccessContextHolder.set(AccessContext.builder().uid(100L).tenantId(1L).build());
        when(mapper.selectById(1L)).thenReturn(tpl(1, 100, "PRIVATE"));
        BizException e = assertThrows(BizException.class,
                () -> service.setTemplateVisibility(1L, "SHARED", 1L, 100L));
        assertTrue(e.getMessage().contains("管理员"));
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void adminOwnerCanShareAndReclaim() {
        AccessContextHolder.set(AccessContext.builder()
                .uid(100L).tenantId(1L).orgRoles(Set.of("ADMIN")).build());
        when(mapper.selectById(1L)).thenReturn(tpl(1, 100, "PRIVATE"));
        when(mapper.update(isNull(), any())).thenReturn(1);
        service.setTemplateVisibility(1L, "SHARED", 1L, 100L);
        verify(mapper).update(isNull(), any());
    }

    @Test
    void gateOffBlocksShareButAllowsReclaim() {
        AccessContextHolder.set(AccessContext.builder()
                .uid(100L).tenantId(1L).orgRoles(Set.of("ADMIN")).build());
        when(aibiConfig.sharedTemplatesEnabled()).thenReturn(false);
        when(mapper.update(isNull(), any())).thenReturn(1);
        // 共享被拒（不必查库即拦）
        assertThrows(BizException.class,
                () -> service.setTemplateVisibility(1L, "SHARED", 1L, 100L));
        // 收回 PRIVATE 不受门控限制（fail-soft：关开关仍能治理存量）
        when(mapper.selectById(1L)).thenReturn(tpl(1, 100, "SHARED"));
        service.setTemplateVisibility(1L, "PRIVATE", 1L, 100L);
        verify(mapper).update(isNull(), any());
    }

    @Test
    void unknownVisibilityRejected() {
        AccessContextHolder.set(AccessContext.builder()
                .uid(100L).tenantId(1L).orgRoles(Set.of("ADMIN")).build());
        assertThrows(BizException.class,
                () -> service.setTemplateVisibility(1L, "PUBLIC", 1L, 100L));
    }

    // ==================== isShared 判定 ====================

    @Test
    void isSharedNullSafe() {
        assertFalse(QueryService.isShared(null));
        assertFalse(QueryService.isShared(tpl(1, 1, null)), "存量 NULL 按 PRIVATE");
        assertFalse(QueryService.isShared(tpl(1, 1, "PRIVATE")));
        assertTrue(QueryService.isShared(tpl(1, 1, "shared")), "大小写宽容");
    }
}
