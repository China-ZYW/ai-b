package com.aibi.module.semantic.service;

import com.aibi.module.chat.nl2sql.SchemaSlice;
import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.semantic.entity.SemanticMeta;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 语义层服务（配置化升级 V2，准确率核心）：门面（T8.4 拆分，2026-10-24，原 785 行巨类按职责四分）——
 * <ul>
 *   <li>{@link SemanticViewStore}：视图缓存/读路径基座（view/list/columnRole/unitOf/invalidate）；</li>
 *   <li>{@link SemanticLabels}：别名补全（displayLabels/enumDicts 展示名与码值字典）；</li>
 *   <li>{@link SemanticPromptSupplier}：选表打分供给（buildPromptSection 语义段落）；</li>
 *   <li>{@link SemanticWriter}：写路径（骨架初始化/编辑/删除/枚举采样）。</li>
 * </ul>
 *
 * <p>消费点不变：<b>NL2SQL Prompt</b>（{@link #buildPromptSection} 注入别名/描述/枚举/角色/业务口径）、
 * <b>SchemaLinker</b>（{@link #view} 别名/同义词参与相关性打分）、<b>ChartRecommender</b>
 * （{@link #columnRole} 人工列角色先于启发式）、<b>结果展示</b>（{@link #displayLabels} 业务别名展示名）、
 * <b>图表标注</b>（{@link #unitOf} 度量单位）。public 签名零变化，全部方法纯委托。
 */
@Service
public class SemanticService {

    public static final String TYPE_TABLE = SemanticViewStore.TYPE_TABLE;
    public static final String TYPE_COLUMN = SemanticViewStore.TYPE_COLUMN;
    public static final String ROLE_AUTO = SemanticViewStore.ROLE_AUTO;
    public static final String ROLE_MEASURE = SemanticViewStore.ROLE_MEASURE;

    private final SemanticViewStore store;
    private final SemanticLabels labels;
    private final SemanticPromptSupplier promptSupplier;
    private final SemanticWriter writer;

    public SemanticService(SemanticViewStore store,
                           SemanticLabels labels,
                           SemanticPromptSupplier promptSupplier,
                           SemanticWriter writer) {
        this.store = store;
        this.labels = labels;
        this.promptSupplier = promptSupplier;
        this.writer = writer;
    }

    // ==================== 读路径（→ SemanticViewStore） ====================

    /** 某数据源的全部语义行（管理页）。 */
    public List<SemanticMeta> list(Long datasourceId) {
        return store.list(datasourceId);
    }

    /** 带缓存的语义视图；无语义数据时返回空视图（消费点零影响）。 */
    public SemanticView view(Long datasourceId) {
        return store.view(datasourceId);
    }

    /** 列角色查询（图表推荐用）：跨表按列名取人工确认角色（非 AUTO）；无匹配返回 null。 */
    public String columnRole(Long datasourceId, String columnName) {
        return store.columnRole(datasourceId, columnName);
    }

    /** 度量单位查询（图表标注用）：跨表按列名匹配已启用语义行的 unit；未配置返回 null。 */
    public String unitOf(Long datasourceId, String columnName) {
        return store.unitOf(datasourceId, columnName);
    }

    // ==================== 别名补全（→ SemanticLabels） ====================

    /** 结果列展示名（业务别名优先、物理列名兜底）；没有任何列取到别名时返回 null。 */
    public List<String> displayLabels(Long datasourceId, List<String> columns) {
        return labels.displayLabels(datasourceId, columns);
    }

    /** 结果列展示名（自定义覆盖 &gt; 语义层别名 &gt; 物理列名）。 */
    public List<String> displayLabels(Long datasourceId, List<String> columns, Map<String, String> overrides) {
        return labels.displayLabels(datasourceId, columns, overrides);
    }

    /** 结果列枚举字典（码值→标签，只换显示不换数据）；无任何列命中时返回 null。 */
    public Map<String, Map<String, String>> enumDicts(Long datasourceId, List<String> columns) {
        return labels.enumDicts(datasourceId, columns);
    }

    // ==================== Prompt 供给（→ SemanticPromptSupplier） ====================

    /** 注入 Prompt 的"业务语义"段落；全空返回 null（不浪费 token）。 */
    public String buildPromptSection(Long datasourceId, SchemaSlice slice) {
        return promptSupplier.buildPromptSection(datasourceId, slice);
    }

    // ==================== 写路径（→ SemanticWriter） ====================

    /** 骨架初始化：为数据源全部表/列补语义骨架行（已存在则跳过），返回新增行数。 */
    public int initSkeleton(Long datasourceId) {
        return writer.initSkeleton(datasourceId);
    }

    /** 骨架懒初始化（异步）：问数首用且语义行为空时后台补齐，不阻塞主链路；每数据源仅触发一次。 */
    public void asyncInitSkeleton(Long datasourceId) {
        writer.asyncInitSkeleton(datasourceId);
    }

    /** 更新语义行（管理页编辑别名/角色/单位/同义词/口径/枚举/启用态/字段级权限）。 */
    public SemanticMeta update(SemanticMeta form) {
        return writer.update(form);
    }

    /** 删除语义行。 */
    public boolean delete(Long id) {
        return writer.delete(id);
    }

    /** 枚举值采样：单列 DISTINCT 回填 enum_values（标识符白名单校验防注入）。 */
    public Map<String, Object> sampleEnums(Long datasourceId, String table, String column) {
        return writer.sampleEnums(datasourceId, table, column);
    }

    /** 一键采样该数据源所有维度类列的枚举值（跳过度量列），返回汇总。 */
    public Map<String, Object> sampleAllEnums(Long datasourceId) {
        return writer.sampleAllEnums(datasourceId);
    }

    // ==================== 缓存 ====================

    /** 写操作后失效该数据源视图缓存。 */
    public void invalidate(Long datasourceId) {
        store.invalidate(datasourceId);
    }
}
