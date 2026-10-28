package com.aibi.module.org.dto;

/**
 * 邀请创建结果（{@code member:invite:create}）。
 *
 * <p>{@code token} 为邀请令牌<b>明文</b>，仅此一次随创建结果返回；服务端只持久化其 SHA-256 哈希，
 * 后续任何查询都无法再取回明文。前端据此拼 {@code inviteUrl} 供管理员复制分发。
 *
 * @param invitation 邀请视图（不含令牌）
 * @param token      邀请令牌明文（Base64URL，256bit），仅返回一次
 * @param inviteUrl  可直接分发的接受链接，形如 {@code /org/join?token=...}
 */
public record InvitationCreateResult(
        InvitationVO invitation,
        String token,
        String inviteUrl) {
}
