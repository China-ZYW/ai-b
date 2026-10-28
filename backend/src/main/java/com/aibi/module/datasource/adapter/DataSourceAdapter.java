package com.aibi.module.datasource.adapter;

import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;

import java.util.List;

/**
 * 数据源适配器：屏蔽不同数据库的连接、扫描、执行差异（见设计文档 6）。
 *
 * <p>实现类：
 * <ul>
 *   <li>{@code JdbcDataSourceAdapter}——通用 JDBC，覆盖关系型 + OLAP 全部类型，差异由 {@code SqlDialect} 承担；</li>
 *   <li>{@code CsvFileAdapter}——文件类（CSV/Excel），载入内存库后以 SQL 查询。</li>
 * </ul>
 */
public interface DataSourceAdapter {

    /** 是否支持该数据源类型。 */
    boolean supports(DataSourceType type);

    /** 测试连接；失败抛 {@code BizException(DATASOURCE_CONNECT_FAILED)}。 */
    void testConnection(DataSourceConfig config);

    /** 扫描表结构元数据，供生成 LLM 提示词。 */
    List<TableMeta> scanSchema(DataSourceConfig config);

    /**
     * 列出同一连接下可选作“跨库联合分析”的库/模式名（默认空实现）。
     *
     * <p>仅用于配置界面的候选清单，让用户不必先查出确切库名才能填。
     * 实现方拿不到清单时应返回空列表而非抛异常：这只是个便利项，
     * 不能因此让“测试连接”本身失败。
     */
    default List<String> listCatalogs(DataSourceConfig config) {
        return List.of();
    }

    /**
     * 只读执行一条已校验的 SELECT，并按方言与 JDBC 双重限制行数。
     *
     * @param sql            已通过 {@code SqlValidator} 校验的安全 SQL
     * @param maxRows        最大返回行数（硬上限）
     * @param timeoutSeconds 查询超时（秒）
     */
    QueryResult execute(DataSourceConfig config, String sql, long maxRows, int timeoutSeconds);

    /**
     * 只读执行一条已校验的 SELECT（支持位置参数 {@code ?} 占位符），并按方言与 JDBC 双重限制行数。
     *
     * <p>用于下钻/联动切片等需要把筛选值安全绑定到 SQL 的场景：调用方<b>必须</b>用本方法而非字符串拼接，
     * 杜绝注入。{@code params} 为 null 或空表示无参数，等价于 {@link #execute(DataSourceConfig, String, long, int)}。
     *
     * @param sql            已通过 {@code SqlValidator} 校验的安全 SQL（含 ? 占位符）
     * @param params         位置参数（对应 SQL 中的 ? 占位符）
     * @param maxRows        最大返回行数（硬上限）
     * @param timeoutSeconds 查询超时（秒）
     */
    QueryResult execute(DataSourceConfig config, String sql, List<Object> params, long maxRows, int timeoutSeconds);

    /**
     * 失效适配器为该数据源维护的本地缓存（如文件类载入的 SQLite 临时库）。
     * 数据源被替换/删除时调用，令下次查询基于最新配置重建；无缓存的适配器保持默认空实现。
     */
    default void evictCache(Long tenantId, Long datasourceId) {
    }
}
