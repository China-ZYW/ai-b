package com.aibi.module.drift;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构变更差分（T12-3 指标变更感知，纯函数、可单测）。
 *
 * <p>输入两轮 schema（表名 → 列名集合，调用方负责统一小写），输出确定性变更事件序列。
 * 感知锚点是「上轮快照 vs 本轮扫描」：物理库列被改名时 JDBC 无法告知改名关系
 * （{@link SchemaDriftService} 同款约束），会表现为一删一增两条事件，由上层文案提示可能为改名。
 *
 * <p><b>只有删除类事件会打断下游引用</b>（删表/删列）；新增事件仅记录供人工确认，不触发预警
 * ——判定宁缺勿滥，避免"加了个列"也来霸屏告警。
 */
public final class SchemaChangeDiff {

    /** 变更事件类型。 */
    public enum Kind {
        TABLE_ADDED("新增表"),
        TABLE_DROPPED("删除表"),
        COLUMN_ADDED("新增列"),
        COLUMN_DROPPED("删除列");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 删除类事件：可能打断既有引用，需要反查影响面。 */
        public boolean isDrop() {
            return this == TABLE_DROPPED || this == COLUMN_DROPPED;
        }
    }

    /** 单条变更事件（表/列名均为小写归一名；新增列 column 恒为 null）。 */
    public record Change(Kind kind, String table, String column) {
    }

    private SchemaChangeDiff() {
    }

    /**
     * 两轮 schema 差分。null 安全：prev 空视为首轮全新增（调用方通常以"无快照→只建基准"短路，不应走到告警）。
     *
     * @param prev 上轮快照（表名→列名集合，均已小写）
     * @param cur  本轮扫描（同口径）
     * @return 变更事件（先表级后列级；稳定序：按表名/列名排序，便于文案与断言）
     */
    public static List<Change> diff(Map<String, Set<String>> prev, Map<String, Set<String>> cur) {
        List<Change> out = new ArrayList<>();
        if (prev == null) {
            prev = Map.of();
        }
        if (cur == null) {
            cur = Map.of();
        }
        Set<String> allTables = new java.util.TreeSet<>();
        allTables.addAll(prev.keySet());
        allTables.addAll(cur.keySet());
        for (String table : allTables) {
            boolean inPrev = prev.containsKey(table);
            boolean inCur = cur.containsKey(table);
            if (inCur && !inPrev) {
                out.add(new Change(Kind.TABLE_ADDED, table, null));
                continue;
            }
            if (inPrev && !inCur) {
                out.add(new Change(Kind.TABLE_DROPPED, table, null));
                continue;
            }
            Set<String> prevCols = normalize(prev.get(table));
            Set<String> curCols = normalize(cur.get(table));
            for (String col : new java.util.TreeSet<>(prevCols)) {
                if (!curCols.contains(col)) {
                    out.add(new Change(Kind.COLUMN_DROPPED, table, col));
                }
            }
            for (String col : new java.util.TreeSet<>(curCols)) {
                if (!prevCols.contains(col)) {
                    out.add(new Change(Kind.COLUMN_ADDED, table, col));
                }
            }
        }
        return out;
    }

    /** 只保留会打断引用的删除类事件。 */
    public static List<Change> drops(List<Change> changes) {
        List<Change> out = new ArrayList<>();
        for (Change c : changes) {
            if (c.kind().isDrop()) {
                out.add(c);
            }
        }
        return out;
    }

    /** 同表"一删一增"列对提示（改名启发，仅用于文案"可能为改名"，不做自动替换）。 */
    public static Map<String, List<String[]>> renameHints(List<Change> changes) {
        Map<String, List<String[]>> out = new java.util.LinkedHashMap<>();
        if (changes == null || changes.isEmpty()) {
            return out;
        }
        Set<String> tables = new java.util.TreeSet<>();
        for (Change c : changes) {
            tables.add(c.table());
        }
        for (String table : tables) {
            List<String> dropped = changes.stream()
                    .filter(c -> c.kind() == Kind.COLUMN_DROPPED && c.table().equals(table))
                    .map(Change::column).toList();
            List<String> added = changes.stream()
                    .filter(c -> c.kind() == Kind.COLUMN_ADDED && c.table().equals(table))
                    .map(Change::column).toList();
            if (!dropped.isEmpty() && !added.isEmpty()) {
                List<String[]> pairs = new ArrayList<>();
                for (int i = 0; i < Math.min(dropped.size(), added.size()); i++) {
                    pairs.add(new String[]{dropped.get(i), added.get(i)});
                }
                out.put(table, pairs);
            }
        }
        return out;
    }

    private static Set<String> normalize(Set<String> cols) {
        if (cols == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String c : cols) {
            if (c != null && !c.isBlank()) {
                out.add(c.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
