package com.aibi.module.semantic.service;

import com.aibi.module.chat.nl2sql.SchemaSlice;
import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aibi.module.semantic.service.SemanticViewStore.ROLE_AUTO;
import static com.aibi.module.semantic.service.SemanticViewStore.enabled;
import static com.aibi.module.semantic.service.SemanticViewStore.notBlank;

/**
 * 语义 Prompt 供给协作类（T8.4 自 SemanticService 抽出）：把别名/描述/同义词/角色/单位/
 * 枚举/业务口径注入 NL2SQL 提示词，并供追问改写与推荐问法复用同一口径。
 *
 * <p>仅输出有信息量的条目，全空时返回 null（不浪费 token）。
 */
@Component
class SemanticPromptSupplier {

    private final SemanticViewStore store;
    private final ObjectMapper objectMapper;

    SemanticPromptSupplier(SemanticViewStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建注入 Prompt 的"业务语义"段落：仅输出有信息量的条目（别名/描述/同义词/角色/枚举/口径），
     * 全空时返回 null（不浪费 token）。
     */
    String buildPromptSection(Long datasourceId, SchemaSlice slice) {
        if (slice == null || slice.isEmpty()) {
            return null;
        }
        SemanticView view = store.view(datasourceId);
        if (view.tables().isEmpty() && view.columns().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<String> calibers = new ArrayList<>();
        for (TableMeta t : slice.tables()) {
            SemanticMeta tm = view.tables().get(t.name());
            Map<String, SemanticMeta> cols = view.columns().getOrDefault(t.name(), Map.of());
            StringBuilder tableSb = new StringBuilder();
            if (tm != null && enabled(tm)) {
                tableSb.append("表 ").append(t.name());
                if (notBlank(tm.getAliasCn())) {
                    tableSb.append("（").append(tm.getAliasCn()).append("）");
                }
                if (notBlank(tm.getDescription())) {
                    tableSb.append("：").append(tm.getDescription());
                }
                if (notBlank(tm.getSynonyms())) {
                    tableSb.append("；同义词：").append(tm.getSynonyms());
                }
                tableSb.append("\n");
            }
            for (TableMeta.ColumnMeta c : t.columns()) {
                SemanticMeta cm = cols.get(c.name());
                if (cm == null || !enabled(cm)) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                if (notBlank(cm.getAliasCn()) && !cm.getAliasCn().equals(c.name())) {
                    parts.add("别名:" + cm.getAliasCn());
                }
                if (notBlank(cm.getColumnRole()) && !ROLE_AUTO.equals(cm.getColumnRole())) {
                    parts.add("角色:" + cm.getColumnRole());
                }
                if (notBlank(cm.getUnit())) {
                    parts.add("单位:" + cm.getUnit());
                }
                if (notBlank(cm.getEnumValues())) {
                    parts.add(buildEnumHint(cm));
                }
                if (notBlank(cm.getSynonyms())) {
                    parts.add("同义词:" + cm.getSynonyms());
                }
                if (notBlank(cm.getDescription())) {
                    parts.add(cm.getDescription());
                }
                if (notBlank(cm.getSqlExpr())) {
                    calibers.add((notBlank(cm.getAliasCn()) ? cm.getAliasCn() : c.name())
                            + " = " + cm.getSqlExpr());
                }
                if (!parts.isEmpty()) {
                    tableSb.append("  - ").append(c.name()).append("（")
                            .append(String.join("，", parts)).append("）\n");
                }
            }
            sb.append(tableSb);
        }
        if (!calibers.isEmpty()) {
            sb.append("业务口径：").append(String.join("；", calibers)).append("\n");
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 枚举片段拼装：有字典时拼成 {@code 枚举:1,2,3,4；字典:1=已支付，2=已取消}，
     * 让模型知道码值含义；无字典时退回原样输出取值列表（与升级前一致，不引入噪音）。
     */
    private String buildEnumHint(SemanticMeta cm) {
        String dict = formatEnumDict(cm.getEnumLabels());
        String head = "枚举:" + cm.getEnumValues();
        return dict.isEmpty() ? head : head + "；" + dict;
    }

    /** 枚举字典 JSON → {@code 字典:1=已支付，2=已取消}；非法 JSON / 空值一律返回空串（静默降级，不打断 Prompt）。 */
    private String formatEnumDict(String json) {
        if (!notBlank(json)) {
            return "";
        }
        Map<?, ?> map;
        try {
            map = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return "";
        }
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("字典:");
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            String label = String.valueOf(v).trim();
            if (label.isEmpty()) {
                continue;
            }
            sb.append(e.getKey()).append('=').append(label).append("，");
        }
        int len = sb.length();
        if (len == 3) {   // 只有"字典:"前缀，一条有效映射都没拼上
            return "";
        }
        sb.setLength(len - 1);
        return sb.toString();
    }
}
