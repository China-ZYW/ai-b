package com.aibi.module.share.dto;

/**
 * 公开访问分享的结果（M7）：把"可看/需密码/密码错/已过期/不存在"统一为一个响应，
 * 前端据 {@link Status} 决定是直接渲染、弹密码框还是提示失效。
 *
 * @param status           访问状态
 * @param requiresPassword 是否需要输入密码（前端据此弹框）
 * @param view             仅当 {@code status==OK} 时非空的展示数据
 */
public record ShareAccessResponse(
        Status status,
        Boolean requiresPassword,
        SharedView view
) {

    /** 公开访问状态。 */
    public enum Status { OK, PASSWORD_REQUIRED, PASSWORD_WRONG, EXPIRED, NOT_FOUND }

    public static ShareAccessResponse ok(SharedView view) {
        return new ShareAccessResponse(Status.OK, false, view);
    }

    public static ShareAccessResponse passwordRequired() {
        return new ShareAccessResponse(Status.PASSWORD_REQUIRED, true, null);
    }

    public static ShareAccessResponse passwordWrong() {
        return new ShareAccessResponse(Status.PASSWORD_WRONG, true, null);
    }

    public static ShareAccessResponse expired() {
        return new ShareAccessResponse(Status.EXPIRED, false, null);
    }

    public static ShareAccessResponse notFound() {
        return new ShareAccessResponse(Status.NOT_FOUND, false, null);
    }
}
