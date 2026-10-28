package com.aibi.module.chat.nl2sql;

import java.util.Locale;

/**
 * 一条表间关联（连接键 + 来源）：多表问数里「该用哪个键 JOIN」的最小事实单元。
 *
 * <p>三个来源的<b>可信度依次递减</b>，Prompt 渲染时按来源分别措辞——库中真实约束与人工登记是确定信息
 * （必须使用），命名推断只是参考（可能推错，与业务不符时该忽略）：
 * <ol>
 *   <li>{@link Source#MANUAL}：人工在「表关联」里登记的口径，权威；</li>
 *   <li>{@link Source#FOREIGN_KEY}：JDBC 元数据里采到的外键约束；</li>
 *   <li>{@link Source#INFERRED}：按 {@code order_id → orders.id} 这类命名与类型约定推断，
 *       <b>没有外键约束的库（分析型库的常态）主要靠它</b>。</li>
 * </ol>
 *
 * @param leftTable   左侧表名（多为"多"方，即持有连接键的一方）
 * @param leftColumn  左侧连接列
 * @param rightTable  右侧表名（多为"一"方）
 * @param rightColumn 右侧连接列
 * @param source      关联来源
 * @param cardinality 基数（人工登记时可显式声明；外键与推断来源为 {@link Cardinality#UNKNOWN}，
 *                    由 {@link JoinGraph} 按"右列是否对方主键"推导）
 */
public record TableRelation(String leftTable, String leftColumn,
                            String rightTable, String rightColumn,
                            Source source, Cardinality cardinality) {

    /** 关联来源（决定可信度与 Prompt 措辞）。 */
    public enum Source {
        /** 人工登记（权威）。 */
        MANUAL,
        /** 库中外键约束。 */
        FOREIGN_KEY,
        /** 按命名 / 类型约定推断（仅供参考）。 */
        INFERRED
    }

    /**
     * 关联基数：决定 fan-out 提醒的强度。
     *
     * <p>{@link #MANY_TO_MANY} 只能由人工声明——两侧都不唯一时，元数据与命名约定都推不出来，
     * 而它恰恰是度量被放大最狠、也最难被发现的情形。
     */
    public enum Cardinality {
        /** 一对一：关联不放大行数。 */
        ONE_TO_ONE,
        /** 多对一：左多行对应右一行。 */
        MANY_TO_ONE,
        /** 多对多：两侧行数都会翻倍。 */
        MANY_TO_MANY,
        /** 未声明（由推导补齐）。 */
        UNKNOWN
    }

    /** 兼容构造器：未声明基数。 */
    public TableRelation(String leftTable, String leftColumn,
                         String rightTable, String rightColumn, Source source) {
        this(leftTable, leftColumn, rightTable, rightColumn, source, Cardinality.UNKNOWN);
    }

    /**
     * 同一条关联的无向标识：用于多来源去重（同一对连接键既被推断又被人工登记时，只保留更可信的那条）。
     * 表名取限定名末段并小写，使 {@code orders} 与 {@code dw.orders} 视为同一张表。
     */
    public String pairKey() {
        String a = shortName(leftTable) + "." + nullSafe(leftColumn).toLowerCase(Locale.ROOT);
        String b = shortName(rightTable) + "." + nullSafe(rightColumn).toLowerCase(Locale.ROOT);
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** 来源可信度排序值：越大越可信（去重时保留大的）。 */
    public int confidence() {
        return switch (source == null ? Source.INFERRED : source) {
            case MANUAL -> 2;
            case FOREIGN_KEY -> 1;
            case INFERRED -> 0;
        };
    }

    /** 基数安全取值（null 视为未声明）。 */
    public Cardinality safeCardinality() {
        return cardinality == null ? Cardinality.UNKNOWN : cardinality;
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int last = name.lastIndexOf('.');
        return (last < 0 ? name : name.substring(last + 1)).toLowerCase(Locale.ROOT);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
