package com.aibi.module.config.service;

import com.aibi.common.exception.BizException;
import com.aibi.module.config.entity.SysConfig;
import com.aibi.module.config.mapper.SysConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心两条不变量的回归护栏（v3.19）：
 *
 * <p><b>① 幂等写入</b>：内容无变化时 {@code upsert} 不写库、不递增 {@code version}、返回 {@code null}。
 * 背景：管理页「保存」按钮此前没有护栏，连点几次就把 {@code version} 顶上去，而 {@code version} 是
 * "这行配置被改过几次"的事实记录（审计与"是否被人动过"都看它），不该被空提交污染。
 *
 * <p><b>② 平台管控</b>：GLOBAL 行 {@code managed=1} 时租户写同一 (category, configKey) 必须抛
 * {@link BizException}（40301），而不是静默忽略。
 *
 * <p>测试用手写 Mockito 替身，不起 Spring 上下文——这两条都是纯逻辑不变量，走真实库反而更难覆盖边界。
 */
class ConfigServiceNoChangeTest {

    private SysConfigMapper mapper;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SysConfigMapper.class);
        // 默认无任何 GLOBAL / TENANT 行；个别用例再按需覆盖
        when(mapper.selectList(any())).thenReturn(List.of());
        service = new ConfigService(mapper, new ObjectMapper(), 1800);
    }

    /** 一行已存在的 GLOBAL 配置。 */
    private SysConfig existingRow(String valueJson, int version) {
        SysConfig r = new SysConfig();
        r.setId(1L);
        r.setScopeType(ConfigService.SCOPE_GLOBAL);
        r.setScopeId(null);
        r.setCategory("ATTRIBUTION");
        r.setConfigKey("result-panel");
        r.setValueJson(valueJson);
        r.setDescription("问数结果内展示归因面板");
        r.setVersion(version);
        r.setEnabled(1);
        r.setManaged(0);
        return r;
    }

    @Test
    @DisplayName("同一份内容重复保存：不写库、版本号不变、返回 null")
    void repeatSameValueIsNoOp() {
        when(mapper.selectOne(any())).thenReturn(existingRow("true", 3));

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "ATTRIBUTION", "result-panel",
                "true", "问数结果内展示归因面板", null);

        assertNull(ret, "内容无变化必须返回 null，前端据此提示“未发生变化”");
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("仅 JSON 格式不同（空白/缩进）视为无变化：不写库")
    void reformattedJsonIsNoOp() {
        when(mapper.selectOne(any())).thenReturn(existingRow("{\"a\":1,\"b\":[1,2]}", 7));

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "CHART", "chart-defaults",
                "{ \"a\": 1, \"b\": [1, 2] }", null, null);

        assertNull(ret, "格式化差异不该被当成一次真实变更");
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("值真的变了：写库一次、版本号 +1、返回更新后的行")
    void changedValueWritesAndBumpsVersion() {
        SysConfig row = existingRow("true", 3);
        when(mapper.selectOne(any())).thenReturn(row);

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "ATTRIBUTION", "result-panel",
                "false", null, null);

        assertNotNull(ret);
        assertEquals(4, ret.getVersion(), "真实变更才递增版本号");
        assertEquals("false", ret.getValueJson());
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("只改说明也算变更：写库并递增版本号")
    void descriptionOnlyChangeWrites() {
        SysConfig row = existingRow("true", 5);
        when(mapper.selectOne(any())).thenReturn(row);

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "ATTRIBUTION", "result-panel",
                "true", "改过的说明文案", null);

        assertNotNull(ret);
        assertEquals(6, ret.getVersion());
        assertEquals("改过的说明文案", ret.getDescription());
    }

    @Test
    @DisplayName("管控标记切换算变更：写库并落 managed=1")
    void managedToggleWrites() {
        SysConfig row = existingRow("true", 2);
        when(mapper.selectOne(any())).thenReturn(row);

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "ATTRIBUTION", "result-panel",
                "true", "问数结果内展示归因面板", true);

        assertNotNull(ret);
        assertEquals(1, ret.getManaged());
        assertEquals(3, ret.getVersion());
    }

    @Test
    @DisplayName("被停用的行再次 upsert 仍会写库（重新启用），不会被幂等护栏吞掉")
    void disabledRowIsReEnabled() {
        SysConfig row = existingRow("true", 4);
        row.setEnabled(0);
        when(mapper.selectOne(any())).thenReturn(row);

        SysConfig ret = service.upsert(ConfigService.SCOPE_GLOBAL, null, "ATTRIBUTION", "result-panel",
                "true", "问数结果内展示归因面板", null);

        assertNotNull(ret, "enabled=0 的行必须被重新启用，不能被当作“无变化”跳过");
        assertEquals(1, ret.getEnabled());
        assertEquals(5, ret.getVersion());
    }

    @Test
    @DisplayName("平台管控键：租户写入被拒绝（40301），且不产生任何写操作")
    void tenantWriteBlockedWhenManaged() {
        SysConfig managed = existingRow("true", 1);
        managed.setManaged(1);
        when(mapper.selectList(any())).thenReturn(List.of(managed));

        BizException ex = assertThrows(BizException.class, () ->
                service.upsert(ConfigService.SCOPE_TENANT, 1L, "ATTRIBUTION", "result-panel", "false", null));

        assertTrue(ex.getMessage().contains("平台统一管控"), "错误信息要能指导用户：联系平台管理员");
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("管控清单只收 GLOBAL 行里 managed=1 的键，且带分类前缀")
    void managedKeysListsOnlyManagedGlobalKeys() {
        SysConfig managed = existingRow("true", 1);
        managed.setManaged(1);
        SysConfig plain = existingRow("false", 1);
        when(mapper.selectList(any())).thenReturn(List.of(managed, plain));

        List<String> keys = service.managedKeys();

        assertEquals(List.of("ATTRIBUTION:result-panel"), keys);
    }
}
