package com.aibi.module.mcp.dto;

/**
 * JSON-RPC 2.0 错误对象（{@code error} 成员）。
 *
 * <p>MCP 在 JSON-RPC 标准码之上约定：工具执行失败也返回 <b>HTTP 200 + result.isError=true</b>
 * （见 {@code tools/call}）——因为"工具报错"是给模型看的可恢复信息，不是协议级故障。
 * 只有协议/鉴权问题才用这里的 {@code error} 或 HTTP 401。
 */
public record JsonRpcError(int code, String message, Object data) {

    /** 解析失败（请求不是合法 JSON）。 */
    public static final int PARSE_ERROR = -32700;
    /** 请求对象不符合 JSON-RPC 2.0 结构。 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法不存在或不可用。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数无效（缺必填、类型不对）。 */
    public static final int INVALID_PARAMS = -32602;
    /** 服务端内部错误。 */
    public static final int INTERNAL_ERROR = -32603;

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }
}
