package com.aibi.module.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 保存的查询模板（SQL 编辑器 · 高级查询 P1，映射表 {@code saved_query}）。
 *
 * <p>与 {@code query_history} 的分工：history 记"跑过什么"，本表记"留下来复用什么"。
 * {@code sqlTemplate} 中的 <b>{@code :param}</b> 是命名占位符，运行期由
 * {@link com.aibi.module.query.service.QueryService} 改写成 {@code ?} 并走
 * {@code PreparedStatement} 绑定——<b>绝不拼接进 SQL 字符串</b>，这是本表的注入红线。
 *
 * <p>带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("saved_query")
public class SavedQuery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long uid;
    private Long datasourceId;

    /** 模板名称（(tenant_id, uid, name) 唯一）。 */
    private String name;

    /** 带 :param 命名占位符的 SQL 模板。 */
    private String sqlTemplate;

    /** 参数名，逗号分隔；顺序与 :param 在模板中出现的顺序一致。 */
    @TableField("param_names")
    private String paramNames;

    private String description;

    /** 构造器规格 JSON（BuilderSpec）；来自可视化构造器时非空，SQL 编辑器来源为 null。 */
    private String spec;

    /** 可见性：{@code PRIVATE}=仅本人（默认，存量行为不变）/ {@code SHARED}=组织共享（成员可查/可跑/可设定时）。 */
    private String visibility;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public SavedQuery() {
    }

    /**
     * 便捷构造（保存入口）。
     *
     * @param paramNames 参数名，无参传 null
     */
    public SavedQuery(Long tenantId, Long uid, Long datasourceId, String name,
                      String sqlTemplate, String paramNames, String description) {
        this.tenantId = tenantId;
        this.uid = uid;
        this.datasourceId = datasourceId;
        this.name = name;
        this.sqlTemplate = sqlTemplate;
        this.paramNames = paramNames;
        this.description = description;
    }

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

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSqlTemplate() {
        return sqlTemplate;
    }

    public void setSqlTemplate(String sqlTemplate) {
        this.sqlTemplate = sqlTemplate;
    }

    public String getParamNames() {
        return paramNames;
    }

    public void setParamNames(String paramNames) {
        this.paramNames = paramNames;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
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
