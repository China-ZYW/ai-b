package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 席位与配额调整表单（§9.2 运营组 · 席位与配额）。
 *
 * <p>逐租户精确调整：席位上限必填；每日/累计上限留空（{@code null}）表示保持不变，
 * 只调其中一项时无需回填另一项。三个 {@code -1} 一律表示“不限”，故各值域为 {@code >= -1}
 * （服务层强校验，非法值抛 {@code PARAM_ERROR}）。
 *
 * <p>不含 {@code seatUsed}：已用席位由邀请接受/成员移除事务维护，运营不可直接改写，
 * 否则会与 {@code team_member} 在册数失配。
 *
 * @param seatLimit        席位上限（{@code -1} 不限），必填
 * @param dailyLimit       每日生成次数上限（{@code -1} 不限），{@code null} 保持不变
 * @param totalLimit       累计总次数上限（{@code -1} 不限），{@code null} 保持不变
 * @param datasourceLimit  数据源数量上限（{@code -1} 不限），{@code null} 保持不变
 */
public record SeatQuotaForm(
        @NotNull(message = "席位上限不能为空") Integer seatLimit,
        Integer dailyLimit,
        Long totalLimit,
        Integer datasourceLimit) {
}
