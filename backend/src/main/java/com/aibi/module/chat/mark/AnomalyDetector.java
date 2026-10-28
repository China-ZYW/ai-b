package com.aibi.module.chat.mark;

import com.aibi.common.analysis.ColumnRoleClassifier;
import com.aibi.common.analysis.ColumnRoleClassifier.ColumnRole;
import com.aibi.module.chat.dto.AnomalyMark;
import com.aibi.module.chat.dto.AnomalyMarks;
import com.aibi.module.datasource.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环比/异常自动标记检测器（T10-4b K4）：对<b>时间序列形态</b>的查询结果做纯确定性相邻周期波动检测。
 *
 * <p>设计红线（对齐 T10-3 LineageExtractor 与 ClarifyGate 的 fail-soft 口径）：
 * <ul>
 *   <li>零 LLM、零新增 DB 查询——列角色判定走 {@link ColumnRoleClassifier}（语义层缓存视图 + 启发式）；</li>
 *   <li>宁缺毋滥——非"恰 1 时间列 + 无类别列 + 行数≥3"的时间序列形态一律返回 {@code null}；</li>
 *   <li>只报事实——结论文案仅含数字与方向，<b>不做任何业务归因</b>；</li>
 *   <li>整体 try/catch：任何异常降级 {@code null}，绝不拖垮问数返回。</li>
 * </ul>
 */
