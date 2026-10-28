package com.aibi.module.chat.llm.mapper;

import com.aibi.module.chat.llm.entity.LlmConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 大模型配置 Mapper（由 {@code @MapperScan} 扫描注册）。{@code llm_config} 在租户拦截器 IGNORE 名单
 * （全局表 + 租户覆盖），故 CRUD 不自动注入 {@code tenant_id}；按租户解析用下列显式查询。
 */
public interface LlmConfigMapper extends BaseMapper<LlmConfig> {

    /** 某租户已启用的专属配置（按优先级升序）。 */
    @Select("SELECT * FROM llm_config WHERE tenant_id = #{tenantId} AND enabled = 1 ORDER BY priority ASC, id ASC")
    List<LlmConfig> selectEnabledByTenant(@Param("tenantId") Long tenantId);

    /** 全局默认已启用配置（{@code tenant_id IS NULL}，按优先级升序）。 */
    @Select("SELECT * FROM llm_config WHERE tenant_id IS NULL AND enabled = 1 ORDER BY priority ASC, id ASC")
    List<LlmConfig> selectGlobalEnabled();
}
