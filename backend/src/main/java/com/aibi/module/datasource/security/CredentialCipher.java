package com.aibi.module.datasource.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源凭证加解密（AES-256/GCM）。
 *
 * <p>数据源账号密码经此加密后存入 {@code data_source.password_cipher}，绝不明文落库或打日志。
 * 主密钥由环境变量 {@code AIBI_CIPHER_KEY} 注入（此处对密钥材料做 SHA-256 派生出 32 字节 AES 密钥），
 * 与数据库分离管理（见设计文档 4 / 9）。密文格式：Base64( 12 字节 IV ‖ 密文 ‖ 16 字节 Tag )。
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${aibi.datasource.cipher-key}") String keyMaterial) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化凭证加密密钥失败", e);
        }
    }

    /** 加密明文，返回 Base64 密文。 */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv).put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /** 解密 Base64 密文，返回明文。 */
    public String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherTextBase64);
            ByteBuffer buffer = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败（检查 AIBI_CIPHER_KEY 是否一致）", e);
        }
    }

    /**
     * 探测该密文能否用本实例密钥成功解密（GCM Tag 校验通过即为 true），<b>不抛异常</b>。
     *
     * <p>专供 cipher-key 双 key 重加密迁移（{@link CipherMigrationRunner}）判定每行归属：
     * 能被新密钥解开＝已迁移可跳过；解不开＝待用旧密钥重加密。空/非法密文一律返回 false。
     */
    public boolean canDecrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null || cipherTextBase64.isBlank()) {
            return false;
        }
        try {
            decrypt(cipherTextBase64);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
