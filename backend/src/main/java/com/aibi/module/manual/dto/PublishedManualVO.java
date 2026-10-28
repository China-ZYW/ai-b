package com.aibi.module.manual.dto;

import java.time.LocalDateTime;

/**
 * 用户手册阅读视图（全体用户可见）：只暴露已发布快照，不含草稿。
 *
 * @param title       已发布标题
 * @param content     已发布正文（Markdown）
 * @param version     发布版本号
 * @param publishedAt 最近发布时间
 */
public record PublishedManualVO(
        String title,
        String content,
        Integer version,
        LocalDateTime publishedAt
) {
}
