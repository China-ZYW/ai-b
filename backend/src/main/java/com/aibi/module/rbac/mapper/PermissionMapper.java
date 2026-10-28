package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.Permission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 权限点字典 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code permission} 与 {@code role_permission} 均为全局字典表，已列入租户拦截器 IGNORE 名单，
 * 故下列查询不含 {@code tenant_id}，全平台共享同一份权限点定义。
 *
 * <p>{@link #selectCodesByRoleCodes} 是权限解析的热路径（每次缓存未命中都会调用），
 * 结果由 {@code PermissionService} 的 Caffeine 缓存按 {@code 角色码集合 + perm_version} 键缓存。
 */
public interface PermissionMapper extends BaseMapper<Permission> {

    /** 全部已启用权限点（后台字典维护与接口矩阵扫描用）。 */
    @Select("SELECT * FROM permission WHERE enabled = 1 ORDER BY module ASC, sort ASC, id ASC")
    List<Permission> selectEnabled();

    /**
     * 按角色码集合解析权限点码集合（多角色取并集，见设计文档 3.6⑥）。
     *
     * <p>用 {@code <script>} + {@code foreach} 展开 IN 列表；调用方须保证 {@code roleCodes} 非空，
     * 空集合会生成 {@code IN ()} 导致 SQL 语法错误。
     */
    @Select("""
            <script>
            SELECT p.code FROM permission p
            JOIN role_permission rp ON rp.permission_code = p.code
            WHERE p.enabled = 1 AND rp.role_code IN
            <foreach collection="roleCodes" item="rc" open="(" separator="," close=")">#{rc}</foreach>
            </script>
            """)
    List<String> selectCodesByRoleCodes(@Param("roleCodes") Collection<String> roleCodes);

    /** 菜单型权限点（前端动态菜单的数据来源，按 sort 升序保证菜单顺序稳定）。 */
    @Select("SELECT * FROM permission WHERE enabled = 1 AND type = 'MENU' ORDER BY sort ASC, id ASC")
    List<Permission> selectMenus();

    /** API 型权限点（后台"接口矩阵扫描"比对未登记接口用）。 */
    @Select("SELECT * FROM permission WHERE enabled = 1 AND type = 'API' ORDER BY api_pattern ASC, id ASC")
    List<Permission> selectApis();
}
