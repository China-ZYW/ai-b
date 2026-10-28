package com.aibi.module.attribution;

import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 归因维度解析器：从语义层读取"哪些列可以当归因维度、时间列是谁、表名是谁"。
 *
 * <p><b>为什么只认语义层标记、不启发式猜列</b>：归因结论会直接呈给经营决策者（"华南区拖累 -38%"），
 * 一旦把主键、时间戳、备注这类高基数列误判为维度，产出的就是一堆无意义的碎片成员。
 * 因此维度候选严格取自管理员在语义层显式标记的 {@code DIMENSION} 角色——垃圾进垃圾出的责任回到维护者，
 * 而不是让算法去猜。这也是需求 §3.1"自动读取语义层预先标记的度量指标、业务维度列表"的原意。
 *
 * <p><b>敏感维度护栏（Q11）</b>：现有 {@code FieldPermissionService} 只对<b>结果列值</b>脱敏，
 * 并不阻止对敏感列做 {@code GROUP BY}。归因恰恰会把维度成员的贡献值逐个摆出来
 * （"某客户贡献 -120 万"），等于绕过字段级权限暴露敏感成员的业务表现。
 * 故此处在<b>选维入口</b>直接剔除 {@code sensitive=1} 的维度，从源头堵住这条暴露路径。
 */
@Component
public class DimensionResolver {

    private static final Logger log = LoggerFactory.getLogger(DimensionResolver.class);

    /** 语义层列角色取值（与前端 {@code ColumnRole} 枚举严格一致，避免出现第二套真相）。 */
    public static final String ROLE_DIMENSION = "DIMENSION";
    public static final String ROLE_MEASURE = "MEASURE";
    public static final String ROLE_TEMPORAL = "TEMPORAL";
    public static final String ROLE_AUTO = "AUTO";

    /** 量字段的关键词（用于从度量列推断"量"，供量价拆解使用）。 */
    private static final List<String> VOLUME_HINTS =
            List.of("qty", "quantity", "num", "count", "cnt", "volume", "units", "件", "数量", "销量", "单量", "笔数");

    private final SemanticMetaMapper semanticMetaMapper;

    public DimensionResolver(SemanticMetaMapper semanticMetaMapper) {
        this.semanticMetaMapper = semanticMetaMapper;
    }

    /** 可选归因维度（已剔除敏感列）。 */
    public record DimensionOption(String column, String label, String table) {
    }

    /**
     * 归因所需的库表骨架。
     *
     * @param table        归因主表（取时间列或首个维度所在表）
     * @param timeColumn   时间列（用于本期/基期切分）
     * @param dimensions   可用维度（已剔除敏感列）
     */
    public record AttributionSchema(String table, String timeColumn, List<DimensionOption> dimensions) {
    }

    /**
     * 解析某数据源下可用于归因的骨架。
     *
     * @param datasourceId 数据源 id（租户隔离由 MP 拦截器自动追加）
     * @return 骨架；无时间列或无维度时返回 null，由调用方给出明确提示（宁可不做，也不错做）
     */
    public AttributionSchema schema(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        List<SemanticMeta> metas = load(datasourceId);
        if (metas.isEmpty()) {
            return null;
        }
        String timeColumn = firstColumn(metas, ROLE_TEMPORAL);
        List<DimensionOption> dimensions = dimensionOptions(metas);
        if (timeColumn == null || dimensions.isEmpty()) {
            return null;
        }
        String table = tableOf(metas, timeColumn);
        if (table == null) {
            table = dimensions.get(0).table();
        }
        return new AttributionSchema(table, timeColumn, dimensions);
    }

    /** 维度候选：DIMENSION 角色且非敏感列；展示名优先取语义别名。 */
    public List<DimensionOption> dimensionOptions(Long datasourceId) {
        return dimensionOptions(load(datasourceId));
    }