@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    /** 最少行数：环比至少两对相邻点才谈"波动趋势"，单对变化易偶发噪声。 */
    private static final int MIN_ROWS = 3;
    /** 上期值绝对值低于此视为 0，不做环比（除零无意义，"从 0 涨到 1"百分比无穷大）。 */
    private static final double ZERO_EPS = 1e-9;
    /** 事实结论里最多列举的示例数。 */
    private static final int SUMMARY_EXAMPLES = 3;

    private final ColumnRoleClassifier classifier;

    public AnomalyDetector(ColumnRoleClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 检测结果中的环比波动标记。
     *
     * @param datasourceId 数据源 id（可空；供列角色判定查语义层）
     * @param result       <b>原始</b>查询结果（与 lineage 用 result.sql() 同理，不依赖展示名装配）
     * @param thresholdPct 阈值百分比（如 30：|变化| ≥ 30% 即标记）
     * @return 命中标记与事实结论；形态不符 / 无命中 / 任何异常时为 {@code null}
     */
    public AnomalyMarks detect(Long datasourceId, QueryResult result, int thresholdPct) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return null;
        }
        try {
            List<String> columns = result.columns();
            List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
            if (rows.size() < MIN_ROWS) {
                return null;
            }
            List<ColumnRole> roles = classifier.classifyAll(result, datasourceId);
            int timeCol = -1;
            List<Integer> measureCols = new ArrayList<>();
            for (int i = 0; i < roles.size(); i++) {
                switch (roles.get(i)) {
                    case TEMPORAL -> {
                        if (timeCol >= 0) {
                            return null;   // 多个时间列：一期不猜主粒度，宁缺毋滥
                        }
                        timeCol = i;
                    }
                    case CATEGORY -> {
                        // 存在类别列 → 多行是同维度分组行而非时间序列，伪环比不做
                        return null;
                    }
                    case MEASURE -> measureCols.add(i);
                }
            }
            if (timeCol < 0 || measureCols.isEmpty()) {
                return null;
            }

            // 按时间自然序整理：同时间点重复行合并取和（防脏双计），再按时间键升序
            List<SeqPoint> seq = buildSequence(rows, timeCol, measureCols);
            if (seq.size() < MIN_ROWS) {
                return null;
            }

            double threshold = thresholdPct / 100.0;
            List<AnomalyMark> items = new ArrayList<>();
            for (int r = 1; r < seq.size(); r++) {
                SeqPoint prev = seq.get(r - 1);
                SeqPoint cur = seq.get(r);
                for (int mi = 0; mi < measureCols.size(); mi++) {
                    int colIdx = measureCols.get(mi);
                    double pv = prev.values()[mi];
                    double cv = cur.values()[mi];
                    if (Math.abs(pv) < ZERO_EPS) {
                        continue;   // 除零无环比意义
                    }
                    double pct = (cv - pv) / Math.abs(pv) * 100.0;
                    if (Math.abs(pct / 100.0) >= threshold) {
                        items.add(new AnomalyMark(cur.timeKey(), r, colIdx, columns.get(colIdx),
                                cv, pv, round1(pct), pct >= 0));
                    }
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return new AnomalyMarks(thresholdPct, columns.get(timeCol),
                    buildSummary(columns.get(timeCol), thresholdPct, items), items);
        } catch (RuntimeException e) {
            log.warn("环比波动标记装配失败（省略 marks，不影响返回）：{}", e.getMessage());
            return null;
        }
    }

    // ==================== 序列构建 ====================

    /** 一个时间点在序列中的位置：展示键 + 各度量值（按 measureCols 顺序）。 */
    private record SeqPoint(String timeKey, double[] values) {
    }

    /**
     * 构建按时间升序的展示序列：
     * 先收集每行 (时间键, 时间原值)，同时间键合并度量求和；
     * 全部时间原值同型且可比较时按自然序排序，混合型不排序保持返回序（SQL 通常已 ORDER BY）。
     */
    private List<SeqPoint> buildSequence(List<List<Object>> rows, int timeCol, List<Integer> measureCols) {
        //  LinkedHashMap 保返回序合并同时间点
        Map<String, double[]> merged = new LinkedHashMap<>();
        Map<String, Object> timeSample = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            if (row == null || timeCol >= row.size()) {
                continue;
            }
            Object tv = row.get(timeCol);
            if (tv == null) {
                continue;
            }
            String key = String.valueOf(tv);
            double[] vals = merged.computeIfAbsent(key,
                    k -> new double[measureCols.size()]);
            timeSample.putIfAbsent(key, tv);
            for (int mi = 0; mi < measureCols.size(); mi++) {
                Double d = toDouble(row, measureCols.get(mi));
                if (d != null) {
                    vals[mi] += d;
                }
            }
        }
        List<String> keys = new ArrayList<>(merged.keySet());
        if (sortable(timeSample.values())) {
            keys.sort((a, b) -> compareTime(timeSample.get(a), timeSample.get(b)));
        }
        List<SeqPoint> seq = new ArrayList<>();
        for (String k : keys) {
            seq.add(new SeqPoint(k, merged.get(k)));
        }
        return seq;
    }

    /** 度量值取数：非 Number 或不可解析返回 null（该期视为无值、贡献 0）。 */
    private Double toDouble(List<Object> row, int col) {
        if (row == null || col >= row.size()) {
            return null;
        }
        Object v = row.get(col);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim().replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 时间原值集合是否同型可比较（全 Temporal、全 Date、全 Number 或全 String）。 */
    private boolean sortable(Iterable<Object> samples) {
        Class<?> kind = null;
        for (Object o : samples) {
            Class<?> c = o instanceof Date ? Date.class
                    : o instanceof Temporal ? Temporal.class
                    : o instanceof Number ? Number.class
                    : o instanceof String ? String.class : Other.class;
            if (kind == null) {
                kind = c;
            } else if (kind != c) {
                return false;
            }
        }
        return kind != null;
    }

    private interface Other {
    }

    /** 同型时间值比较：Date 比时间戳、Number 比数值、其余按字符串自然序（"2026-01"<"2026-02"）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareTime(Object a, Object b) {
        if (a instanceof Date da && b instanceof Date db) {
            return da.compareTo(db);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ==================== 事实文案 ====================

    /**
     * 一句话<b>事实</b>结论：波动数量 + 最多 3 例"列 前值→现值 ±xx%"，只报数字与方向、不归因。
     * 数字保留 2 位有效小数并去尾零，避免 "100.00" 这类噪声。
     */
    private String buildSummary(String timeCol, int thresholdPct, List<AnomalyMark> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("相邻 ").append(timeCol).append(" 周期共 ").append(items.size())
                .append(" 处波动超过 ").append(thresholdPct).append("%：");
        int n = Math.min(SUMMARY_EXAMPLES, items.size());
        for (int i = 0; i < n; i++) {
            AnomalyMark m = items.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(m.colName()).append(' ').append(m.timePoint()).append(' ')
                    .append(fmtNum(m.prevValue())).append(" → ").append(fmtNum(m.value()))
                    .append(' ').append(m.pctChange() >= 0 ? "+" : "").append(fmtNum(m.pctChange())).append('%');
        }
        if (items.size() > n) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 数字紧凑格式化：整数去小数尾，小数最多 2 位。 */
    private String fmtNum(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(Math.round(d * 100.0) / 100.0);
        }
        return String.valueOf(v);
    }
}
