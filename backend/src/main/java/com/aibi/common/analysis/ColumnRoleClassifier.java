package com.aibi.common.analysis;

import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 列角色判定公共组件（T10-4b K1）：判定结果集中每列是<b>时间维度 / 类别维度 / 数值度量</b>。
 *
 * <p>本组件的判定逻辑自 {@code ChartRecommender} <b>逐字节下沉</b>而来（语义层人工确认的
 * {@code column_role} 优先采信，其次"取样值的运行时类型 + 列名关键词"双重启发式），图表推荐与
 * 环比异常标记共用同一份判定防漂移——先例对齐 T10-3 的 {@code SqlTableExtractor}。
 * {@code ChartRecommender} 现在只保留图型规则，角色判定全部委派至此。
 */
@Component
public class ColumnRoleClassifier {

    private static final Logger log = LoggerFactory.getLogger(ColumnRoleClassifier.class);

    /** 列的角色：时间维度 / 类别维度 / 数值度量。 */
    public enum ColumnRole { TEMPORAL, CATEGORY, MEASURE }

    /** 时间维英文词根（按“词边界”匹配，见 {@link #containsAnyBounded}：命中片段须位于串首或前一字符非字母，
     *  避免 "candidate"(含 date)、"runtime"(含 time) 等被误判为时间维而丢失度量）。 */
    private static final String[] TEMPORAL_WORD = {
            "date", "time", "month", "year", "quarter", "week", "hour"};
    /** 时间维列名子串特征（自带分隔符的 "_at" + 明确的中文词，直接子串匹配即可）。 */
    private static final String[] TEMPORAL_SUBSTR = {
            "_at", "日期", "时间", "年份", "年月", "年度", "月份", "月度", "季度"};
    /** 时间维列名精确特征（易误判的中文单字，仅在完全相等时命中，避免"年龄/月薪"误判）。 */
    private static final String[] TEMPORAL_EXACT = {"day", "dt", "年", "月", "日", "周", "天"};
    /** 主键/编码类维度列名特征（这些数值列是维度而非度量）。 */
    private static final String[] ID_SUBSTR = {"编号", "编码", "序号", "主键"};

    private final SemanticService semanticService;

    public ColumnRoleClassifier(SemanticService semanticService) {
        this.semanticService = semanticService;
    }

    /** 逐列判定角色（顺序与 {@code result.columns()} 一一对应）。 */
    public List<ColumnRole> classifyAll(QueryResult result, Long datasourceId) {
        List<String> columns = result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        List<ColumnRole> out = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            out.add(classify(columns.get(i), rows, i, datasourceId));
        }
        return out;
    }

    /**
     * 判定列角色：语义层人工确认的 column_role（非 AUTO）直接采信，先于启发式；
     * 否则综合"取样值的运行时类型 + 列名关键词"判定。全列为 null 时仅按列名推断。
     */
    public ColumnRole classify(String name, List<List<Object>> rows, int col, Long datasourceId) {
        ColumnRole semantic = semanticRole(datasourceId, name);
        if (semantic != null) {
            return semantic;
        }
        String lower = name == null ? "" : name.toLowerCase();
        boolean temporalName = containsAnyBounded(lower, TEMPORAL_WORD)
                || containsAny(lower, TEMPORAL_SUBSTR) || equalsAny(lower, TEMPORAL_EXACT);
        boolean idName = lower.equals("id") || lower.endsWith("_id") || containsAny(lower, ID_SUBSTR);

        Object sample = firstNonNull(rows, col);
        if (sample == null) {
            return temporalName ? ColumnRole.TEMPORAL : ColumnRole.CATEGORY;
        }
        if (sample instanceof Temporal || sample instanceof Date) {
            return ColumnRole.TEMPORAL;
        }
        if (sample instanceof Boolean) {
            return ColumnRole.CATEGORY;
        }
        if (sample instanceof Number) {
            if (idName) {
                return ColumnRole.CATEGORY;      // 数值型主键/编码是维度而非度量
            }
            return temporalName ? ColumnRole.TEMPORAL : ColumnRole.MEASURE;  // 如 year=2024 归时间维
        }
        // 字符串或其它类型
        if (temporalName || looksLikeDate(sample.toString())) {
            return ColumnRole.TEMPORAL;
        }
        return ColumnRole.CATEGORY;
    }

    /** 语义层人工确认的列角色（DIMENSION/MEASURE/TEMPORAL）；无配置或异常时返回 null 交由启发式。 */
    private ColumnRole semanticRole(Long datasourceId, String columnName) {
        if (datasourceId == null || columnName == null) {
            return null;
        }
        try {
            String role = semanticService.columnRole(datasourceId, columnName);
            if (role == null) {
                return null;
            }
            return switch (role) {
                case "DIMENSION" -> ColumnRole.CATEGORY;
                case "MEASURE" -> ColumnRole.MEASURE;
                case "TEMPORAL" -> ColumnRole.TEMPORAL;
                default -> null;
            };
        } catch (Exception e) {
            log.debug("语义列角色查询跳过：{}", e.getMessage());
            return null;
        }
    }

    /** 轻量判断字符串是否形如日期（yyyy-MM-dd / yyyy/MM / yyyy年MM月 等）。 */
    private boolean looksLikeDate(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.matches("\\d{4}[-/年.]\\d{1,2}([-/月.]\\d{1,2}日?)?.*");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 英文时间词根的“词边界”匹配：关键词须出现在串首，或其前一字符不是 ASCII 字母（如被 {@code _} 分隔）。
     * 这样 {@code order_date/create_time/datetime/timestamp} 仍判为时间维，而 {@code candidate}(含 date)、
     * {@code runtime/overtime}(含 time) 不再被误判——后者本是度量列，误判为时间维会导致度量丢失、图表塌成明细表。
     */
    private boolean containsAnyBounded(String text, String... keywords) {
        for (String k : keywords) {
            int from = 0;
            while (from <= text.length()) {
                int idx = text.indexOf(k, from);
                if (idx < 0) {
                    break;
                }
                if (idx == 0 || !isAsciiLetter(text.charAt(idx - 1))) {
                    return true;
                }
                from = idx + 1;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean equalsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.equals(k)) {
                return true;
            }
        }
        return false;
    }

    private Object firstNonNull(List<List<Object>> rows, int col) {
        for (List<Object> row : rows) {
            if (row != null && col < row.size() && row.get(col) != null) {
                return row.get(col);
            }
        }
        return null;
    }
}
