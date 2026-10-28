package com.aibi.module.datasource;

import com.aibi.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T9 驱动插件化的纯逻辑单测（不起 Spring、不连真库）：
 * 内置探测 / 驱动缺失优雅报错 / DriverShim 委托与 DriverManager 可见性。
 */
class DriverPluginRegistryTest {

    @Test
    void detectsBundledDriverClasses() {
        DriverPluginRegistry reg = new DriverPluginRegistry("");
        // mysql 驱动属默认包高频驱动，测试 classpath 必可见
        assertThat(reg.isBundled("com.mysql.cj.jdbc.Driver")).isTrue();
        assertThat(reg.isBundled("no.such.ClassDriver999")).isFalse();
        assertThat(reg.isBundled(null)).isFalse();
    }

    @Test
    void missingPluginDriverFailsGracefully() {
        // 未配置插件目录：冷驱动既非内置又无插件 jar → 「驱动未安装」业务异常（含补齐指引）
        DriverPluginRegistry reg = new DriverPluginRegistry("");
        assertThatThrownBy(() -> reg.requirePluginDriver("org.apache.hive.jdbc.HiveDriver"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驱动未安装")
                .hasMessageContaining("drivers-extended");
        // 目录配置了但不存在：扫描退化为空表，同样优雅报错，不裸抛 IO 异常
        DriverPluginRegistry reg2 = new DriverPluginRegistry("D:/no/such/dir-999");
        assertThatThrownBy(() -> reg2.requirePluginDriver("io.trino.jdbc.TrinoDriver"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驱动未安装");
    }

    @Test
    void driverShimDelegatesAndIsVisibleToDriverManager() throws Exception {
        AtomicReference<Properties> seen = new AtomicReference<>();
        Driver fake = new Driver() {
            @Override
            public Connection connect(String url, Properties info) {
                seen.set(info);
                return null; // 只验证委托发生，不建真连接
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:fakeit:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() {
                return 1;
            }

            @Override
            public int getMinorVersion() {
                return 0;
            }

            @Override
            public boolean jdbcCompliant() {
                return false;
            }

            @Override
            public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException("n/a");
            }
        };
        DriverPluginRegistry.DriverShim shim = new DriverPluginRegistry.DriverShim(fake);
        DriverManager.registerDriver(shim);
        try {
            assertThat(shim.acceptsURL("jdbc:fakeit://x/y")).isTrue();
            assertThat(shim.acceptsURL("jdbc:other://x/y")).isFalse();
            // Hikari 不设 driverClassName 时的选中路径：DriverManager 按 URL 找 → 命中 shim → 委托 fake
            Properties props = new Properties();
            assertThat(shim.connect("jdbc:fakeit://x/y", props)).isNull();
            assertThat(seen.get()).isSameAs(props);
            assertThat(DriverManager.getDriver("jdbc:fakeit://x/y")).isSameAs(shim);
            // 不匹配的 URL：DriverManager 不误选 shim
            assertThatThrownBy(() -> DriverManager.getDriver("jdbc:nosuchscheme://x"))
                    .isInstanceOf(SQLException.class);
        } finally {
            DriverManager.deregisterDriver(shim);
        }
    }
}
