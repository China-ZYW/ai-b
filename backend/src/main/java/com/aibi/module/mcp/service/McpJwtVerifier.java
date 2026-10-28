package com.aibi.module.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 的 Bearer JWT 校验器（OAuth 2.1 资源服务器角色）：按 JWKS 验 RS256 签名。
 *
 * <p><b>只实现 RS256</b>：它是 IdP（Keycloak / Auth0 / Okta / Azure AD）的事实标准，
 * 且 JDK 原生即可由 {@code n}/{@code e} 还原 RSA 公钥。ES256 需要自行解析 EC 点，
 * 一期不引入——遇到时给出明确报错而不是静默放行。
 *
 * <p><b>密钥缓存</b>：JWKS 按 {@code kid} 缓存（{@value #CACHE_TTL_MINUTES} 分钟）。
 * 遇到未知 {@code kid} 时最多回源一次（常见于 IdP 轮转密钥），仍然拿不到才判失败——
 * 这样既能感知轮转，又不会让每个请求都去拉 JWKS。
 */
@Component
public class McpJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(McpJwtVerifier.class);

    private static final long CACHE_TTL_MINUTES = 30;

    private final ObjectMapper objectMapper;
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile long loadedAt = 0L;
    private volatile String lastJwksUrl = "";

    public McpJwtVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验一个 JWT 的签名并解析出载荷。
     *
     * @return 载荷声明（未经业务校验）；签名或算法不支持时返回 {@code null}
     */
    public Map<String, Object> verify(String token, String jwksUrl) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        JsonNode header;
        try {
            header = objectMapper.readTree(decode(parts[0]));
        } catch (Exception e) {
            return null;
        }
        String alg = header.path("alg").asText("");
        String kid = header.path("kid").asText("");
        if (!"RS256".equals(alg)) {
            log.warn("MCP Bearer 令牌算法不支持: alg={}（当前仅支持 RS256）", alg);
            return null;
        }
        PublicKey key = resolveKey(jwksUrl, kid);
        if (key == null) {
            log.warn("MCP Bearer 令牌 kid={} 未在 JWKS 中找到", kid);
            return null;
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) {
                return null;
            }
        } catch (Exception e) {
            log.warn("MCP Bearer 令牌验签异常: {}", e.getMessage());
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(decode(parts[1]), Map.class);
            return claims == null ? null : claims;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 kid 取公钥：缓存命中直接返回；未命中回源一次 JWKS。 */
    private PublicKey resolveKey(String jwksUrl, String kid) {
        if (jwksUrl == null || jwksUrl.isBlank()) {
            return null;
        }
        PublicKey cached = keyCache.get(kid);
        if (cached != null && !expired()) {
            return cached;
        }
        refresh(jwksUrl);
        return keyCache.get(kid);
    }

    private boolean expired() {
        return System.currentTimeMillis() - loadedAt > Duration.ofMinutes(CACHE_TTL_MINUTES).toMillis();
    }

    /** 拉取 JWKS 并重建缓存（失败保留旧缓存，避免一次网络抖动打掉全部请求）。 */
    private synchronized void refresh(String jwksUrl) {
        if (!jwksUrl.equals(lastJwksUrl)) {
            keyCache.clear();
            lastJwksUrl = jwksUrl;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("拉取 JWKS 失败: HTTP {}", resp.statusCode());
                return;
            }
            JsonNode jwks = objectMapper.readTree(resp.body());
            JsonNode keys = jwks.path("keys");
            Map<String, PublicKey> loaded = new HashMap<>();
            for (JsonNode k : keys) {
                if (!"RSA".equals(k.path("kty").asText(""))) {
                    continue;
                }
                String kid = k.path("kid").asText("");
                String n = k.path("n").asText("");
                String e = k.path("e").asText("");
                if (kid.isEmpty() || n.isEmpty() || e.isEmpty()) {
                    continue;
                }
                try {
                    BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                    BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                    PublicKey pk = KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                    loaded.put(kid, pk);
                } catch (Exception ex) {
                    log.warn("JWKS 条目解析失败 kid={}: {}", kid, ex.getMessage());
                }
            }
            if (loaded.isEmpty()) {
                log.warn("JWKS 中未解析到任何 RSA 公钥: {}", jwksUrl);
                return;
            }
            keyCache.clear();
            keyCache.putAll(loaded);
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("拉取 JWKS 异常: {}", e.getMessage());
        }
    }

    private static byte[] decode(String segment) {
        return Base64.getUrlDecoder().decode(segment);
    }
}
