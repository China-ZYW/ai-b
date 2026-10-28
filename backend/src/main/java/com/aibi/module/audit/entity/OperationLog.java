package com.aibi.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 全局操作日志（请求级自动捕获，与 {@code auth_audit_log} 事件驱动审计互补）。
 *
 * <p>本表记录的是「谁在什么时间对哪个接口发了什么请求、结果如何、耗时多久」，属横切视角；
 * {@code auth_audit_log} 记录的是「权限/登录等关键事件的业务语义」。二者并存、互不替代：
 * 操作日志覆盖全量请求（含被权限拦截的 403），事件审计只覆盖被显式埋点的关键动作。
 *
 * <p>表本身<b>跨租户</b>（不随租户隔离）：查询时由检索接口按当前身份显式收敛范围
 * （平台后台跨租户、组织内钉死当前租户）。故用 {@link InterceptorIgnore#tenantLine()} 关闭 MP 租户列注入，
 * 否则 {@code TenantLineInnerInterceptor} 会把 {@code tenant_id} 当作隔离条件导致查询错位。
 */
@TableName("audit_log")
@InterceptorIgnore(tenantLine = "true")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件归属租户（0=平台域）；仅作归属标记，不作隔离条件。 */
    private Long tenantId;

    /** 操作人 uid（匿名请求为空）。 */
    private Long userId;

    /** 操作人登录名。 */
    private String username;

    /** 组织角色码（逗号分隔，脱敏展示用）。 */
    private String orgRoles;

    /** 平台角色码（逗号分隔，仅平台域非空）。 */
    private String platformRoles;

    /** 请求追踪 id（与日志链路追踪对齐）。 */
    private String requestId;

    /** 业务模块（由路径推导：org/chat/dashboard/admin...）。 */
    private String module;

    /** 可读动作（{@code METHOD /path}，路径去参）。 */
    private String action;

    /** HTTP 方法。 */
    private String method;

    /** 请求路径（不含查询串）。 */
    private String uri;

    /** 查询串（原始）。 */
    private String queryString;

    /** 请求体（脱敏 + 截断，禁存口令/凭证）。 */
    private String requestBody;

    /** 客户端 IP（X-Forwarded-For 首段）。 */
    private String ip;

    /** User-Agent。 */
    private String userAgent;

    /** HTTP 状态码（401/403/500 等便于事后排查）。 */
    private Integer httpStatus;

    /** 异常信息（仅失败时非空，截断）。 */
    private String errorMsg;

    /** 处理耗时（毫秒）。 */
    private Long durationMs;

    /** 创建时间（落库时刻）。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOrgRoles() {
        return orgRoles;
    }

    public void setOrgRoles(String orgRoles) {
        this.orgRoles = orgRoles;
    }

    public String getPlatformRoles() {
        return platformRoles;
    }

    public void setPlatformRoles(String platformRoles) {
        this.platformRoles = platformRoles;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
