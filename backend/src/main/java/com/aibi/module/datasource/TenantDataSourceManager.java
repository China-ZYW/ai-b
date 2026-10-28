package com.aibi.module.datasource;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 租户级数据源连接池管理器（数据隔离第③层：连接隔离）。
 *
 * <p>按 {@code tenantId:datasourceId} 为每个租户的每个数据源维护一个独立的小连接池（默认 max 3），
 * 池之间绝不复用，从连接层面杜绝跨租户访问。针对 4GB 内存：{@code minimumIdle=0 + idleTimeout} 让空闲池
 * 自动释放连接，避免大量租户把连接数堆爆；{@code maxPools} 给<b>池总数</b>封顶，超出的淘汰最久未用的池，
 * 防止租户/数据源无限增长把进程内存吃满。
 *
 * <p>连通性测试走 {@link #createTransientDataSource(DataSourceConfig)} 的<b>瞬时池</b>：用完即关、不进托管池，
 * 既避免把“未保存的草稿配置”以 {@code tenant:null} 为键污染托管池（曾导致连接池键冲突与永不回收），
 * 也避免一次测试连接留下常驻连接池造成资源泄漏。
 */
@Component
public class TenantDataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceManager.class);

    /** 托管连接池：键 = tenantId:datasourceId。 */
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** 池键的插入顺序（FIFO），用于超上限时淘汰最久未用者；与 {@link #pools} 弱一致即可。 */
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    private final int maxPoolSize;
    private final int maxPools;
    /** T9 驱动插件化：冷驱动不在默认包时，经插件目录加载/优雅报错。 */
    private final DriverPluginRegistry driverPlugins;

    public TenantDataSourceManager(@Value("${aibi.datasource.pool.max-size:3}") int maxPoolSize,
                                  @Value("${aibi.datasource.pool.max-pools:200}") int maxPools,
                                  DriverPluginRegistry driverPlugins) {
        this.maxPoolSize = maxPoolSize;
        this.maxPools = Math.max(1, maxPools);
        this.driverPlugins = driverPlugins;
    }

    private String key(DataSourceConfig config) {
        return config.tenantId() + ":" + config.id();
    }

    /** 获取（或惰性创建）该租户该数据源的连接池。 */
    public DataSource getDataSource(DataSourceConfig config) {
        String k = key(config);
        HikariDataSource existing = pools.get(k);
        if (existing != null) {
            return existing;
        }
        // 双检 + putIfAbsent：并发下只有一个线程真正建池，其余复用既有，避免重复建池浪费连接
        HikariDataSource created = createPool(config);
        HikariDataSource prev = pools.putIfAbsent(k, created);
        if (prev == null) {
            order.addLast(k);
            evictIfOver();
            return created;
        }
        created.close();   // 落后的线程建出的池没用上，立即释放
        return prev;
    }

    /** 从租户专属连接池取一个连接（调用方负责关闭，连接归还池而非物理断开）。 */
    public Connection getConnection(DataSourceConfig config) throws SQLException {
        return getDataSource(config).getConnection();
    }

    /**
     * 连通性测试专用：新建一个<b>瞬时</b>连接池并交还调用方，调用方须用 try-with-resources 关闭。
     * 该池不进入 {@link #pools}、不污染键空间，测试结束即释放连接——尤其对 id 为 null 的草稿配置，
     * 不会以 {@code tenant:null} 误建常驻池。
     */
    public HikariDataSource createTransientDataSource(DataSourceConfig config) {
        return createPool(config);
    }

    private HikariDataSource createPool(DataSourceConfig config) {
        String driver = config.type().driverClass();
        if (driver == null) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                    "该数据源类型不支持 JDBC 连接: " + config.type());
        }
        // JDBC 4.0+ 驱动经 ServiceLoader 自注册，无需 Class.forName；Hikari 按 JDBC URL 自动选用驱动。
        // 若驱动依赖缺失，下方 new HikariDataSource 会抛出明确的“找不到驱动”错误。

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.buildJdbcUrl());
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        if (driverPlugins.isBundled(driver)) {
            // 内置高频驱动（MySQL 系/PG/SQLite）：保持原显式指定，早失败、语义清晰
            hc.setDriverClassName(driver);
        } else {
            // T9 插件驱动：类只存在于插件 CL，setDriverClassName 会 Class.forName 失败；
            // 改经 DriverShim 注册进 DriverManager 后不设 driverClassName，Hikari 按 JDBC URL 自动命中 shim；
            // 插件目录也没有时 requirePluginDriver 抛「驱动未安装」优雅报错（§90 链路）。
            driverPlugins.requirePluginDriver(driver);
        }
        hc.setMaximumPoolSize(maxPoolSize);
        hc.setMinimumIdle(0);              // 空闲不放常驻连接，省内存
        if (config.type().family() == DataSourceType.Family.SQLITE) {
            // SQLite 为本地文件库：Hikari 的 setReadOnly(true) 会触发驱动异常
            // "Cannot change read-only flag after establishing a connection"（只读须由 JDBC URL 的 ?mode=ro 控制且文件须存在）；
            // 故 SQLite 跳过 Hikari 层只读，避免该类型数据源建连即失败。其余类型仍保持全局只读第二重防护。
            log.info("SQLite 数据源跳过 Hikari readOnly（驱动不兼容），其只读由 JDBC URL 控制");
        } else {
            hc.setReadOnly(true);          // 全局只读，第二重防护
        }
        hc.setConnectionTimeout(10_000);
        hc.setIdleTimeout(60_000);
        hc.setMaxLifetime(300_000);
        hc.setPoolName("aibi-ds-" + key(config));
        log.info("创建租户数据源连接池: {}", config.safeDescriptor());
        return new HikariDataSource(hc);
    }

    /** 池总数超上限时，淘汰队首（最久未用）的池并释放其连接。 */
    private void evictIfOver() {
        while (pools.size() > maxPools) {
            String k = order.pollFirst();
            if (k == null) {
                break;
            }
            HikariDataSource ds = pools.remove(k);
            if (ds != null) {
                ds.close();
                log.info("连接池总数超上限（{}），已淘汰最久未用池: {}", maxPools, k);
            }
        }
    }

    /** 配置变更/删除时移除并关闭对应连接池。 */
    public void evict(Long tenantId, Long datasourceId) {
        String k = tenantId + ":" + datasourceId;
        HikariDataSource ds = pools.remove(k);
        order.remove(k);
        if (ds != null) {
            ds.close();
            log.info("已关闭并移除数据源连接池: tenant={}, ds={}", tenantId, datasourceId);
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        order.clear();
    }
}
