package com.aibi.module.manual.dto;

import java.time.LocalDateTime;

/**
 * 用户手册管理视图（超管编辑页用）：同时给出草稿与已发布快照，便于对比"改了什么、线上是什么"。
 *
 * @param title            草稿标题
 * @param content          草稿正文（Markdown）
 * @param status           DRAFT=有未发布改动 / PUBLISHED=草稿与发布版一致
 * @param version          已发布版本号（0=从未发布）
 * @param published        是否已发布过（publishedContent 非空）
 * @param publishedTitle   已发布标题（未发布为 null）
 * @param publishedContent 已发布正文（未发布为 null）
 * @param updatedAt        草稿最近更新时间
 * @param publishedAt      最近发布时间（未发布为 null）
 */
public record ManualVO(
        String title,
        String content,
        String status,
        Integer version,
        boolean published,
        String publishedTitle,
        String publishedContent,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt
) {
}
