package com.aibi.module.query.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 高级查询 / SQL 编辑器模块的请求与响应契约（全部为 record，访问器形如 {@code req.datasourceId()}）。
 *
 * <p>约定：{@code maxRows} 为 null 或 <=0 时由服务端取配置默认值；{@code chartType} 为 null 时
 * 由服务端按结果结构推荐，非空时以用户显式指定为准（前端"图表类型"下拉）。
 */
public final class QueryDtos {

    private QueryDtos() {
    }

    /** 执行一条 SQL（编辑器「运行」）。 */
    public record SqlRunRequest(
            Long datasourceId,
            String sql,
            Long maxRows,
            /** 显式指定图型（为空则由服务端推荐）。 */
            String chartType
    ) {
    }

    /** 保存查询模板入参。参数名从模板里自动解析，无需前端回传。 */
    public record SavedQueryCreateCmd(
            String name,
            Long datasourceId,
            String sqlTemplate,
            String description,
            /** 构造器规格 JSON（{@code BuilderSpec}）；来自可视化构造器时非空，SQL 编辑器来源为 null。 */
            String spec
    ) {
    }

    /** 重命名/改模 touching 既有模板时使用的完整入参（与创建同形，便于复用一层 Mapper 方法）。 */
    public record SavedQueryUpdateCmd(
            String name,
            Long datasourceId,
            String sqlTemplate,
            String description,
            /** 构造器规格 JSON（{@code BuilderSpec}）；来自可视化构造器时非空，SQL 编辑器来源为 null。 */
            String spec
    ) {
    }

    /** 运行模板入参：键为模板中 {@code :param} 的参数名。 */
    public record TemplateRunRequest(
            Map<String, Object> params
    ) {
    }

    /** 切换模板可见性入参（仅属主且为租户管理员可操作）。 */
    public record SavedQueryVisibilityCmd(
            /** {@code PRIVATE}=收回仅本人 / {@code SHARED}=共享给全租户。 */
            String visibility
    ) {
    }

    /** 查询模板列表项。 */
    public record SavedQueryVO(
            Long id,
            String name,
            String description,
            Long datasourceId,
            String datasourceName,
            String sqlTemplate,
            List<String> paramNames,
            LocalDateTime updatedAt,
            /** 构造器规格 JSON（{@code BuilderSpec}）；非空表示此模板来自可视化构造器。 */
            String spec,
            /** 可见性：PRIVATE=仅本人 / SHARED=组织共享（null 按 PRIVATE 处理，兼容存量）。 */
            String visibility,
            /** 属主 uid（前端据此判定共享模板隐藏编辑/删除，保留运行与定时入口）。 */
            Long ownerUid
    ) {
    }

    /** 一次执行的完整产出：结果集 + 图表规格 + 脱敏标记。 */
    public record QueryRunVO(
            QueryResult result,
            ChartSpec chart,
            /** 命中/使用的答问技能编码，供前端标注"本次由哪条链路产出"。 */
            String skillCode,
            /** 该数据源下本次结果是否发生了脱敏。 */
            boolean masked,
            Long templateId
    ) {
    }

    // ==================== 可视化查询构造器（P1 收尾） ====================

    /**
     * 构造器：一条待生成的输出列。
     *
     * @param table  列所属表（必须是主表或已关联的维表）
     * @param column 列名
     * @param agg    聚合函数（{@code COUNT/SUM/AVG/MIN/MAX}）；空白表示"原始列"，有原始列且存在聚合时自动 GROUP BY
     * @param alias  可选输出别名（留空则由后端按列名生成，保证唯一）
     */
    public record BuilderSelect(String table, String column, String agg, String alias) {
    }

    /**
     * 构造器：一条 JOIN。
     *
     * <p><b>只带关系标识、不带连接列</b>——连接列由服务端从关联图谱（人工登记 > 外键 > 命名推断）
     * 解析后翻转方向，前端无从指定，也就无从指定错的键。
     *
     * @param relationKey 关联标识（取自 {@link BuilderRelationVO#key}）
     */
    public record BuilderJoin(String relationKey) {
    }

    /**
     * 构造器：一条筛选。
     *
     * @param op     运算符（{@code = != > >= < <= LIKE NOT LIKE IN NOT IN BETWEEN IS NULL IS NOT NULL}）
     * @param values 取值（{@code IS NULL}/{@code IS NOT NULL} 忽略；其余至少 1 个）
     */
    public record BuilderFilter(String table, String column, String op, List<String> values) {
    }

    /** 构造器：一条排序（{@code desc} 为 null 按升序）。 */
    public record BuilderSort(String table, String column, Boolean desc) {
    }

    /** 构造器完整规格：主表 → 关联 → 输出列 → 筛选 → 排序 → 分页。 */
    public record BuilderSpec(String mainTable,
                              List<BuilderJoin> joins,
                              List<BuilderSelect> selects,
                              List<BuilderFilter> filters,
                              List<BuilderSort> sorts,
                              Long limit,
                              Long offset) {
    }

    /**
     * 构造器可用的一条关联（前端只能从清单里挑）。
     *
     * @param id          本清单内的序号（前端用来回传，仅作展示与去重，键本身才是权威）
     * @param key         关联标识（回传服务端即据此解析连接列）
     * @param source      关联来源（{@code MANUAL} / {@code FOREIGN_KEY} / {@code INFERRED}）
     * @param cardinality 基数（决定会不会 fan-out 放大度量）
     */
    public record BuilderRelationVO(long id, String key, String table, String column,
                                    String refTable, String refColumn,
                                    String source, String cardinality) {
    }

    /** 构造器生成的 SQL（预览用，不执行）。 */
    public record BuilderSqlVO(String sql, int paramCount) {
    }

    /** 构造器「预览 / 运行」共用入参（预览时前端忽略执行结果即可）。 */
    public record BuilderRunRequest(
            Long datasourceId,
            BuilderSpec spec,
            Long maxRows,
            /** 显式图型（为空则由服务端按结果结构推荐）。 */
            String chartType
    ) {
    }

    /** 查询历史列表项（SQL 编辑器「历史」页）。 */
    public record QueryRecordVO(
            Long id,
            Long datasourceId,
            String datasourceName,
            String sqlText,
            Integer rowCount,
            Long elapsedMs,
            String status,
            String skillCode,
            String chartType,
            LocalDateTime createdAt,
            /** 构造器规格 JSON（{@code BuilderSpec}）；来自可视化构造器运行时才有。 */
            String spec
    ) {
    }
}
