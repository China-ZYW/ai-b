package com.aibi.module.chat.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 客户端（默认主力模型）。走 OpenAI 兼容协议，公共逻辑见 {@link AbstractOpenAiCompatibleClient}。
 *
 * <p>由 PoC 的 {@code DeepSeekClient} 演进而来：从 {@code nl2sql} 包迁入 {@code llm} 包并实现 {@link LlmClient}，
 * 使核心引擎面向接口编程、支持多模型降级（见 {@link LlmRouter}）。
 */
@Component
public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    public DeepSeekClient(@Value("${aibi.llm.base-url:https://api.deepseek.com}") String baseUrl,
                          @Value("${aibi.llm.api-key:}") String apiKey,
                          @Value("${aibi.llm.model:deepseek-chat}") String model,
                          @Value("${aibi.llm.timeout-seconds:30}") long timeoutSeconds) {
        super(baseUrl, apiKey, model, timeoutSeconds);
    }

    @Override
    public String name() {
        return "deepseek";
    }
}
