package com.aibi.common.response;

import com.aibi.common.exception.ErrorCode;

/**
 * 统一响应包装。所有 Controller 返回该结构，前端按 code 判定成败。
 *
 * @param code    0=成功，非 0=业务错误码（见 {@link ErrorCode}）
 * @param message 提示信息
 * @param data    业务数据
 * @param traceId 链路追踪 ID（骨架阶段可空，接入日志 MDC 后填充）
 */
public record R<T>(int code, String message, T data, String traceId) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "success", data, null);
    }

    public static <T> R<T> ok() {
        return new R<>(0, "success", null, null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.getCode(), message, null, null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, null);
    }
}
