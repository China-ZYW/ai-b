package com.aibi.module.dashboard.dto;

/**
 * 大屏公开访问结果（问题2）：把"可看/需密码/密码错/已过期/不存在"统一为一个响应，
 * 前端据 {@link Status} 决定直接渲染实时大屏、弹密码框还是提示失效。
 *
 * @param status           访问状态
 * @param requiresPassword 是否需要输入密码（前端据此弹框）
 * @param data             仅当 {@code status==OK} 时非空：大屏配置 + 各 widget 实时数据
 */
public record DashboardAccessResponse(
        Status status,
        Boolean requiresPassword,
        DashboardDataVO data) {

    /** 公开访问状态。 */
    public enum Status { OK, PASSWORD_REQUIRED, PASSWORD_WRONG, EXPIRED, NOT_FOUND }

    public static DashboardAccessResponse ok(DashboardDataVO data) {
        return new DashboardAccessResponse(Status.OK, false, data);
    }

    public static DashboardAccessResponse passwordRequired() {
        return new DashboardAccessResponse(Status.PASSWORD_REQUIRED, true, null);
    }

    public static DashboardAccessResponse passwordWrong() {
        return new DashboardAccessResponse(Status.PASSWORD_WRONG, true, null);
    }

    public static DashboardAccessResponse expired() {
        return new DashboardAccessResponse(Status.EXPIRED, false, null);
    }

    public static DashboardAccessResponse notFound() {
        return new DashboardAccessResponse(Status.NOT_FOUND, false, null);
    }
}
