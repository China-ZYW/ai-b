package com.aibi.module.mcp.service;

import com.aibi.module.mcp.dto.JsonRpcError;

/**
 * MCP 的协议级/业务级失败：由 {@code McpController} 转成 JSON-RPC {@code error} 对象。
 *
 * @param code    JSON-RPC 错误码（见 {@link JsonRpcError} 常量）
 * @param message 面向客户端的英文/中文说明
 */
public class McpRpcException extends RuntimeException {

    private final int code;

    public McpRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static McpRpcException invalidParams(String message) {
        return new McpRpcException(JsonRpcError.INVALID_PARAMS, message);
    }

    public static McpRpcException methodNotFound(String message) {
        return new McpRpcException(JsonRpcError.METHOD_NOT_FOUND, message);
    }

    public static McpRpcException invalidRequest(String message) {
        return new McpRpcException(JsonRpcError.INVALID_REQUEST, message);
    }

    public int code() {
        return code;
    }
}
