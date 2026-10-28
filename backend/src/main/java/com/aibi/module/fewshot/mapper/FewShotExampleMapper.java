package com.aibi.module.fewshot.mapper;

import com.aibi.module.fewshot.entity.FewShotExample;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问数样例库 Mapper（表 {@code few_shot_example} 为租户隔离表，tenant_id 由拦截器自动注入）。
 */
@Mapper
public interface FewShotExampleMapper extends BaseMapper<FewShotExample> {
}
