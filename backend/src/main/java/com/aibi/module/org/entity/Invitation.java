package com.aibi.module.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 组织邀请实体（映射表 {@code invitation}）。
 *
 * <p><b>隔离特例</b>：本表带 {@code tenant_id} 却列入 {@code MybatisPlusConfig.IGNORE_TABLES}——
 * 为支持「匿名接受」（凭 {@code token_hash} 全局寻址，早于 {@code TenantContext} 建立）。列入后
 * 管理侧的每条读写都<b>必须显式带 {@code tenant_id}</b>，这是唯一的隔离保障，漏写即跨组织越权。
 *
 * <p>{@code tokenHash} 只存 {@code SHA-256(明文令牌)}（{@link com.aibi.common.security.TokenDigest}），
 * 明文仅创建时返回一次。任何视图对象（{@code InvitationVO}）都不得回传本列。
 *
 * <p>接受链路（{@code accept}）已落地：被邀请者登录后凭令牌调用 {@code InvitationService.accept}，
 * 完成席位消费、跨组织成员身份建立与角色授予。{@code consumeSeat}/{@code used_count} 的原子占位已在此被真实消费。
 */
@TableName("invitation")
public class Invitation {

    /** 状态：已发出（默认，可被接受）。 */
    public static final String STATUS_SENT = "SENT";
    /** 状态：已撤销（管理员手动作废，不可再接受）。 */
    public static final String STATUS_REVOKED = "REVOKED";
    /** 状态：已接受（席位用尽或定向邀请被接受后）。 */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** 状态：已过期（{@code expire_at} 已过，由巡检或校验时置位）。 */
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 状态：待审批（{@code require_approval=1} 时接受后进入）。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 渠道：链接邀请（默认）。 */
    public static final String CHANNEL_LINK = "LINK";
    /** 渠道：定向邮箱邀请。 */
    public static final String CHANNEL_EMAIL = "EMAIL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 入组后归属团队（空=默认团队）。 */
    private Long teamId;
    /** LINK/EMAIL/SMS/BATCH/APPLY。 */
    private String channel;
    /** {@code SHA-256(明文令牌)} 十六进制，唯一索引；绝不外泄。 */
    private String tokenHash;
    /** 定向邀请：仅该邮箱可接受。 */
    private String targetEmail;
    private String targetPhone;
    /** 链接邀请：限定邮箱域（空=不限）。 */
    private String allowDomain;
    /** 接受后授予的组织角色。 */
    private String roleCode;
    /** 接受后授予的团队角色。 */
    private String teamRole;
    private Long customRoleId;
    /** 可接受次数上限（{@code -1}=不限）。 */
    private Integer seats;
    private Integer usedCount;
    /** 1=接受后进入待审批。 */
    private Integer requireApproval;
    /** 1=须登录后接受（防匿名占席）。 */
    private Integer needLogin;
    /** 空=不过期。 */
    private LocalDateTime expireAt;
    /** DRAFT/SENT/PENDING/APPROVED/REJECTED/ACCEPTED/EXPIRED/REVOKED。 */
    private String status;
    private Long invitedBy;
    private String message;
    /** 通知发送结果 PENDING/SENT/FAILED。 */
    private String notifyStatus;
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

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    public String getTargetPhone() {
        return targetPhone;
    }

    public void setTargetPhone(String targetPhone) {
        this.targetPhone = targetPhone;
    }

    public String getAllowDomain() {
        return allowDomain;
    }

    public void setAllowDomain(String allowDomain) {
        this.allowDomain = allowDomain;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getTeamRole() {
        return teamRole;
    }

    public void setTeamRole(String teamRole) {
        this.teamRole = teamRole;
    }

    public Long getCustomRoleId() {
        return customRoleId;
    }

    public void setCustomRoleId(Long customRoleId) {
        this.customRoleId = customRoleId;
    }

    public Integer getSeats() {
        return seats;
    }

    public void setSeats(Integer seats) {
        this.seats = seats;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public Integer getRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(Integer requireApproval) {
        this.requireApproval = requireApproval;
    }

    public Integer getNeedLogin() {
        return needLogin;
    }

    public void setNeedLogin(Integer needLogin) {
        this.needLogin = needLogin;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(Long invitedBy) {
        this.invitedBy = invitedBy;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getNotifyStatus() {
        return notifyStatus;
    }

    public void setNotifyStatus(String notifyStatus) {
        this.notifyStatus = notifyStatus;
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

    /** 是否已过期（{@code expire_at} 非空且早于当前时间）。 */
    public boolean isExpired() {
        return expireAt != null && expireAt.isBefore(LocalDateTime.now());
    }

    /** 是否仍有可接受名额（{@code seats=-1} 不限，否则 {@code used_count < seats}）。 */
    public boolean hasCapacity() {
        if (seats == null || seats < 0) {
            return true;
        }
        return (usedCount == null ? 0 : usedCount) < seats;
    }
}
