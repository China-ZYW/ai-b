package com.aibi.module.dashboard.dto;

import java.time.LocalDateTime;

/**
 * 大屏分享状态视图（问题2）。
 *
 * @param enabled     是否已开启公开分享
 * @param token       分享令牌（未开启为 null）
 * @param url         可直接访问的公开链接（未开启为 null）
 * @param expireAt    过期时间（null=不过期）
 * @param hasPassword 是否设置了访问密码（不回填明文）
 */
public record DashboardShareVO(
        Boolean enabled,
        String token,
        String url,
        LocalDateTime expireAt,
        Boolean hasPassword) {
}
