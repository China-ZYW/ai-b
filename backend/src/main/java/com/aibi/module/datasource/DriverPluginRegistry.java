package com.aibi.module.datasource;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T9 驱动插件化：冷数据源驱动（SQL Server / Oracle / ClickHouse / Hive / Trino / Presto）
 * 已移出默认 fat jar（pom profile {@code drivers-extended} 可选打回），运行期按需从插件目录加载。
 *
 * <p><b>加载机制</b>：{@code aibi.datasource.driver-dir} 目录下的 *.jar 用 {@link URLClassLoader}
 * （parent=应用 CL，共享 slf4j 等）经 {@link ServiceLoader} 发现 {@link Driver} 实现，按驱动类名索引。
 * DriverManager 会按"调用方 ClassLoader 能否见到驱动类"过滤已注册驱动——插件 CL 里的驱动类对
 * Hikari（应用 CL）不可见，故用经典 <b>DriverShim</b> 包装真实驱动实例注册：shim 类在应用 jar 内可见，
 * {@code connect/acceptsURL} 委托插件驱动，Hikari 不显式设 driverClassName 时按 URL 自动命中 shim。
 *
 * <p><b>优雅失败（§90 链路延伸）</b>：驱动既非内置、插件目录又没有时，抛
 * {@code "驱动未安装"} 业务异常（中文指引放置目录），而非 ClassNotFoundException 裸抛。
 */
@Component
public class DriverPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(DriverPluginRegistry.class);

    private final String driverDir;
    /** 懒扫描结果：驱动类名 → 插件 CL 里的真实 Driver 实例；null=尚未扫描，空表不固化（补放 jar 可重扫）。 */
    private volatile Map<String, Driver> pluginDrivers;
    /** 已注册的 shim：驱动类名 → shim 实例（避免同一驱动重复注册进 DriverManager）。 */
    private final Map<String, Driver> registeredShims = new ConcurrentHashMap<>();

    public DriverPluginRegistry(@Value("${aibi.datasource.driver-dir:}") String driverDir) {
        this.driverDir = driverDir == null ? "" : driverDir.trim();
    }

    /** 应用 ClassLoader 能否直接加载该驱动类（= 高频内置驱动，走原有 driverClassName 显式路径）。 */
    public boolean isBundled(String driverClass) {
        if (driverClass == null) {
            return false;
        }
        try {
            Class.forName(driverClass, false, DriverPluginRegistry.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 插件驱动就绪化：从插件目录解析该驱动类并保证其 DriverShim 已注册进 DriverManager。
     *
     * @return 真实驱动实例（供日志/诊断）
     * @throws BizException 插件目录未提供该驱动 → "驱动未安装" 优雅报错
     */
    public Driver requirePluginDriver(String driverClass) {
        Driver d = scan().get(driverClass);
        if (d == null) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                    "驱动未安装：" + driverClass + " 未随默认包打包，插件目录中也未找到。"
                            + (driverDir.isEmpty()
                            ? "请设置 aibi.datasource.driver-dir 并放入对应驱动 jar，"
                            + "或以 mvn package -Pdrivers-extended 重新打包。"
                            : "请将驱动 jar（含必要传递依赖）放入 " + driverDir + " 后重试，"
                            + "或以 mvn package -Pdrivers-extended 重新打包。"));
        }
        registeredShims.computeIfAbsent(driverClass, k -> {
            Driver shim = new DriverShim(d);
            try {
                DriverManager.registerDriver(shim);
                log.info("插件驱动已注册: {}（来自 {}）", k, driverDir);
            } catch (SQLException e) {
                throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                        "插件驱动注册失败: " + k + " — " + e.getMessage(), e);
            }
            return shim;
        });
        return d;
    }

    /** 懒扫描插件目录；空结果不固化（允许运行期补放 jar 后直接生效，不必重启）。 */
    private Map<String, Driver> scan() {
        Map<String, Driver> local = pluginDrivers;
        if (local != null && !local.isEmpty()) {
            return local;
        }
        synchronized (this) {
            if (pluginDrivers != null && !pluginDrivers.isEmpty()) {
                return pluginDrivers;
            }
            Map<String, Driver> found = new HashMap<>();
            File dir = new File(driverDir);
            File[] jars = driverDir.isEmpty() || !dir.isDirectory() ? null : dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                try {
                    URL[] urls = new URL[jars.length];
                    for (int i = 0; i < jars.length; i++) {
                        urls[i] = jars[i].toURI().toURL();
                    }
                    // parent=应用 CL：驱动 jar 里缺的共享依赖（slf4j 等）回落应用层，其余类只在插件 CL 可见
                    URLClassLoader cl = new URLClassLoader(urls, DriverPluginRegistry.class.getClassLoader());
                    for (Driver d : ServiceLoader.load(Driver.class, cl)) {
                        found.put(d.getClass().getName(), d);
                    }
                    log.info("驱动插件目录扫描完成: {}（{} 个 jar，发现 {} 个驱动）",
                            dir.getAbsolutePath(), jars.length, found.size());
                } catch (Throwable t) {
                    // 目录不可读/坏 jar 不炸启动：冷驱动退化为"驱动未安装"报错，内置驱动不受影响
                    log.warn("驱动插件目录扫描失败（冷数据源将报「驱动未安装」）: {} — {}", driverDir, t.toString());
                }
            }
            pluginDrivers = found;
            return found;
        }
    }

    /**
     * DriverManager 按调用方 ClassLoader 过滤可见驱动，插件 CL 的驱动类对 Hikari 不可见；
     * 本 shim 在应用 jar 内可见，connect/acceptsURL 全量委托真实驱动实例。
     */
    static final class DriverShim implements Driver {

        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return acceptsURL(url) ? delegate.connect(url, info) : null;
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
