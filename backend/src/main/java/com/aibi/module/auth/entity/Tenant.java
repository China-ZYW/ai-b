package com.aibi.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 租户实体（映射表 {@code tenant}）。
 *
 * <p>注册时自助创建；表无 AUTO_INCREMENT，故主键用雪花算法（{@link IdType#ASSIGN_ID}）生成。
 * {@code plan}/{@code isolationMode} 决定该租户的套餐与隔离策略（见《SaaS 多租户设计》3）。
 *
 * <p><b>也是“组织”实体</b>（见《角色权限体系与组织协同设计》2.2）：个人版 = 单人组织，
 * 不另建一套表。席位（{@code seatLimit}/{@code seatUsed}）与组织权限版本（{@code permVersion}）
 * 均在本行上维护：前者是邀请接受时的原子占位对象（两条条件 UPDATE 同事务，锁序固定为
 * 先 {@code invitation} 后 {@code tenant}，否则死锁），后者是 JWT claim {@code pv} 的组织级分量。
 */
@TableName("tenant")
public class Tenant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String plan;
    private String isolationMode;
    private Integer status;
    private LocalDateTime expireAt;
    private Integer dailyLimit;
    private Long totalLimit;
    private Long totalUsed;
    /** 席位上限（-1=不限）；存量默认 -1，等价于升级前的“不限制成员数”。 */
    private Integer seatLimit;
    /** 已用席位；与 {@code team_member} 在册数保持一致，邀请接受时原子 +1。 */
    private Integer seatUsed;
    /** 数据源数量上限（-1=不限）；按套餐初值（FREE=1/PRO=5/ENT=-1），根除"无限数据源"超卖。 */
    private Integer datasourceLimit;
    /** 组织权限版本号：角色/权限/成员变更时 +1，使旧 JWT 的 {@code pv} 失配而失效。 */
    private Long permVersion;
    /** 默认团队（{@code team.code='default'}）：承接未显式归属团队的资源与成员。 */
    private Long defaultTeamId;
    /** 组织所有者 uid（回填为该租户最早的 {@code app_user.id}）。 */
    private Long ownerUid;
    /** 自助注册来源 IP（每 IP 仅可开通一个免费工作区，遏制体验版批量刷号）。 */
    private String registerIp;
    /** 1=允许 VIEWER 问数（组织级开关，可关闭以把只读成员限为“仅看驾驶舱”）。 */
    private Integer allowViewerQuery;
    /** 1=允许成员转发邀请链接自助入组（默认关闭，防链接外流）。 */
    private Integer allowSelfInvite;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getIsolationMode() {
        return isolationMode;
    }

    public void setIsolationMode(String isolationMode) {
        this.isolationMode = isolationMode;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Integer getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(Integer dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public Long getTotalLimit() {
        return totalLimit;
    }

    public void setTotalLimit(Long totalLimit) {
        this.totalLimit = totalLimit;
    }

    public Long getTotalUsed() {
        return totalUsed;
    }

    public void setTotalUsed(Long totalUsed) {
        this.totalUsed = totalUsed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getSeatLimit() {
        return seatLimit;
    }

    public void setSeatLimit(Integer seatLimit) {
        this.seatLimit = seatLimit;
    }

    public Integer getSeatUsed() {
        return seatUsed;
    }

    public void setSeatUsed(Integer seatUsed) {
        this.seatUsed = seatUsed;
    }

    public Integer getDatasourceLimit() {
        return datasourceLimit;
    }

    public void setDatasourceLimit(Integer datasourceLimit) {
        this.datasourceLimit = datasourceLimit;
    }

    public Long getPermVersion() {
        return permVersion;
    }

    public void setPermVersion(Long permVersion) {
        this.permVersion = permVersion;
    }

    /** 权限版本容错取值（存量行未回填时为 null，JWT 的 {@code pv} 不可为 null）。 */
    public long permVersionOrZero() {
        return permVersion == null ? 0L : permVersion;
    }

    public Long getDefaultTeamId() {
        return defaultTeamId;
    }

    public void setDefaultTeamId(Long defaultTeamId) {
        this.defaultTeamId = defaultTeamId;
    }

    public Long getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(Long ownerUid) {
        this.ownerUid = ownerUid;
    }

    public String getRegisterIp() {
        return registerIp;
    }

    public void setRegisterIp(String registerIp) {
        this.registerIp = registerIp;
    }

    public Integer getAllowViewerQuery() {
        return allowViewerQuery;
    }

    public void setAllowViewerQuery(Integer allowViewerQuery) {
        this.allowViewerQuery = allowViewerQuery;
    }

    public Integer getAllowSelfInvite() {
        return allowSelfInvite;
    }

    public void setAllowSelfInvite(Integer allowSelfInvite) {
        this.allowSelfInvite = allowSelfInvite;
    }

    /** 席位是否已用满（{@code seatLimit<0} 表示不限）。仅用于提示，真正卡控靠邀请事务里的条件 UPDATE。 */
    public boolean isSeatFull() {
        if (seatLimit == null || seatLimit < 0) {
            return false;
        }
        return seatUsed != null && seatUsed >= seatLimit;
    }

    /** VIEWER 是否可问数（默认可以，与升级前行为一致）。 */
    public boolean viewerCanQuery() {
        return allowViewerQuery == null || allowViewerQuery == 1;
    }

    /** 是否允许成员转发邀请链接自助入组（默认关闭）。 */
    public boolean selfInviteAllowed() {
        return allowSelfInvite != null && allowSelfInvite == 1;
    }
}
