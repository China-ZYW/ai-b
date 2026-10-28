package com.aibi.module.mcp.dto;

/**
 * 创建令牌的响应：附带<b>仅此一次</b>的明文令牌。
 *
 * <p>服务端只存哈希，明文不落库也无任何缓存，故此响应是拿到明文的唯一时机；
 * 前端必须在弹窗里提示"请立即保存，关闭后无法再次查看"。
 */
public record McpPatCreated(McpPatVO pat, String token) {
}
