package com.aibi.common.config;

import com.aibi.common.tenant.PlanType;
import com.aibi.module.datasource.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 租户/账号/演示数据源播种（T8.1 拆分自 {@link DataSeeder}，幂等语义逐方法保持）。
 *
 * <p>★ 开源安全：未注入 {@code AIBI_SUPERADMIN_PASSWORD} / {@code AIBI_DEMO_ADMIN_PASSWORD} 时，
 * 本类会在首次播种时用 {@link SecureRandom} 即时生成 16 字节 URL-Safe 随机口令，
 * 并以 WARN 日志一次性打印——鼓励部署者落盘后立即修改，或以环境变量固定注入重启。
 * 代码与配置文件不预置任何硬编码默认口令（防仓库内部泄密）。
 */
@Component
public class TenantSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(TenantSeeder.class);

    private final JdbcTemplate metaJdbc;
    private final CredentialCipher cipher;
    private final PasswordEncoder passwordEncoder;

    @Value("${aibi.demo.tenant-id:1}")
    private long tenantId;
    @Value("${aibi.demo.plan:PRO}")
    private String plan;
    @Value("${aibi.demo.datasource-id:1}")
    private long datasourceId;
    @Value("${aibi.demo.name:演示电商库}")
    private String name;
    @Value("${aibi.demo.type:MYSQL}")
    private String type;
    @Value("${aibi.demo.host:localhost}")
    private String host;
    @Value("${aibi.demo.port:3306}")
    private int port;
    @Value("${aibi.demo.database:aibi_demo}")
    private String database;
    @Value("${aibi.demo.username:root}")
    private String username;
    @Value("${aibi.demo.password:root}")
    private String password;
    @Value("${aibi.demo.jdbc-params:useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8}")
    private String jdbcParams;
    @Value("${aibi.demo.admin-username:admin}")
    private String adminUsername;
    /** 开源仓不预置默认口令；留空时首启随机生成并 WARN 打印。 */
    @Value("${aibi.demo.admin-password:}")
    private String adminPassword;
    @Value("${aibi.superadmin.username:superadmin}")
    private String superAdminUsername;
    /** 开源仓不预置默认口令；留空时首启随机生成并 WARN 打印。 */
    @Value("${aibi.superadmin.password:}")
    private String superAdminPassword;

    public TenantSeeder(JdbcTemplate metaJdbc, CredentialCipher cipher, PasswordEncoder passwordEncoder) {
        this.metaJdbc = metaJdbc;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String name() {
        return "tenant/users/datasource";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void seed() {
        seedTenant();
        seedAdminUser();
        seedSuperAdmin();
        seedDataSource();
    }

    private void seedTenant() {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id = ?", Integer.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        PlanType planType = PlanType.parse(plan);
        int dailyLimit = switch (planType) {
            case PRO -> 1000;
            case ENTERPRISE -> -1;
            default -> 20;
        };
        metaJdbc.update(
                "INSERT INTO tenant(id, name, plan, isolation_mode, status, daily_limit, total_limit, total_used) "
                        + "VALUES (?, ?, ?, ?, 1, ?, -1, 0)",
                tenantId, "演示租户", planType.name(), planType.defaultIsolationMode().name(), dailyLimit);
        log.info("已播种演示租户: id={}, plan={}, dailyLimit={}", tenantId, planType, dailyLimit);
    }

    private void seedAdminUser() {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, adminUsername);
        if (count != null && count > 0) {
            return;
        }
        String raw = adminPassword;
        boolean generated = false;
        if (raw == null || raw.isBlank()) {
            raw = randomPassword();
            generated = true;
        }
        metaJdbc.update(
                "INSERT INTO app_user(tenant_id, username, password_hash, nickname, status) VALUES (?, ?, ?, ?, 1)",
                tenantId, adminUsername, passwordEncoder.encode(raw), "演示管理员");
        if (generated) {
            log.warn("\u26a0\ufe0f 首次播种演示账号 {}：未配置 aibi.demo.admin-password / AIBI_DEMO_ADMIN_PASSWORD，已随机生成口令 [{}] ——"
                            + " 请妥善保存并登录后立即修改；如需固定口令，下次启动前写入 local.env 或在环境变量中注入。",
                    adminUsername, raw);
        } else {
            log.info("已播种演示用户: {} (tenant={})，口令取自 AIBI_DEMO_ADMIN_PASSWORD", adminUsername, tenantId);
        }
    }

    private void seedSuperAdmin() {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, superAdminUsername);
        if (count != null && count > 0) {
            return;
        }
        String raw = superAdminPassword;
        boolean generated = false;
        if (raw == null || raw.isBlank()) {
            raw = randomPassword();
            generated = true;
        }
        metaJdbc.update(
                "INSERT INTO app_user(tenant_id, username, password_hash, nickname, role, status) "
                        + "VALUES (?, ?, ?, ?, 'SUPER_ADMIN', 1)",
                0L, superAdminUsername, passwordEncoder.encode(raw), "超级管理员");
        if (generated) {
            log.warn("\u26a0\ufe0f 首次播种超级管理员 {}：未配置 AIBI_SUPERADMIN_PASSWORD，已随机生成口令 [{}] ——"
                            + " 请妥善保存并登录后立即修改；生产部署下次启动前必须写入 backend/local.env 或 systemd EnvironmentFile。",
                    superAdminUsername, raw);
        } else {
            log.info("已播种超级管理员: {} (tenant=0)，口令取自 AIBI_SUPERADMIN_PASSWORD", superAdminUsername);
        }
    }

    /** 16 字节 URL-Safe Base64 无填充随机串（≈ 22 个字符），兼容 BCrypt 而无需额外预处理。 */
    private static String randomPassword() {
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private void seedDataSource() {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM data_source WHERE id = ? AND tenant_id = ?",
                Integer.class, datasourceId, tenantId);
        if (count != null && count > 0) {
            return;
        }
        metaJdbc.update(
                "INSERT INTO data_source(id, tenant_id, name, type, host, port, db_name, username, "
                        + "password_cipher, jdbc_params, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                datasourceId, tenantId, name, type.trim().toUpperCase(), host, port, database,
                username, cipher.encrypt(password), jdbcParams);
        log.info("已播种演示数据源: id={} → {}://{}:{}/{}", datasourceId, type, host, port, database);
    }
}
