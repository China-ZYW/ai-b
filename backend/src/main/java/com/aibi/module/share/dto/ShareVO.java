package com.aibi.module.share.dto;

import java.time.LocalDateTime;

/**
 * 分享链接视图（M7，给创建者/我的分享列表）：绝不返回结果快照与密码。
 *
 * @param id           分享 id
 * @param token        公开访问令牌
 * @param url          可直接分享的公开访问地址
 * @param question     分享的问题
 * @param expireAt     过期时间（空=永不过期）
 * @param watermark    是否加水印
 * @param hasPassword  是否设置了访问密码
 * @param viewCount    累计访问次数
 * @param createdAt    创建时间
 * @param type         分享类型：metric=指标/结果快照分享（share_link），dashboard=驾驶舱/大屏分享（dashboard 表）
 */
public record ShareVO(
        Long id,
        String token,
        String url,
        String question,
        LocalDateTime expireAt,
        boolean watermark,
        boolean hasPassword,
        Integer viewCount,
        LocalDateTime createdAt,
        String type
) {
}
