package com.aibi.module.datasource.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表结构元数据（由适配器扫描得到，供 SchemaProvider 生成 LLM 提示词）。
 *
 * <p><b>主键 / 外键</b>：多表问数的准确率主要卡在「LLM 不知道该用哪个键连接、连接后粒度会不会翻倍」。
 * 这两组信息由 {@code JdbcDataSourceAdapter} 扫描时一并采集，既渲染进 schema 行供 LLM 直接读取，
 * 也供 {@code SchemaLinker} 做关系扩散（把问法里没出现、但必须 join 的维度表补进切片）与
 * {@code PromptBuilder} 生成【关联关系】段。无外键约束的库（Hive / ClickHouse 等）为空列表，
 * 行为与采集前完全一致。
 *
 * @param name        表名
 * @param comment     表注释（可空）
 * @param columns     列元数据
 * @param primaryKeys 主键列名（按 KEY_SEQ 顺序，复合主键为多列；采集不到时为空）
 * @param foreignKeys 外键（本表列 → 引用表.列；采集不到时为空）
 */
public record TableMeta(String name, String comment, List<ColumnMeta> columns,
                        List<String> primaryKeys, List<FkMeta> foreignKeys) {

    public TableMeta {
        columns = columns == null ? List.of() : columns;
        primaryKeys = primaryKeys == null ? List.of() : primaryKeys;
        foreignKeys = foreignKeys == null ? List.of() : foreignKeys;
    }

    /** 兼容构造器：无主键 / 外键信息（文件类数据源等采集不到约束）。 */
    public TableMeta(String name, String comment, List<ColumnMeta> columns) {
        this(name, comment, columns, List.of(), List.of());
    }

    /**
     * 列元数据。
     *
     * @param name    列名
     * @param type    数据类型
     * @param comment 列注释（可空）
     */
    public record ColumnMeta(String name, String type, String comment) {
    }

    /**
     * 外键：本表 {@code column} 引用 {@code refTable.refColumn}。
     *
     * @param column    本表列名
     * @param refTable  被引用表名（跨库时带与表名一致的限定前缀）
     * @param refColumn 被引用列名
     */
    public record FkMeta(String column, String refTable, String refColumn) {
    }

    /** 生成紧凑的一行 schema 文本：{@code 表 orders: id(int,主键), user_id(int,外键→users.id)}。 */
    public String toSchemaLine() {
        StringBuilder sb = new StringBuilder("表 ").append(name);
        if (comment != null && !comment.isBlank()) {
            sb.append("(").append(comment).append(")");
        }
        sb.append(": ");
        Set<String> pks = lowerKeySet(primaryKeys);
        Map<String, FkMeta> fks = new LinkedHashMap<>();
        for (FkMeta fk : foreignKeys) {
            if (fk != null && fk.column() != null) {
                fks.putIfAbsent(fk.column().toLowerCase(Locale.ROOT), fk);
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta c = columns.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(c.name()).append("(").append(c.type());
            if (c.comment() != null && !c.comment().isBlank()) {
                sb.append(",").append(c.comment());
            }
            // 键标记紧跟列注释：LLM 读到该列即知道能否用作连接键、连接后是否一对多
            if (c.name() != null && pks.contains(c.name().toLowerCase(Locale.ROOT))) {
                sb.append(",主键");
            }
            FkMeta fk = c.name() == null ? null : fks.get(c.name().toLowerCase(Locale.ROOT));
            if (fk != null) {
                sb.append(",外键→").append(fk.refTable()).append(".").append(fk.refColumn());
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private static Set<String> lowerKeySet(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                out.add(n.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
