package com.aibi.module.acl.mapper;

import com.aibi.module.acl.entity.ResourceAcl;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 资源级 ACL Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code resource_acl} 带 {@code tenant_id}，但本表独立于各资源表、由 {@code ResourceAclService}
 * 集中维护；其查询按 {@code (resource_type, resource_id)} 唯一定位，不做跨组织注入（调用方已先验租户）。
 */
public interface ResourceAclMapper extends BaseMapper<ResourceAcl> {

    /**
     * 按 (资源类型, 资源 id) 列出全部授权行（一个资源可有多个 principal 行）。
     * 租户隔离由 {@code TenantLineInnerInterceptor} 自动注入 {@code tenant_id}（本表为租户隔离表）。
     */
    @Select("SELECT * FROM resource_acl WHERE resource_type = #{type} AND resource_id = #{id}")
    List<ResourceAcl> listByResource(@Param("type") String type, @Param("id") Long id);

    /** 删除某资源的全部归属行（资源删除时清理）。 */
    @Delete("DELETE FROM resource_acl WHERE resource_type = #{type} AND resource_id = #{id}")
    int deleteByResource(@Param("type") String type, @Param("id") Long id);
}
