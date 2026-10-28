package com.aibi.module.chat.llm.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.llm.ConfiguredLlmClient;
import com.aibi.module.chat.llm.LlmClient;
import com.aibi.module.chat.llm.entity.LlmConfig;
import com.aibi.module.chat.llm.mapper.LlmConfigMapper;
import com.aibi.module.datasource.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型配置服务：解析“全局默认 + 租户覆盖”的有效链路、加解密 API Key、构建临时客户端、连通性测试。
 *
 * <p>解析策略：若租户存在已启用的专属配置，则<b>覆盖</b>使用租户配置；否则回退全局默认（{@code tenant_id IS NULL}）。
 * 两者皆无时返回空链，由 {@link com.aibi.module.chat.llm.LlmRouter} 回退到 {@code application.yml} 的 env Bean 链。
 * API Key 全程以 {@link CredentialCipher} 密文存储，仅在构建客户端/测试时解密，绝不返回明文或打日志。
 */
@Service
public class LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);
    private static final int DEFAULT_TIMEOUT = 30;

    private final LlmConfigMapper llmConfigMapper;
    private final CredentialCipher cipher;

    public LlmConfigService(LlmConfigMapper llmConfigMapper, CredentialCipher cipher) {
        this.llmConfigMapper = llmConfigMapper;
        this.cipher = cipher;
    }

    /** 解析某租户生效的配置列表（租户专属优先，否则全局默认）。 */
    public List<LlmConfig> resolveChain(Long tenantId) {
        if (tenantId != null) {
            List<LlmConfig> tenantConfigs = llmConfigMapper.selectEnabledByTenant(tenantId);
            if (!tenantConfigs.isEmpty()) {
                return tenantConfigs;
            }
        }
        return llmConfigMapper.selectGlobalEnabled();
    }

    /** 依解析出的配置构建可调用客户端链（解密 key、跳过空 key）。 */
    public List<LlmClient> buildChain(Long tenantId) {
        List<LlmClient> clients = new ArrayList<>();
        for (LlmConfig cfg : resolveChain(tenantId)) {
            String apiKey = cipher.decrypt(cfg.getApiKeyCipher());
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("大模型配置 [{}] 缺少可用 API Key，已跳过", cfg.getName());
                continue;
            }
            clients.add(toClient(cfg, apiKey));
        }
        return clients;
    }

    /**
     * 连通性测试：用给定配置发一条最小 prompt。明文 key 优先（新建/改 key 场景），否则用已存密文。
     *
     * @throws BizException 缺少 key 或调用失败
     */
    public void testConnection(LlmConfig cfg, String plainKeyOverride) {
        String apiKey = (plainKeyOverride != null && !plainKeyOverride.isBlank())
                ? plainKeyOverride : cipher.decrypt(cfg.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID, "缺少 API Key，无法测试连通性");
        }
        toClient(cfg, apiKey).chat("ping");
    }

    private LlmClient toClient(LlmConfig cfg, String apiKey) {
        String displayName = cfg.getName() == null || cfg.getName().isBlank() ? cfg.getProvider() : cfg.getName();
        int timeout = cfg.getTimeoutSeconds() == null ? DEFAULT_TIMEOUT : cfg.getTimeoutSeconds();
        return new ConfiguredLlmClient(displayName, cfg.getBaseUrl(), apiKey, cfg.getModel(), timeout,
                cfg.getExtraBody(), cfg.getMaxTokens());
    }

    /** 加密明文 API Key（供 admin 写入）。 */
    public String encryptKey(String plain) {
        return cipher.encrypt(plain);
    }
}
