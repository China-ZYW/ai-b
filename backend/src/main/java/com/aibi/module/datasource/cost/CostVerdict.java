package com.aibi.module.datasource.cost;

/**
 * 代价预估结论（T10-1 执行前代价熔断）。
 *
 * <p>三档语义（{@link Level}）：
 * <ul>
 *   <li>{@link Level#PASS}：低代价或无法判定（解析失败/行数取不到且有过滤）——正常放行，零提示；</li>
 *   <li>{@link Level#WARN}：预估扫描量落在告警带、或大表无过滤但保守不阻断——放行但记审计/日志；</li>
 *   <li>{@link Level#REJECT}：确凿超预算 / 全表扫大表 / 笛卡尔积——执行前主动拒绝，{@link #advice()} 给可执行拆分建议。</li>
 * </ul>
 *
 * @param level              结论档位
 * @param estimatedScanRows  预估扫描行数（无法估算时为 {@code -1}，即 UNKNOWN）
 * @param reason             判定依据（简短，供日志/审计；{@code PASS} 可为空）
 * @param advice             面向用户的建议文案（{@code REJECT} 时作为异常消息返回；其余可空）
 */
public record CostVerdict(Level level, long estimatedScanRows, String reason, String advice) {

    /** 代价档位。 */
    public enum Level { PASS, WARN, REJECT }

    /** 行数未知标记：{@link CostStatsProvider} 取不到该表估算行数时返回本值。 */
    public static final long UNKNOWN_ROWS = -1L;

    /** 是否应被熔断（挡在数据库之前）。 */
    public boolean blocked() {
        return level == Level.REJECT;
    }

    /** 恒放行的单例结论（总开关关闭、或保守放行时返回，对调用方零行为变更）。 */
    public static CostVerdict pass() {
        return new CostVerdict(Level.PASS, UNKNOWN_ROWS, "", null);
    }
}
