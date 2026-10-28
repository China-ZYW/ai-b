package com.aibi.module.theme.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 主题令牌实体（MyBatis-Plus 映射表 {@code theme_config}，配置化升级 V2）。
 *
 * <p>样式配置化的事实源：{@code tokens} 为与前端 {@code DashboardTheme} 同构的 JSON（背景/卡片/文字/
 * 图表色板/坐标轴/tooltip 等），{@code code} 对应 {@code dashboard.theme} 引用的 key。
 *
 * <p>{@code tenant_id} 可空（NULL=全局内置预设），本表列入租户拦截器 IGNORE 名单，
 * 由 {@code ThemeService} 显式解析（全局预设 + 租户自定义）。
 */
@TableName("theme_config")
public class ThemeConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private Integer dark;
    private String tokens;
    private Integer builtin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
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

    public Integer getDark() {
        return dark;
    }

    public void setDark(Integer dark) {
        this.dark = dark;
    }

    public String getTokens() {
        return tokens;
    }

    public void setTokens(String tokens) {
        this.tokens = tokens;
    }

    public Integer getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Integer builtin) {
        this.builtin = builtin;
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
}
