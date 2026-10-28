package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色字典实体（映射表 {@code role}，全局表，列入租户拦截器 IGNORE 名单）。
 *
 * <p>三层角色（见设计文档 3.1）：{@code level} 取 PLATFORM / TENANT / TEAM，
 * 三层是<b>并集</b>而非覆盖。{@code dataScope} 参与"多角色取最宽"的合并（3.6⑦）。
 * 内置角色由 {@code RbacDictionary} 播种（{@code builtin=1}，不可删除）。
 */
@TableName("role")
public class Role {

    /** 角色层级：平台。 */
    public static final String LEVEL_PLATFORM = "PLATFORM";
    /** 角色层级：组织（租户）。 */
    public static final String LEVEL_TENANT = "TENANT";
    /** 角色层级：团队。 */
    public static final String LEVEL_TEAM = "TEAM";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    /** PLATFORM/TENANT/TEAM。 */
    private String level;
    /** ALL/TENANT/TEAM_AND_CHILD/TEAM/SELF/CUSTOM，见 {@code DataScope}。 */
    private String dataScope;
    /** 1=团队角色对 parent_id 链上的全部子团队生效。 */
    private Integer inheritToChild;
    private Integer builtin;
    private String description;
    private Integer sort;
    private Integer enabled;
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

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getDataScope() {
        return dataScope;
    }

    public void setDataScope(String dataScope) {
        this.dataScope = dataScope;
    }

    public Integer getInheritToChild() {
        return inheritToChild;
    }

    public void setInheritToChild(Integer inheritToChild) {
        this.inheritToChild = inheritToChild;
    }

    public Integer getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Integer builtin) {
        this.builtin = builtin;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
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

    /** 团队角色是否对子团队生效（{@code inherit_to_child=1}）。 */
    public boolean inheritsToChild() {
        return inheritToChild != null && inheritToChild == 1;
    }

    public boolean isEnabled() {
        return enabled == null || enabled == 1;
    }
}
