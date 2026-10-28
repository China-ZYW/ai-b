package com.aibi.module.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 团队成员实体（映射表 {@code team_member}）。
 *
 * <p><b>隔离特例</b>：与 {@code user_role} 同理，本表带 {@code tenant_id} 却列入
 * {@code MybatisPlusConfig.IGNORE_TABLES}——登录时要按 {@code uid} 反查其所属团队集合，
 * 早于 {@code TenantContext} 建立。列入后所有读写必须显式带 {@code tenant_id}，
 * 读出口用 {@code TenantAssertion.assertMatch} 兜底。
 *
 * <p>{@code status=0} 表示已移除，但<b>保留行以留痕</b>（审计与"重新邀请"需知道历史归属），
 * 因此查询成员列表必须显式加 {@code status=1}，不能只看行是否存在。
 * 移除成员时除置 {@code status=0} 外，还必须 {@code app_user.token_version++} 并吊销其
 * {@code login_session}、失效其创建的 {@code share_link} 与 {@code dashboard.share_token}
 * ——公开链接不走 JWT，仅靠会话吊销拦不住（见设计文档 8.6）。
 */
@TableName("team_member")
public class TeamMember {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long teamId;
    /** {@code app_user.id}。 */
    private Long uid;
    /** TEAM_OWNER/TEAM_EDITOR/TEAM_VIEWER，见 {@code TeamRole}。 */
    private String teamRole;
    /** 1=正常 0=已移除（保留行以留痕）。 */
    private Integer status;
    private LocalDateTime joinedAt;
    /** 邀请人 uid（回填的历史成员为空）。 */
    private Long invitedBy;
    private LocalDateTime leftAt;

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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getTeamRole() {
        return teamRole;
    }

    public void setTeamRole(String teamRole) {
        this.teamRole = teamRole;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(Long invitedBy) {
        this.invitedBy = invitedBy;
    }

    public LocalDateTime getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(LocalDateTime leftAt) {
        this.leftAt = leftAt;
    }

    public boolean isActive() {
        return status == null || status == 1;
    }
}
