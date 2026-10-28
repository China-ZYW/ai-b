package com.aibi.module.chat.nl2sql;

/**
 * 单次问数的执行统计（可变载体，问数准确率与图表能力升级 V3 - 数据完整度）。
 *
 * <p>{@link QueryContext} 以引用持有本对象，{@link Nl2SqlEngine} 在生成/执行/修复过程中回填，
 * {@code ChatService} 据此组装 {@code ResultMeta} 返回前端。record 上下文本身仍不可变，仅其
 * 持有的统计对象可变——这样既能回传执行元信息，又不必改动 {@code generate} 的返回签名。
 */
public class ExecStats {

    /** SQL 生成/执行尝试次数（含修正重试，首次为 1）。 */
    private int sqlAttempts = 1;
    /** 是否触发过“执行报错 → LLM 修正”。 */
    private boolean repaired;
    /** 是否触发过“空结果自修复”（0 行时放宽过滤重试）。 */
    private boolean emptyRetried;
    /** 结果是否被行数上限截断（rowCount 触顶 maxRows 的近似判定）。 */
    private boolean truncated;
    /** self-consistency 生成的候选 SQL 数（未启用时为 0）。 */
    private int consistencyCandidates;
    /** 是否触发过"辅助模型交叉校验"并采纳了其修正版 SQL（V5）。 */
    private boolean crossChecked;
    /** 双生成共识（cross-consistency）是否一致（true=主辅模型生成一致，高置信）。 */
    private boolean crossConsistent;

    public int getSqlAttempts() {
        return sqlAttempts;
    }

    public void setSqlAttempts(int sqlAttempts) {
        this.sqlAttempts = sqlAttempts;
    }

    public boolean isRepaired() {
        return repaired;
    }

    public void setRepaired(boolean repaired) {
        this.repaired = repaired;
    }

    public boolean isEmptyRetried() {
        return emptyRetried;
    }

    public void setEmptyRetried(boolean emptyRetried) {
        this.emptyRetried = emptyRetried;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public int getConsistencyCandidates() {
        return consistencyCandidates;
    }

    public void setConsistencyCandidates(int consistencyCandidates) {
        this.consistencyCandidates = consistencyCandidates;
    }

    public boolean isCrossChecked() {
        return crossChecked;
    }

    public void setCrossChecked(boolean crossChecked) {
        this.crossChecked = crossChecked;
    }

    public boolean isCrossConsistent() {
        return crossConsistent;
    }

    public void setCrossConsistent(boolean crossConsistent) {
        this.crossConsistent = crossConsistent;
    }
}
