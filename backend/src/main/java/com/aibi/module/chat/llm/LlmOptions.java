package com.aibi.module.chat.llm;

/**
 * 单次 LLM 调用的可选项：用于<b>按调用场景覆盖</b>模型行（{@code llm_config}）上的默认参数。
 *
 * <p>典型场景是「交互链路求快、后台异步求准」：模型行上默认用 {@code extra_body} 关闭"思考模式"
 * 并给一个较小的 {@code max_tokens}（交互问数必须低延迟）；而跑在后台线程的异步任务
 * （全库别名补全、AI 大屏编排）不受延迟约束，通过本选项把 thinking 覆盖为开启、并放宽
 * {@code max_tokens}——思考 token 也计入 {@code max_tokens}，沿用行上的小值会把答案挤掉。
 *
 * <p>两个字段都可空：{@code null} 表示"沿用模型行上的值"。
 *
 * @param extraBodyOverrideJson 额外请求体 JSON，逐字段并入请求体顶层，且压过模型行上的 extra_body
 * @param maxTokensOverride     本次调用的输出 token 上限；{@code null}=沿用行上的值
 */
public record LlmOptions(String extraBodyOverrideJson, Integer maxTokensOverride) {

    /** 不做任何覆盖（交互链路的默认）。 */
    public static final LlmOptions NONE = new LlmOptions(null, null);
}
