package com.aibi.module.embed.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 嵌入问数·外部用户对话日志（表 {@code chat_embed_log}）。
 *
 * <p>第三方以 iframe 嵌入时，可在 URL 上传入一个<b>外部用户唯一值</b>（{@code ?user=xxx}），
 * 用于把同一名访客的多次问数归并到其名下。本表同时承担两重职责：
 * <ul>
 *   <li><b>审计留痕</b>：每条问数附带 {@code ext_user}，可据此回溯"哪个嵌入下的哪个外部用户问了什么"；</li>
 *   <li><b>历史回看</b>：嵌入页初始化时按 {@code (embed_id, ext_user)} 拉取该用户历史，右侧悬浮面板展示。</li>
 * </ul>
 *
 * <p>每行记一次"轮次"（USER 问句 / BOT 结论摘要），同一次多轮对话共享 {@code conversation_id}。
 * {@code created_at} 由代码写入（与 {@code notify_message} 同口径，避免被 MyBatis-Plus 旧值回写冻结）。
 */
@TableName("chat_embed_log")
public class ChatEmbedLog {

    private Long id;
    private Long tenantId;
    private Long embedId;
    private String token;
    /** 嵌入方传入的外部用户唯一值（{@code ?user=}，可空=未传递）。 */
    private String extUser;
    /** 多轮会话 id（与问数主链路 {@code conversationId} 一致），用于续接与按会话回看。 */
    private String conversationId;
    /** USER / BOT。 */
    private String role;
    /** 消息内容：用户问句原文 / 机器人结论摘要（或"已返回 N 行结果"）。 */
    private String content;
    @TableField(value = "created_at", updateStrategy = FieldStrategy.NEVER)
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

    public Long getEmbedId() {
        return embedId;
    }

    public void setEmbedId(Long embedId) {
        this.embedId = embedId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getExtUser() {
        return extUser;
    }

    public void setExtUser(String extUser) {
        this.extUser = extUser;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
