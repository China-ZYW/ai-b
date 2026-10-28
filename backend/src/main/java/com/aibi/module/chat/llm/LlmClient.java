package com.aibi.module.chat.llm;

/**
 * LLM 客户端抽象（§3.6 LlmClient / 开发计划 T3.4 多 LLM 抽象）。
 *
 * <p>把"用哪个大模型生成 SQL"从核心引擎里解耦：引擎只依赖本接口，具体供应商（DeepSeek、通义千问…）
 * 各自实现；{@link LlmRouter} 负责按优先级选择可用客户端并在故障时自动降级。
 *
 * <p>新增供应商只需实现本接口并注册为 Spring Bean，无需改动引擎与业务代码（开闭原则）。
 */
public interface LlmClient {

    /** 供应商标识，用于日志与路由排序（如 {@code deepseek} / {@code qwen}）。 */
    String name();

    /** 该客户端实际使用的模型名（如 {@code deepseek-chat} / {@code qwen-plus}），用于辅助交叉校验时精准指定目标模型。 */
    String model();

    /** 是否已配置可用（通常以是否配置了 API Key 判定）。不可用的客户端会被 {@link LlmRouter} 跳过。 */
    boolean isAvailable();

    /**
     * 发送单轮 prompt，返回助手文本（已去除 markdown 代码围栏）。
     *
     * @throws com.aibi.common.exception.BizException 调用失败（超时 / HTTP 异常 / 未配置）时抛出
     */
    String chat(String prompt);

    /**
     * 带调用选项的单轮 prompt：选项可覆盖模型行上的额外参数（如为后台异步任务开启"长思考"）。
     *
     * <p>默认实现忽略选项、退化为 {@link #chat(String)}，使既有实现零改动兼容。
     */
    default String chat(String prompt, LlmOptions options) {
        return chat(prompt);
    }
}
