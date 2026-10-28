package com.aibi.module.chat.distill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 待治理问题台账行（映射表 {@code governance_issue}，租户隔离表，T11-1 二期 B）。
 *
 * <p>一行代表一个 {@code (tenant_id, datasource_id, key_hash, signal)} 的治理问题簇，
 * 承载处置态（OPEN/CONVERTED/DISMISSED）、转写产出锚点与推送去重时间戳。
 *
 * <p>本表不在 {@code IGNORE_TABLES}，{@code tenant_id} 由 MyBatis-Plus 拦截器自动注入。
 */
@TableName("governance_issue")
public class GovernanceLedgerEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasourceId;
    /** MySQL 保留字，需反引号转义。 */
    @TableField("`signal`")
    private String signal;
    /** 归一键（截断至 512 字符，仅用于展示/查询）。 */
    private String normalizedKey;
    /** normalized_key 全长 SHA-256 十六进制（唯一键参与列，防截断碰撞）。 */
    private String keyHash;
    /** OPEN / CONVERTED / DISMISSED。 */
    private String status;
    /** 最近一次代表问法（推送/展示用）。 */
    private String question;
    /** 最近一次扫描出现次数。 */
    private Integer occurrences;
    /** 处置产出类型：METRIC / FEWSHOT / null。 */
    private String refType;
    /** 转写产出 id。 */
    private Long refId;
    /** 忽略原因。 */
    private String dismissReason;
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;
    private Long resolvedBy;
    /** 最近推送时间（去重闸）。 */
    private LocalDateTime notifiedAt;

    // ==================== getter/setter ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }

    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String normalizedKey) { this.normalizedKey = normalizedKey; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Integer getOccurrences() { return occurrences; }
    public void setOccurrences(Integer occurrences) { this.occurrences = occurrences; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public String getDismissReason() { return dismissReason; }
    public void setDismissReason(String dismissReason) { this.dismissReason = dismissReason; }

    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public Long getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(Long resolvedBy) { this.resolvedBy = resolvedBy; }

    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }
}
