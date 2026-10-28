package com.aibi.common.config;

import com.aibi.module.datasource.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.aibi.common.config.SeederSupport.notBlank;

/**
 * 全局大模型配置播种（T8.1 拆分自 {@link DataSeeder}）：
 * 仅当 llm_config 全表为空时按 yml 注入的 key 播种主/备配置，api_key 经 {@link CredentialCipher} 加密落库。
 */
@Component
public class LlmConfigSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigSeeder.class);

    private final JdbcTemplate metaJdbc;
    private final CredentialCipher cipher;

    @Value("${aibi.llm.base-url:https://api.deepseek.com}")
    private String llmBaseUrl;
    @Value("${aibi.llm.api-key:}")
    private String llmApiKey;
    @Value("${aibi.llm.model:deepseek-chat}")
    private String llmModel;
    @Value("${aibi.llm.timeout-seconds:30}")
    private int llmTimeout;
    @Value("${aibi.llm.fallback.base-url:}")
    private String llmFallbackBaseUrl;
    @Value("${aibi.llm.fallback.api-key:}")
    private String llmFallbackApiKey;
    @Value("${aibi.llm.fallback.model:}")
    private String llmFallbackModel;

    public LlmConfigSeeder(JdbcTemplate metaJdbc, CredentialCipher cipher) {
        this.metaJdbc = metaJdbc;
        this.cipher = cipher;
    }

    @Override
    public String name() {
        return "llm-config";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void seed() {
        Integer count = metaJdbc.queryForObject("SELECT COUNT(*) FROM llm_config", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        int priority = 10;
        if (notBlank(llmApiKey)) {
            insertLlmConfig("DEEPSEEK", "DeepSeek（全局默认）", llmBaseUrl, llmApiKey, llmModel, llmTimeout, priority);
            priority += 10;
        }
        if (notBlank(llmFallbackApiKey)) {
            insertLlmConfig("QWEN", "通义千问（全局备选）", llmFallbackBaseUrl, llmFallbackApiKey,
                    llmFallbackModel, llmTimeout, priority);
        }
    }

    private void insertLlmConfig(String provider, String name, String baseUrl, String apiKey,
                                 String model, int timeout, int priority) {
        metaJdbc.update(
                "INSERT INTO llm_config(tenant_id, provider, name, base_url, api_key_cipher, model, "
                        + "timeout_seconds, priority, enabled) VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, 1)",
                provider, name, baseUrl, cipher.encrypt(apiKey), model, timeout, priority);
        log.info("已播种全局大模型配置: {} ({})", name, provider);
    }
}
