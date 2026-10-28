package com.aibi.module.dashboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 驾驶舱/大屏实体（映射表 {@code dashboard}）：可配置的指标网格编排。
 *
 * <p>{@code dashboard} 为租户隔离表，{@code tenant_id} 由租户拦截器自动注入（勿手动设置）；{@code uid} 显式携带创建者。
 * {@code layout} 存 12 列网格 JSON {@code [{i,metricId,x,y,w,h,title,chartStyle}]}（{@code chartStyle} 为 widget 级
 * 图表标注覆盖，可缺省）；{@code is_screen=1} 表示作为全屏大屏展示，
 * 按 {@code refresh_seconds} 轮询刷新（0=不自动刷新）。{@code template_code} 记录来源内置模板（空=空白/自动布局）。
 */
@TableName("dashboard")
public class Dashboard {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private String name;
    private String description;
    private String templateCode;
    /** 所属分组（{@code dashboard_group.id}，空=未分组）。移动归属走专用端点，不经全量 update。 */
    private Long groupId;
    private String theme;
    private Integer cols;
    private Integer refreshSeconds;
    private Integer isScreen;
    private String layout;
    /** 大屏自定义背景（CSS background 值：色值/渐变/url()，空=跟随主题）。 */
    private String background;
    /**
     * 大屏级自定义样式 JSON（覆盖主题令牌）：顶栏背景/卡片背景/卡片边框/强调色/文字层级/字体族/字号比例/卡片圆角。
     * 形如 {@code {"cardBg":"#0b1b3a","accent":"#00c2ff","fontFamily":"'Microsoft YaHei'","fontScale":1.15}}；
     * 空=完全跟随所选主题。逐 widget 的样式覆盖存于 {@code layout} 数组元素的 {@code style} 字段。
     */
    private String styleConfig;
    /** 大屏公开分享令牌（空=未分享）；能力令牌，凭此匿名只读访问实时大屏。 */
    private String shareToken;
    /** 大屏分享访问密码（BCrypt 哈希，空=无密码）。 */
    private String sharePassword;
    /** 大屏分享过期时间（空=不过期）。 */
    private LocalDateTime shareExpireAt;
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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Integer getCols() {
        return cols;
    }

    public void setCols(Integer cols) {
        this.cols = cols;
    }

    public Integer getRefreshSeconds() {
        return refreshSeconds;
    }

    public void setRefreshSeconds(Integer refreshSeconds) {
        this.refreshSeconds = refreshSeconds;
    }

    public Integer getIsScreen() {
        return isScreen;
    }

    public void setIsScreen(Integer isScreen) {
        this.isScreen = isScreen;
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout;
    }

    public String getBackground() {
        return background;
    }

    public void setBackground(String background) {
        this.background = background;
    }

    public String getStyleConfig() {
        return styleConfig;
    }

    public void setStyleConfig(String styleConfig) {
        this.styleConfig = styleConfig;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public String getSharePassword() {
        return sharePassword;
    }

    public void setSharePassword(String sharePassword) {
        this.sharePassword = sharePassword;
    }

    public LocalDateTime getShareExpireAt() {
        return shareExpireAt;
    }

    public void setShareExpireAt(LocalDateTime shareExpireAt) {
        this.shareExpireAt = shareExpireAt;
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
