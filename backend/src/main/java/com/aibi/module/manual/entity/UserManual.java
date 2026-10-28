package com.aibi.module.manual.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户手册实体（MyBatis-Plus 映射表 {@code user_manual}，平台级全局单例，列入租户 IGNORE 名单）。
 *
 * <p>采用「草稿 + 已发布快照」双列：{@code title/content} 是超管正在编辑的草稿，
 * {@code publishedTitle/publishedContent} 是最近一次发布、对用户可见的快照。编辑草稿不影响线上，
 * 只有显式「发布」才把草稿拷贝到发布列并 {@code version+1}，实现安全的「先改后发布」。
 *
 * <p>{@code publishedContent} 为 {@code null} 表示从未发布，用户端读到空（前端提示"手册尚未发布"）。
 */
@TableName("user_manual")
public class UserManual {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private String publishedTitle;
    private String publishedContent;
    /** DRAFT=有未发布改动 / PUBLISHED=草稿与发布版一致。 */
    private String status;
    private Integer version;
    private Long updatedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPublishedTitle() {
        return publishedTitle;
    }

    public void setPublishedTitle(String publishedTitle) {
        this.publishedTitle = publishedTitle;
    }

    public String getPublishedContent() {
        return publishedContent;
    }

    public void setPublishedContent(String publishedContent) {
        this.publishedContent = publishedContent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
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
