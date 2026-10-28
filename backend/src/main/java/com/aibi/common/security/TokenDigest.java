package com.aibi.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 令牌摘要与随机令牌工具：会话、邀请链接、分享 token 等"不可逆凭证"的统一处理入口。
 *
 * <p><b>只存哈希，不存明文</b>（见《角色权限体系与组织协同设计》6.7 / 8.5）：
 * {@code login_session.token_hash} 与 {@code invitation.token_hash} 均为
 * {@code SHA-256(明文令牌)} 的十六进制串。这样即使元数据库被拖库，攻击者也拿不到
 * 可直接重放的访问令牌或邀请链接；明文只在生成时通过响应返回一次。
 *
 * <p>用 SHA-256 而非 BCrypt 是因为这两类令牌本身是<b>高熵随机串</b>（≥256 bit），
 * 不存在离线字典爆破的可能，无需 BCrypt 的慢哈希开销——而 {@code login_session} 的比对
 * 发生在<b>每个请求</b>上，慢哈希会直接拖垮吞吐。口令（低熵、人选的）仍必须用 BCrypt，
 * 见 {@code SecurityConfig.passwordEncoder()}。
 */
public final class TokenDigest {

    /** 邀请/会话令牌的随机字节数：32 字节 = 256 bit 熵。 */
    private static final int DEFAULT_TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TokenDigest() {
    }

    /**
     * 生成 URL 安全的随机令牌（Base64 URL 编码，无填充），默认 256 bit 熵。
     *
     * <p>用 Base64 而非十六进制是为了缩短邀请链接长度（32 字节 → 43 字符 vs 64 字符）。
     * 字符集 {@code A-Za-z0-9-_} 在 URL 路径与查询串中均无需转义。
     */
    public static String randomToken() {
        return randomToken(DEFAULT_TOKEN_BYTES);
    }

    public static String randomToken(int byteLength) {
        byte[] buf = new byte[Math.max(16, byteLength)];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** 生成会话 id（{@code login_session.sid}）：32 字符十六进制，定长便于建唯一索引与日志检索。 */
    public static String newSessionId() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return toHex(buf);
    }

    /**
     * SHA-256 十六进制摘要（定长 64 字符，与 DDL 里的 {@code CHAR(64)} 对齐）。
     *
     * @param raw 明文令牌；{@code null} 或空串返回 {@code null}（调用方据此跳过比对，
     *            避免把"空令牌的摘要"当成一个合法值存进唯一索引）
     */
    public static String sha256Hex(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常，属不可恢复错误
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 定长比较：防止按字节短路比较泄露摘要前缀的时序侧信道。
     *
     * <p>用于"提交的令牌摘要 vs 库中摘要"的比对场景（如分享 token 校验）。
     */
    public static boolean matches(String rawToken, String expectedHex) {
        String actual = sha256Hex(rawToken);
        if (actual == null || expectedHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHex.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
