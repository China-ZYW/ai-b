package com.aibi.module.chat.nl2sql;

/**
 * 多轮对话中的一轮历史问答（M4）：把"同一会话之前的问法 → 已生成 SQL"回喂给 LLM，
 * 使其理解追问里的指代与省略（如"那上个月呢""只看电子类""按地区再拆一下"）。
 *
 * @param question 上一轮的自然语言问题
 * @param sql      上一轮生成并执行成功的 SQL
 */
public record ConversationTurn(String question, String sql) {
}
