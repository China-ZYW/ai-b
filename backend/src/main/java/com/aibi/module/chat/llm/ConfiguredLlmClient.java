package com.aibi.module.chat.llm;

/**
 * 由 {@code llm_config} 动态构建的临时客户端：把一行大模型配置（解密后的 key/baseUrl/model）适配为
 * {@link LlmClient}，供 {@link LlmRouter} 按当前租户解析出的链路逐个尝试。公共 HTTP 逻辑见
 * {@link AbstractOpenAiCompatibleClient}。
 *
 * <p>按需构建、用完即弃，确保始终反映最新配置（热更新无需重启）。
 */
public class ConfiguredLlmClient extends AbstractOpenAiCompatibleClient {

    private final String displayName;

    public ConfiguredLlmClient(String displayName, String baseUrl, String apiKey, String model, long timeoutSeconds) {
        this(displayName, baseUrl, apiKey, model, timeoutSeconds, null, null);
    }

    public ConfiguredLlmClient(String displayName, String baseUrl, String apiKey, String model, long timeoutSeconds,
                               String extraBodyJson, Integer maxTokens) {
        super(baseUrl, apiKey, model, timeoutSeconds, extraBodyJson, maxTokens);
        this.displayName = displayName;
    }

    @Override
    public String name() {
        return displayName;
    }
}
