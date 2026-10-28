package com.aibi.module.query.completion;

/**
 * 一条补全候选。
 *
 * <p>{@code text} 是<b>插入</b>的内容（补全框里"回车"后写进编辑器的字符串），
 * {@code label} 是<b>展示</b>内容（下拉里看到的主标题）。二者分离是为了承载别名：
 * 语义层里 {@code create_time} 的业务别名是"下单时间"，用户看到的应为中文，落地的仍是原列名。
 *
 * @param text   插入文本
 * @param label  展示标题（可与 text 不同，如中文别名）
 * @param detail 右侧灰色说明（类型 / 表名 / 来源）
 * @param kind   类别，供编辑器着色：TABLE / COLUMN / KEYWORD / SNIPPET
 */
public record CompletionItem(String text, String label, String detail, String kind) {

    public static CompletionItem of(String text, String label, String kind) {
        return new CompletionItem(text, label, null, kind);
    }
}
