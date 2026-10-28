package com.aibi.module.mcp.service;

/**
 * MCP 进度下沉接口：把问数引擎的分阶段回调（{@link com.aibi.module.chat.nl2sql.ProgressListener}）
 * 转译为 MCP 的 {@code notifications/progress} 通知。
 *
 * <p>为何需要单独一层：MCP 进度通知的投递方式取决于传输形态——
 * <ul>
 *   <li><b>SSE 模式</b>（{@code Accept: text/event-stream}）：Controller 把通知写成独立的
 *       {@code event: message} SSE 事件实时下发，避免长问数（实测 200~260s）期间连接空转被代理/客户端掐断；
 *   <li><b>JSON 模式</b>（普通 {@code application/json}）：没有可供进度流动的长连接，用 {@link #NOOP} 丢弃进度。</li>
 * </ul>
 * 引擎侧只认 {@link com.aibi.module.chat.nl2sql.ProgressListener}，本接口负责"进度往哪写"，
 * 由 Controller 在两种模式下提供不同实现，服务层（{@link McpServerService}）只管把阶段映射成百分比后调用。
 */
@FunctionalInterface
public interface McpProgressSink {

    /** 空实现：JSON 模式或客户端未请求进度时使用。 */
    McpProgressSink NOOP = (progressToken, stage, progress, message) -> { };

    /**
     * 上报一个进度事件。
     *
     * @param progressToken 客户端在 {@code tools/call} 请求的 {@code params.meta.progressToken}
     *                       （可为 null，表示客户端未启用进度通知）
     * @param stage         阶段名（与 {@code ProgressListener} 常量一致，如 {@code generate}/{@code execute}）
     * @param progress      0~100 的整数进度百分比
     * @param message       面向用户的简短进度描述（可空）
     */
    void onProgress(Object progressToken, String stage, int progress, String message);
}
