package com.aibi.module.datasource.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * cipher-key 双 key 重加密迁移器（安存治理 T1 · 步骤 3 的前置工具）。
 *
 * <p>{@link CredentialCipher} 是单密钥实现，直接更换 {@code AIBI_CIPHER_KEY} 会让
 * {@code data_source.password_cipher} 与 {@code llm_config.api_key_cipher} 两列历史密文立刻解不开
 * （所有业务库连不上、库内大模型 key 失效）。安全换 key 的唯一路径是<b>先重加密、后换值</b>：
 * 用旧密钥解密每行密文 → 用新密钥重新加密 → 回写，全程幂等。
 *
 * <p><b>正常启动零影响</b>：本 Bean 由 {@code aibi.datasource.cipher-migrate} 门控，缺省 false 时
 * 根本不会注册。仅当运维显式发起一次性维护时才启用，操作流程（见《docs/03-development/T1凭据轮换清单.md》第四节）：
 * <ol>
 *   <li>备份元库；先在 dev 库演练；</li>
 *   <li>注入 {@code AIBI_CIPHER_KEY}=<b>旧</b>密钥、{@code AIBI_CIPHER_KEY_NEW}=<b>新</b>密钥、
 *       {@code AIBI_CIPHER_MIGRATE}=true，启动 → 本迁移跑完即退出维护态；</li>
 *   <li>确认各数据源“测试连接”全绿后，去掉 {@code AIBI_CIPHER_KEY_NEW}/{@code AIBI_CIPHER_MIGRATE}，
 *       把 {@code AIBI_CIPHER_KEY} 改为新密钥，重启。</li>
 * </ol>
 *
 * <p>执行顺序取 {@link Ordered#LOWEST_PRECEDENCE}，确保晚于 {@code SchemaMigration} 与各领域 Seeder，
 * 使被迁移的两张表结构与数据均已就绪。表名/列名为硬编码常量，不来自外部输入，字符串拼接 SQL 安全。
 */
@Component
@ConditionalOnProperty(name = "aibi.datasource.cipher-migrate", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
public class CipherMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CipherMigrationRunner.class);

    /** 运行期实例持有的仍是【旧】密钥（AIBI_CIPHER_KEY），故作为解密源。 */
    private final CredentialCipher current;
    private final JdbcTemplate metaJdbc;
    private final String newKeyMaterial;

    public CipherMigrationRunner(CredentialCipher current,
                                 JdbcTemplate metaJdbc,
                                 @Value("${aibi.datasource.cipher-key-new:}") String newKeyMaterial) {
        this.current = current;
        this.metaJdbc = metaJdbc;
        this.newKeyMaterial = newKeyMaterial;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (newKeyMaterial == null || newKeyMaterial.isBlank()) {
            throw new IllegalStateException(
                    "已开启 cipher-key 迁移（aibi.datasource.cipher-migrate=true）但未提供新密钥 AIBI_CIPHER_KEY_NEW。"
                            + "请同时注入 AIBI_CIPHER_KEY（旧）与 AIBI_CIPHER_KEY_NEW（新）后重试；"
                            + "操作流程见 docs/03-development/T1凭据轮换清单.md 第四节。");
        }
        CredentialCipher target = new CredentialCipher(newKeyMaterial);
        log.warn("=== cipher-key 双 key 重加密迁移开始（旧→新）===");
        int ds = migrateColumn(target, "data_source", "password_cipher");
        int llm = migrateColumn(target, "llm_config", "api_key_cipher");
        log.warn("=== cipher-key 迁移结束：data_source 重加密 {} 行、llm_config 重加密 {} 行。"
                        + "请核验各数据源“测试连接”全绿后，移除 AIBI_CIPHER_KEY_NEW/AIBI_CIPHER_MIGRATE，"
                        + "并把 AIBI_CIPHER_KEY 改为新密钥再重启 ===",
                ds, llm);
    }

    /** 迁移某表某列的全部密文；返回实际重加密行数。任一行无法处理即中断（不误改环境密钥前，旧实例仍可正常解密未迁移行）。 */
    private int migrateColumn(CredentialCipher target, String table, String column) {
        List<Map<String, Object>> rows = metaJdbc.queryForList(
                "SELECT id, " + column + " AS c FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + column + " <> ''");
        int changed = 0;
        int skipped = 0;
        int failed = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String cipher = (String) row.get("c");
            try {
                String next = reencrypt(current, target, cipher);
                if (next == null) {
                    skipped++;   // 已是新密钥密文，或空值——幂等跳过
                    continue;
                }
                metaJdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", next, id);
                changed++;
            } catch (RuntimeException e) {
                failed++;
                log.error("cipher-key 迁移跳过异常行 table={} column={} id={}: {}", table, column, id, e.getMessage());
            }
        }
        log.info("表 {}.{}：扫描 {} 行，重加密 {}，跳过(已是新密钥) {}，失败 {}",
                table, column, rows.size(), changed, skipped, failed);
        if (failed > 0) {
            throw new IllegalStateException(String.format(
                    "cipher-key 迁移在 %s.%s 有 %d 行无法处理（既非旧密钥也非新密钥密文，疑数据损坏或密钥三方不符）；"
                            + "已中断。未改 AIBI_CIPHER_KEY 前旧实例仍可正常解密未迁移行，请排查后重试。",
                    table, column, failed));
        }
        return changed;
    }

    /**
     * 纯逻辑（无 Spring/DB，便于单测）：把用 {@code from} 密钥加密的密文改写为用 {@code to} 密钥加密。
     *
     * @return 新密文；空值或已能用 {@code to} 解密（视为已迁移）时返回 {@code null} 表示不处理（幂等）。
     * @throws IllegalStateException 密文既非 {@code to} 也非 {@code from} 所能解密（密钥不符/数据损坏）。
     */
    static String reencrypt(CredentialCipher from, CredentialCipher to, String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (to.canDecrypt(cipherText)) {
            return null;
        }
        if (!from.canDecrypt(cipherText)) {
            throw new IllegalStateException("密文无法用旧密钥解密（密钥不匹配或数据损坏）");
        }
        return to.encrypt(from.decrypt(cipherText));
    }
}
