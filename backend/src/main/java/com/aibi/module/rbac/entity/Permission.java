package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 权限点字典实体（映射表 {@code permission}，全局表，列入租户拦截器 IGNORE 名单）。
 *
 * <p>本表是<b>权限的单一事实源</b>（见设计文档 4.1）：同一份字典同时驱动后端
 * {@code @RequiresPermission} 切面、前端路由守卫与前端按钮显隐，杜绝"前后端两套权限码"漂移。
 *
 * <p>编码规范 {@code {module}:{resource}:{action}}，如 {@code datasource:conn:test}。
 * {@code type} 区分用途：MENU（前端菜单项，{@code menuCode} 对应路由 name）、BUTTON（按钮显隐）、
 * API（接口门禁，{@code apiMethod}/{@code apiPattern} 供后台接口矩阵扫描比对）、DATA（数据范围类）。
 */
@TableName("permission")
public class Permission {

    /** 平台层权限点：仅平台运营身份可持有。 */
    public static final String LEVEL_PLATFORM = "PLATFORM";
    /** 组织层权限点：组织内角色可持有。 */
    public static final String LEVEL_TENANT = "TENANT";
    /** 团队层权限点：团队角色可持有。 */
    public static final String LEVEL_TEAM = "TEAM";
    /** 资源层权限点：须叠加 {@code resource_acl} 判定。 */
    public static final String LEVEL_RESOURCE = "RESOURCE";

    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_BUTTON = "BUTTON";
    public static final String TYPE_API = "API";
    public static final String TYPE_DATA = "DATA";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** {@code module:resource:action}，全局唯一。 */
    private String code;
    private String name;
    private String module;
    /** PLATFORM/TENANT/TEAM/RESOURCE。 */
    private String level;
    /** MENU/BUTTON/API/DATA。 */
    private String type;
    /** MENU 型：前端路由 name，动态菜单据此过滤。 */
    private String menuCode;
    /** MENU 型：前端路径（仅后台展示用，真实跳转以路由表为准）。 */
    private String menuPath;
    private String menuIcon;
    /** 权限点分组（父权限码），后台树形展示用。 */
    private String parentCode;
    /** API 型：GET/POST/PUT/DELETE。 */
    private String apiMethod;
    /** API 型：{@code /api/datasource/**}，接口矩阵扫描用。 */
    private String apiPattern;
    private Integer builtin;
    private Integer enabled;
    private Integer sort;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMenuCode() {
        return menuCode;
    }

    public void setMenuCode(String menuCode) {
        this.menuCode = menuCode;
    }

    public String getMenuPath() {
        return menuPath;
    }

    public void setMenuPath(String menuPath) {
        this.menuPath = menuPath;
    }

    public String getMenuIcon() {
        return menuIcon;
    }

    public void setMenuIcon(String menuIcon) {
        this.menuIcon = menuIcon;
    }

    public String getParentCode() {
        return parentCode;
    }

    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }

    public String getApiMethod() {
        return apiMethod;
    }

    public void setApiMethod(String apiMethod) {
        this.apiMethod = apiMethod;
    }

    public String getApiPattern() {
        return apiPattern;
    }

    public void setApiPattern(String apiPattern) {
        this.apiPattern = apiPattern;
    }

    public Integer getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Integer builtin) {
        this.builtin = builtin;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isEnabled() {
        return enabled == null || enabled == 1;
    }
}