    private List<DimensionOption> dimensionOptions(List<SemanticMeta> metas) {
        List<DimensionOption> options = new ArrayList<>();
        String table = null;
        for (SemanticMeta m : metas) {
            if (isRole(m, ROLE_DIMENSION)) {
                // 敏感维度不可归因：现有脱敏只防结果列值，不防 GROUP BY（见类注释）
                if (m.getSensitive() != null && m.getSensitive() == 1) {
                    continue;
                }
                if (m.getColumnName() == null || m.getColumnName().isBlank()) {
                    continue;
                }
                if (table == null) {
                    table = m.getTableName();
                }
                options.add(new DimensionOption(m.getColumnName(), labelOf(m), m.getTableName()));
            }
        }
        return options;
    }

    /**
     * 推断"量"字段表达式（量价拆解用）。
     *
     * <p>优先取请求显式指定的列；未指定时在度量列里按关键词匹配。
     * 匹配不到返回 null——此时跳过量价拆解而非随便拿一列充数，避免产出误导性的"量/价"结论。
     */
    public String inferVolumeExpr(Long datasourceId) {
        List<SemanticMeta> metas = load(datasourceId);
        // 第一轮：显式标记为 MEASURE 且列名命中"量"关键词——语义层维护到位时的正解。
        for (SemanticMeta m : metas) {
            if (isRole(m, ROLE_MEASURE) && matchesVolume(m)) {
                return exprOf(m);
            }
        }
        // 第二轮兜底：语义层普遍未维护（列角色大量停留在 AUTO），若严格要求 MEASURE 会导致
        // 量价拆解"静默不触发"——用户只看得到维度归因，却不知道量价本可以做。
        // 量字段不同于维度：误选的代价仅限于量价结论偏差且可解释，远小于敏感维度误归因，故允许兜底。
        for (SemanticMeta m : metas) {
            if (isRole(m, ROLE_AUTO) && matchesVolume(m)) {
                log.warn("量字段「{}」语义角色为 AUTO（未维护），量价拆解按列名推断兜底采用；"
                        + "建议在问数调优中心将其显式标记为 MEASURE", m.getColumnName());
                return exprOf(m);
            }
        }
        return null;
    }

    private String exprOf(SemanticMeta m) {
        return m.getSqlExpr() != null && !m.getSqlExpr().isBlank()
                ? m.getSqlExpr()
                : m.getColumnName();
    }

    /** 读取某数据源的语义行（COLUMN 级），异常一律降级为空列表，不拖垮归因。 */
    private List<SemanticMeta> load(Long datasourceId) {
        LambdaQueryWrapper<SemanticMeta> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SemanticMeta::getDatasourceId, datasourceId);
        wrapper.eq(SemanticMeta::getObjectType, "COLUMN");
        try {
            List<SemanticMeta> list = semanticMetaMapper.selectList(wrapper);
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 取首个指定角色的列名。 */
    private String firstColumn(List<SemanticMeta> metas, String role) {
        for (SemanticMeta m : metas) {
            if (isRole(m, role) && m.getColumnName() != null && !m.getColumnName().isBlank()) {
                return m.getColumnName();
            }
        }
        return null;
    }

    /** 某列所在的表名。 */
    private String tableOf(List<SemanticMeta> metas, String column) {
        for (SemanticMeta m : metas) {
            if (column != null && column.equals(m.getColumnName()) && m.getTableName() != null) {
                return m.getTableName();
            }
        }
        return null;
    }

    private boolean isRole(SemanticMeta m, String role) {
        return m.getColumnRole() != null && role.equalsIgnoreCase(m.getColumnRole().trim());
    }

    private String labelOf(SemanticMeta m) {
        if (m.getAliasCn() != null && !m.getAliasCn().isBlank()) {
            return m.getAliasCn();
        }
        return m.getColumnName();
    }

    /** 列名或别名是否像"量"字段。 */
    private boolean matchesVolume(SemanticMeta m) {
        String text = ((m.getColumnName() == null ? "" : m.getColumnName()) + " "
                + (m.getAliasCn() == null ? "" : m.getAliasCn())).toLowerCase(Locale.ROOT);
        for (String hint : VOLUME_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
