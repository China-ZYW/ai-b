package com.aibi.module.fewshot.service;

import com.aibi.module.chat.nl2sql.Intent;
import com.aibi.module.fewshot.entity.FewShotExample;
import com.aibi.module.fewshot.mapper.FewShotExampleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 问数样例库服务（配置化升级 V2）：curated "问法 → SQL" 样例的增删改查。
 *
 * <p>样例由 {@code FewShotStore} 在问数链路中以最高权重合并进少样本，是问数准确率的可运营抓手。
 * {@code few_shot_example} 为租户隔离表，{@code tenant_id} 由拦截器自动注入/过滤，本服务不显式处理。
 *
 * <p>Phase-1 意图对齐：样例的 {@code intent} 列是少样本检索双向对齐的标注来源，仅接受
 * {@link Intent} 的 6 个枚举名之一（大小写不敏感归一），其余值（含空串/脏值）一律归为 {@code null}，
 * 避免无效标注破坏对齐（null 在检索里会跳过双向对齐、完全向后兼容）。
 */
@Service
public class FewShotService {

    public static final String SOURCE_CURATED = "CURATED";

    private final FewShotExampleMapper mapper;

    public FewShotService(FewShotExampleMapper mapper) {
        this.mapper = mapper;
    }

    /** 某数据源的样例列表（datasourceId 为空则本租户全部）。 */
    public List<FewShotExample> list(Long datasourceId) {
        LambdaQueryWrapper<FewShotExample> w = new LambdaQueryWrapper<FewShotExample>()
                .orderByDesc(FewShotExample::getUpdatedAt);
        if (datasourceId != null) {
            w.eq(FewShotExample::getDatasourceId, datasourceId);
        }
        return mapper.selectList(w);
    }

    /** 新建样例（source=CURATED，use_count=0）。 */
    public FewShotExample create(FewShotExample form) {
        form.setId(null);
        if (form.getSource() == null || form.getSource().isBlank()) {
            form.setSource(SOURCE_CURATED);
        }
        if (form.getEnabled() == null) {
            form.setEnabled(1);
        }
        form.setUseCount(0);
        // 意图标注归一：仅接受 Intent 6 枚举名，脏值/空串归为 null（不入库破坏对齐）
        form.setIntent(normalizeIntent(form.getIntent()));
        mapper.insert(form); // 租户拦截器自动补 tenant_id
        return form;
    }

    /** 更新样例（仅本租户本人可见的行；不存在/越权返回 null）。 */
    public FewShotExample update(FewShotExample form) {
        FewShotExample row = mapper.selectById(form.getId());
        if (row == null) {
            return null;
        }
        if (form.getQuestion() != null) {
            row.setQuestion(form.getQuestion());
        }
        if (form.getSqlText() != null) {
            row.setSqlText(form.getSqlText());
        }
        if (form.getIntent() != null) {
            // 前端传空串表示清空意图：归一后 null 会落库清空，脏值同样归 null
            row.setIntent(normalizeIntent(form.getIntent()));
        }
        if (form.getTags() != null) {
            row.setTags(form.getTags());
        }
        if (form.getDatasourceId() != null) {
            row.setDatasourceId(form.getDatasourceId());
        }
        if (form.getEnabled() != null) {
            row.setEnabled(form.getEnabled());
        }
        mapper.updateById(row);
        return row;
    }

    /** 删除样例（不存在/越权返回 false）。 */
    public boolean delete(Long id) {
        if (mapper.selectById(id) == null) {
            return false;
        }
        mapper.deleteById(id);
        return true;
    }

    /**
     * 意图标注归一化：仅接受 {@link Intent} 的 6 个枚举名（大小写不敏感），其余（null / 空串 / 脏值）归为 null。
     * 保证入库的 intent 一定能被 {@code FewShotStore} 的双向对齐正确识别，脏值不会让对齐失效或误判。
     */
    private static String normalizeIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return null;
        }
        String up = intent.trim().toUpperCase(Locale.ROOT);
        for (Intent v : Intent.values()) {
            if (v.name().equals(up)) {
                return v.name();
            }
        }
        return null;
    }
}
