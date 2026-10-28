package com.aibi.module.mcp.service;

/**
 * MCP 认证失败：由 {@code McpController} 转成 HTTP 401 + {@code WWW-Authenticate} 响应头。
 *
 * <p>刻意不复用 {@code BizException}：MCP 的 401 必须按 RFC 6750 带上 {@code WWW-Authenticate}
 * （客户端据此发现授权服务器与 resource 参数），走统一业务异常处理会丢掉这个头。
 */
public class McpAuthException extends RuntimeException {

    public McpAuthException(String message) {
        super(message);
    }
}
