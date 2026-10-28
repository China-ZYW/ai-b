package com.aibi.module.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

/**
 * 每日配额用量实体（映射表 {@code quota_usage}）：按“租户 + 自然日”持久化当日已用次数。
 *
 * <p>替代早期的进程内计数，使配额在重启后不丢失、并支持多实例部署。计数增减由
 * {@link com.aibi.module.quota.mapper.QuotaUsageMapper} 的原子语句完成，唯一键 {@code (tenant_id, usage_date)}。
 */
@TableName("quota_usage")
public class QuotaUsage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDate usageDate;
    private Integer used;

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

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public Integer getUsed() {
        return used;
    }

    public void setUsed(Integer used) {
        this.used = used;
    }
}
