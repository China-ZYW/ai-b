package com.aibi.module.config.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 统一配置中心实体（MyBatis-Plus 映射表 {@code sys_config}，配置化升级 V2）。
 *
 * <p>二级 scope：{@code GLOBAL}（scope_id=NULL，平台默认）与 {@code TENANT}（scope_id=租户 id，覆盖全局）；
 * 解析顺序 TENANT → GLOBAL → 代码默认（见 {@code ConfigService}）。本表列入租户拦截器 IGNORE 名单，
 * 所有查询在服务层显式携带 scope 条件。
 *
 * <p>{@code valueJson} 存任意 JSON（标量/对象/数组），使"问数结果展示、样式、图表、NL2SQL 策略"
 * 等异构配置共用一张表而无需为每类配置建列。
 *
 * <p><b>{@code managed}（平台管控）</b>：仅对 GLOBAL 行有意义。置 1 时该 (category, configKey) 在
 * <b>所有租户</b>被锁定为平台值——解析时忽略 TENANT 覆盖、租户写入直接拒绝（{@code ConfigService}），
 * 从而把"平台默认 + 租户可覆盖"升级为"平台统一管控"。关闭管控后租户既有覆盖行<b>原样复活</b>
 * （管控期间不会被删除），故该开关可安全来回切换。
 */
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeType;
    private Long scopeId;
    private String category;
    private String configKey;
    private String valueJson;
    private String description;
    private Integer version;
    private Integer enabled;
    private Integer managed;

    /**
     * 创建 / 更新时间由数据库维护（{@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP}）。
     *
     * <p>{@code updateStrategy = NEVER}：{@code upsert} 是"先 {@code selectOne} 再 {@code updateById}"，
     * MyBatis-Plus 默认按 NOT_NULL 策略把实体里非空字段一并写回——若不排除，会把读出来的旧
     * {@code updatedAt} 原样写回，使库上的 {@code ON UPDATE CURRENT_TIMESTAMP} <b>永不触发</b>、
     * 时间戳被冻结，掩盖"这行配置何时被改过"。故禁止 ORM 写时间戳，交给数据库维护。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getValueJson() {
        return valueJson;
    }

    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getManaged() {
        return managed;
    }

    public void setManaged(Integer managed) {
        this.managed = managed;
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
