package com.aibi.module.quota.dto;

import java.time.LocalDateTime;

/**
 * 配额状态（M6 + 超管配额配置）：供前端展示“今日已用 / 剩余”“累计已用 / 剩余”与使用期限。
 *
 * <p>{@code limit/used/remaining} 沿用为<b>每日</b>口径（与既有前端展示兼容）；累计与期限信息通过
 * {@code totalLimit/totalUsed/totalRemaining/expireAt/expired} 追加。{@code -1} 表示不限制。
 *
 * @param plan           当前套餐（FREE/PRO/ENTERPRISE）
 * @param limit          每日上限；{@code -1} 不限
 * @param used           今日已用次数
 * @param remaining      今日剩余次数；{@code -1} 不限
 * @param totalLimit     累计总次数上限；{@code -1} 不限
 * @param totalUsed      累计已用次数
 * @param totalRemaining 累计剩余次数；{@code -1} 不限
 * @param expireAt       使用期限（空=不过期）
 * @param expired        是否已过期
 */
public record QuotaStatus(String plan, int limit, int used, int remaining,
                          long totalLimit, long totalUsed, long totalRemaining,
                          LocalDateTime expireAt, boolean expired) {

    /** 每日是否为不限量。 */
    public boolean unlimited() {
        return limit < 0;
    }
}
