package com.aibi.module.message.dto;

import com.aibi.module.message.entity.NotifyMessage;

import java.time.LocalDateTime;

/**
 * 站内信对外视图对象（收件箱接口返回）。
 *
 * <p>只暴露与"当前用户自己这封信"相关的字段；{@code tenantId} 由接口层按身份强制收敛不外露。
 */
public record NotifyMessageVO(
        Long id,
        Long uid,
        Long ruleId,
        Long recordId,
        String severity,
        String title,
        String content,
        Boolean read,
        LocalDateTime createdAt
) {
    public static NotifyMessageVO from(NotifyMessage m) {
        return new NotifyMessageVO(
                m.getId(),
                m.getUid(),
                m.getRuleId(),
                m.getRecordId(),
                m.getSeverity(),
                m.getTitle(),
                m.getContent(),
                m.getIsRead(),
                m.getCreatedAt()
        );
    }
}
