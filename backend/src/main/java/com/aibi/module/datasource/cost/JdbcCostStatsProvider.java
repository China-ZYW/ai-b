package com.aibi.module.datasource.cost;

import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.TenantDataSourceManager;
import com.aibi.module.datasource.model.DataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于系统视图的表行数估算源（T10-1 一期·启发式的统计输入）。
 *
 * <p>按库族查各自的内省视图取近似行数，并用<b>租户级 TTL 缓存</b>摊薄往返——同一表在一个缓存窗口内
 * 只查一次系统视图，避免每次问数都为代价预估额外打库。
 * <ul>
 *   <li>MySQL 族（含 MariaDB / Doris / StarRocks）：{@code information_schema.TABLES.TABLE_ROWS}；</li>
 *   <li>PostgreSQL：{@code pg_class.reltuples}（未 ANALYZE 时为 -1，视为未知）；</li>
 *   <li>ClickHouse：{@code system.tables.total_rows}；</li>
 *   <li>其余库族（SQLServer / Oracle / Hive / Presto / SQLite / 文件类）：无廉价近似源 → 直接返回未知，
 *       由 {@link CostGuard} 保守降级（未知不拒绝），绝不为估行数去打 {@code COUNT(*)} 全表。</li>
 * </ul>
 *
 * <p><b>永不抛出</b>：任何连接/查询异常都记 warn 并降级为 {@link CostVerdict#UNKNOWN_ROWS}，
 * 保证代价预估的统计环节绝不反过来阻断主链路。
 */
@Component
public class JdbcCostStatsProvider implements CostStatsProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcCostStatsProvider.class);

    /** 缓存有效期：行数统计本就近似，10 分钟陈旧可接受；同时抑制同表反复打系统视图。 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final TenantDataSourceManager dataSourceManager;

    /** key = tenantId:dsId:tableLower，value = (估算行数, 过期时刻)。 */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public JdbcCostStatsProvider(TenantDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    private record Cached(long rows, long expireAt) {
    }

    @Override
    public long estimateRowCount(DataSourceConfig config, String table) {
        if (config == null || table == null || table.isBlank()) {
            return CostVerdict.UNKNOWN_ROWS;
        }
        String shortName = shortName(table);
        String key = config.tenantId() + ":" + config.id() + ":" + shortName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expireAt() > now) {
            return hit.rows();
        }
        long rows = queryRowCount(config, shortName);
        cache.put(key, new Cached(rows, now + CACHE_TTL_MS));
        return rows;
    }

    /** 实际查系统视图；失败/不支持一律返回未知。 */
    private long queryRowCount(DataSourceConfig config, String table) {
        String sql = statsSql(config.type());
        if (sql == null) {
            return CostVerdict.UNKNOWN_ROWS;
        }
        try (Connection conn = dataSourceManager.getConnection(config);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setQueryTimeout(5);   // 元数据查询也必须快，卡住即放弃（降级未知）
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    // reltuples / TABLE_ROWS 未统计时可能为 -1 或 0；-1 明确视为未知
                    return rs.wasNull() || v < 0 ? CostVerdict.UNKNOWN_ROWS : v;
                }
            }
        } catch (Exception e) {
            log.warn("代价预估取表 [{}] 行数失败（降级为未知，不影响查询）: {}", table, e.getMessage());
        }
        return CostVerdict.UNKNOWN_ROWS;
    }

    /** 各库族取「当前库下某表近似行数」的参数化 SQL；不支持的库返回 {@code null}。 */
    private static String statsSql(DataSourceType type) {
        if (type == null) {
            return null;
        }
        return switch (type.family()) {
            case MYSQL -> "SELECT TABLE_ROWS FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
            case POSTGRESQL -> "SELECT c.reltuples::bigint FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = current_schema() AND c.relname = ?";
            case CLICKHOUSE -> "SELECT total_rows FROM system.tables "
                    + "WHERE database = currentDatabase() AND name = ?";
            default -> null;
        };
    }

    /** 取限定名末段短名（{@code db.orders} → {@code orders}），系统视图按短名匹配当前库内的表。 */
    private static String shortName(String table) {
        String t = table.trim();
        int last = t.lastIndexOf('.');
        return last < 0 ? t : t.substring(last + 1);
    }
}
