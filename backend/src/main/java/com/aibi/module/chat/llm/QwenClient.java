package com.aibi.module.chat.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通义千问（Qwen）客户端，作为 DeepSeek 的降级备选。走 DashScope 的 OpenAI 兼容模式端点
 * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，公共逻辑见 {@link AbstractOpenAiCompatibleClient}。
 *
 * <p>未配置 {@code api-key} 时 {@link #isAvailable()} 返回 false，{@link LlmRouter} 会自动跳过，
 * 因此仅配 DeepSeek 也能正常运行；两者都配置后即获得跨供应商容灾能力。
 */
@Component
public class QwenClient extends AbstractOpenAiCompatibleClient {

    public QwenClient(@Value("${aibi.llm.fallback.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                      @Value("${aibi.llm.fallback.api-key:}") String apiKey,
                      @Value("${aibi.llm.fallback.model:qwen-plus}") String model,
                      @Value("${aibi.llm.fallback.timeout-seconds:30}") long timeoutSeconds) {
        super(baseUrl, apiKey, model, timeoutSeconds);
    }

    @Override
    public String name() {
        return "qwen";
    }
}
