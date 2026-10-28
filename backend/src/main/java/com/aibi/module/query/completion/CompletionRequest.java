package com.aibi.module.query.completion;

/**
 * 补全请求（SQL 编辑器 · 自动补全 P2）。
 *
 * @param datasourceId 数据源 id
 * @param prefix       光标前的文本片段（编辑器已含当前行，不含已输入的前缀本身；可空）
 * @param tableName    当前片段所属的表（编辑器解析 FROM 后回填，为空表示尚未确定）
 * @param limit        每条来源最多返回多少项（服务端也会自行截断）
 */
/**
 * <b>limit 必须是 {@code Integer} 而不是 {@code int}</b>：控制器那边是
 * {@code @RequestParam(required = false)}，前端不传它就是 null。若这里声明成基本类型
 * {@code int}，拆箱发生在<b>调用处</b>（{@code new CompletionRequest(..., limit)} 那一行），
 * 堆栈直接指向控制器方法，看上去像"控制器自己炸了"，与真正因由（默认值兜底没兜住 null）毫无关系。
 * 用装箱类型把 null 接住，再由 compact constructor 兜到默认值，NPE 才不会有机会发生。
 */
public record CompletionRequest(Long datasourceId, String prefix, String tableName, Integer limit) {

    public CompletionRequest {
        prefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
        // 顺序不能颠倒：先接住 null，再钳位（compact constructor 里赋值之后的 limit 已是 int）
        limit = java.util.Objects.requireNonNullElse(limit, 50);
        if (limit <= 0) {
            limit = 50;
        }
    }
}
