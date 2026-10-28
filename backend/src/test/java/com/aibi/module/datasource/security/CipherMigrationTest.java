package com.aibi.module.datasource.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cipher-key 双 key 重加密的纯逻辑单测（T1 步骤 3；不起 Spring、不连库）。
 *
 * <p>覆盖 {@link CredentialCipher#canDecrypt} 探测语义与 {@link CipherMigrationRunner#reencrypt}
 * 的转换 / 幂等 / 异常三条主径，确保迁移工具"能安全换 key"的核心逻辑正确。
 */
class CipherMigrationTest {

    private static CredentialCipher cipher(String material) {
        return new CredentialCipher(material);
    }

    @Test
    void roundTripWithSameKey() {
        CredentialCipher k = cipher("old-key-material-AAA");
        String enc = k.encrypt("s3cret-p@ss");
        assertThat(enc).isNotBlank().isNotEqualTo("s3cret-p@ss");
        assertThat(k.decrypt(enc)).isEqualTo("s3cret-p@ss");
    }

    @Test
    void canDecryptTrueOnlyForOwningKey() {
        CredentialCipher a = cipher("key-A");
        CredentialCipher b = cipher("key-B");
        String encA = a.encrypt("payload");
        assertThat(a.canDecrypt(encA)).isTrue();
        assertThat(b.canDecrypt(encA)).isFalse();
        assertThat(a.canDecrypt(null)).isFalse();
        assertThat(a.canDecrypt("  ")).isFalse();
        assertThat(a.canDecrypt("not-base64-@@@")).isFalse();
    }

    @Test
    void reencryptConvertsOldKeyCiphertextToNewKey() {
        CredentialCipher oldKey = cipher("key-OLD");
        CredentialCipher newKey = cipher("key-NEW");
        String encOld = oldKey.encrypt("db-password-123");

        String encNew = CipherMigrationRunner.reencrypt(oldKey, newKey, encOld);

        assertThat(encNew).isNotNull().isNotEqualTo(encOld);
        assertThat(newKey.decrypt(encNew)).isEqualTo("db-password-123");
        // 旧密钥已无法解开重加密后的密文（确证已迁移）
        assertThat(oldKey.canDecrypt(encNew)).isFalse();
    }

    @Test
    void reencryptIsIdempotentOnAlreadyMigrated() {
        CredentialCipher oldKey = cipher("key-OLD");
        CredentialCipher newKey = cipher("key-NEW");
        String encNew = newKey.encrypt("already-migrated");

        // 已是新密钥密文 → 返回 null 表示不处理（重跑迁移安全）
        assertThat(CipherMigrationRunner.reencrypt(oldKey, newKey, encNew)).isNull();
    }

    @Test
    void reencryptSkipsBlankInput() {
        CredentialCipher oldKey = cipher("key-OLD");
        CredentialCipher newKey = cipher("key-NEW");
        assertThat(CipherMigrationRunner.reencrypt(oldKey, newKey, null)).isNull();
        assertThat(CipherMigrationRunner.reencrypt(oldKey, newKey, "")).isNull();
        assertThat(CipherMigrationRunner.reencrypt(oldKey, newKey, "   ")).isNull();
    }

    @Test
    void reencryptRejectsUnknownKeyCiphertext() {
        CredentialCipher oldKey = cipher("key-OLD");
        CredentialCipher newKey = cipher("key-NEW");
        CredentialCipher thirdKey = cipher("key-OTHER");
        String encThird = thirdKey.encrypt("foreign");

        // 既非新密钥、也非旧密钥可解 → 判为数据损坏/密钥不符，中断而非误改
        assertThatThrownBy(() -> CipherMigrationRunner.reencrypt(oldKey, newKey, encThird))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法用旧密钥解密");
    }
}
