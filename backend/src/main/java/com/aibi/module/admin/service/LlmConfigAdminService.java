package com.aibi.module.admin.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.admin.dto.LlmConfigForm;
import com.aibi.module.admin.dto.LlmConfigVO;
import com.aibi.module.chat.llm.entity.LlmConfig;
import com.aibi.module.chat.llm.mapper.LlmConfigMapper;
import com.aibi.module.chat.llm.service.LlmConfigService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 大模型配置管理服务（超管后台）：全局默认 + 租户覆盖的 CRUD、启停、连通性测试。
 *
 * <p>{@code llm_config} 在租户拦截器 IGNORE 名单，故按显式 {@code tenant_id} 条件跨租户操作。
 * API Key 只写不读：{@code apiKey} 非空才经 {@link LlmConfigService#encryptKey} 加密落库，
 * 响应仅回 {@code hasApiKey} 布尔，绝不回填明文/密文。更新用 {@code updateById}（先置空
 * {@code createdAt}/{@code updatedAt} 交由 DB 默认值维护），保留原 Key 当表单未提供新 Key。
 */
@Service
public class LlmConfigAdminService {

    private final LlmConfigMapper llmConfigMapper;
    private final LlmConfigService llmConfigService;

    public LlmConfigAdminService(LlmConfigMapper llmConfigMapper, LlmConfigService llmConfigService) {
        this.llmConfigMapper = llmConfigMapper;
        this.llmConfigService = llmConfigService;
    }

    /** 列配置：{@code tenantId} 为空则全部（全局 + 各租户），否则限该租户；按优先级升序。 */
    public List<LlmConfigVO> list(Long tenantId) {
        return llmConfigMapper.selectList(Wrappers.<LlmConfig>lambdaQuery()
                        .eq(tenantId != null, LlmConfig::getTenantId, tenantId)
                        .orderByAsc(LlmConfig::getPriority).orderByAsc(LlmConfig::getId))
                .stream().map(this::toVO).toList();
    }

    public LlmConfigVO get(Long id) {
        return toVO(require(id));
    }

    @Transactional
    public LlmConfigVO create(LlmConfigForm form) {
        LlmConfig cfg = new LlmConfig();
        applyForm(cfg, form);
        llmConfigMapper.insert(cfg);
        return toVO(cfg);
    }

    @Transactional
    public LlmConfigVO update(Long id, LlmConfigForm form) {
        LlmConfig cfg = require(id);
        applyForm(cfg, form);
        // 置空审计列，交由 DB DEFAULT / ON UPDATE CURRENT_TIMESTAMP 维护，避免 updateById 覆写
        cfg.setCreatedAt(null);
        cfg.setUpdatedAt(null);
        llmConfigMapper.updateById(cfg);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        llmConfigMapper.deleteById(id);
    }

    @Transactional
    public LlmConfigVO setEnabled(Long id, Integer enabled) {
        require(id);
        llmConfigMapper.update(null, Wrappers.<LlmConfig>lambdaUpdate()
                .eq(LlmConfig::getId, id).set(LlmConfig::getEnabled, enabled));
        return get(id);
    }

    /** 测试尚未保存/刚编辑的配置：明文 Key 优先，否则报错（无已存密文可用）。 */
    public void test(LlmConfigForm form) {
        LlmConfig cfg = new LlmConfig();
        cfg.setProvider(form.provider());
        cfg.setName(form.name());
        cfg.setBaseUrl(form.baseUrl());
        cfg.setModel(form.model());
        cfg.setTimeoutSeconds(form.timeoutSeconds());
        llmConfigService.testConnection(cfg, form.apiKey());
    }

    /** 测试已保存的配置：用其已存密文 Key。 */
    public void testById(Long id) {
        llmConfigService.testConnection(require(id), null);
    }

    /** 表单 → 实体（Key 只写不读：非空才加密覆盖，否则保留原值）。 */
    private void applyForm(LlmConfig cfg, LlmConfigForm form) {
        cfg.setTenantId(form.tenantId());
        cfg.setProvider(form.provider());
        cfg.setName(form.name());
        cfg.setBaseUrl(form.baseUrl());
        cfg.setModel(form.model());
        cfg.setTimeoutSeconds(form.timeoutSeconds() == null ? 60 : form.timeoutSeconds());
        cfg.setPriority(form.priority() == null ? 100 : form.priority());
        cfg.setEnabled(form.enabled() == null ? 1 : form.enabled());
        if (form.apiKey() != null && !form.apiKey().isBlank()) {
            cfg.setApiKeyCipher(llmConfigService.encryptKey(form.apiKey()));
        }
    }

    private LlmConfig require(Long id) {
        LlmConfig cfg = llmConfigMapper.selectById(id);
        if (cfg == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "大模型配置不存在: " + id);
        }
        return cfg;
    }

    private LlmConfigVO toVO(LlmConfig c) {
        boolean hasKey = c.getApiKeyCipher() != null && !c.getApiKeyCipher().isBlank();
        return new LlmConfigVO(c.getId(), c.getTenantId(), c.getProvider(), c.getName(), c.getBaseUrl(),
                c.getModel(), c.getTimeoutSeconds(), c.getPriority(), c.getEnabled(), hasKey, c.getCreatedAt());
    }
}
