package com.aibi.module.admin.dto;

import java.time.LocalDateTime;

/**
 * 租户视图对象（超管后台）：含套餐、隔离模式、使用期限、配额与用量、席位、账号数。
 *
 * @param dailyLimit 每日生成次数上限（{@code -1} 不限）
 * @param totalLimit 累计总次数上限（{@code -1} 不限）
 * @param totalUsed  累计已用次数
 * @param seatLimit  席位上限（{@code -1} 不限；存量默认 -1）
 * @param seatUsed   已用席位（与在册成员数一致）
 * @param userCount  该租户下账号数
 */
public record TenantVO(
        Long id,
        String name,
        String plan,
        String isolationMode,
        Integer status,
        LocalDateTime expireAt,
        Integer dailyLimit,
        Long totalLimit,
        Long totalUsed,
        Integer seatLimit,
        Integer seatUsed,
        long userCount,
        LocalDateTime createdAt) {
}
