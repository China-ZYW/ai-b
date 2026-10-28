package com.aibi.module.mcp.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.mcp.dto.McpPatCreated;
import com.aibi.module.mcp.dto.McpPatVO;
import com.aibi.module.mcp.entity.McpPat;
import com.aibi.module.mcp.mapper.McpPatMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * MCP 个人访问令牌（PAT）服务：签发、列表、吊销，以及运行期的哈希校验。
 *
 * <p>安全口径：
 * <ul>
 *   <li>明文由 {@link SecureRandom} 生成 32 字节 → Base64Url，带固定前缀 {@value #PREFIX} 便于客户端识别与密钥扫描；</li>
 *   <li>库里只存 SHA-256 哈希 + 展示前缀，明文不落盘；</li>
 *   <li>吊销 {@code revoked_at} 立即生效（每次调用都重新读行，无缓存）；</li>
 *   <li>令牌绑定到签发者本人：以他的真实角色执行，<b>不做"MCP 专用超级权限"</b>。</li>
 * </ul>
 */
@Service
public class McpPatService {

    private static final Logger log = LoggerFactory.getLogger(McpPatService.class);

    /** 令牌明文前缀：便于客户端/密钥扫描工具识别，也便于用户在后台辨认。 */
    public static final String PREFIX = "aibi_pat_";

    /** 默认有效期天数（不传时）：90 天，兼顾便利与暴露窗口。 */
    private static final int DEFAULT_DAYS = 90;
    /** 有效期上限：365 天，避免签出永不过期的凭据。 */
    private static final int MAX_DAYS = 365;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpPatMapper mapper;

    public McpPatService(McpPatMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 签发一枚新令牌。
     *
     * @param name 令牌名称（用于后台辨认）
     * @param days 有效天数（1~365，空则 90）
     * @return 视图 + <b>仅此一次的明文</b>
     */
    public McpPatCreated create(String name, Integer days) {
        AccessContext ctx = AccessContextHolder.require();
        return createFor(ctx.requireTenantId(), ctx.requireUid(), name, days);
    }

    /**
     * 平台侧代签：为指定组织的指定用户签发令牌（{@code /api/admin/mcp-pats}）。
     *
     * <p>为什么要平台代签：外部集成往往是运营/实施同学替业务方接入，业务方自己没有后台入口；
     * 但令牌仍然<b>绑定到那个用户的真实身份</b>，权限、脱敏、配额一律按他本人执行——
     * 代签只是省去"让对方自己点一下"，不改变安全语义。
     */
    public McpPatCreated createFor(Long tenantId, Long uid, String name, Integer days) {
        if (tenantId == null || tenantId <= 0L) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须指定一个组织（平台域账号不能签发 MCP 令牌）");
        }
        if (uid == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "必须指定令牌所属用户");
        }
        int d = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        String plain = PREFIX + randomToken();
        String hash = sha256(plain);

        McpPat row = new McpPat();
        row.setTenantId(tenantId);
        row.setUid(uid);
        row.setName(blankToDefault(name, "MCP 令牌"));
        row.setTokenPrefix(prefixOf(plain));
        row.setTokenHash(hash);
        row.setScopes("mcp:read");
        row.setExpiresAt(LocalDateTime.now().plusDays(d));
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);

        log.info("签发 MCP 令牌: id={}, tenant={}, uid={}, 有效期{}天", row.getId(), tenantId, uid, d);
        return new McpPatCreated(McpPatVO.of(row), plain);
    }

    /** 当前租户的令牌列表（按 id 倒序）。 */
    public List<McpPatVO> list() {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        return listByTenant(tenantId);
    }

    /** 指定组织的令牌列表（平台侧）。 */
    public List<McpPatVO> listByTenant(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return mapper.byTenant(tenantId).stream().map(McpPatVO::of).toList();
    }

    /** 吊销（幂等：已吊销的直接返回）。 */
    public void revoke(Long id) {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        McpPat row = mapper.selectById(id);
        if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "令牌不存在或不属于当前组织");
        }
        if (row.getRevokedAt() != null) {
            return;
        }
        doRevoke(id, tenantId);
    }

    /** 平台侧吊销（不校验归属：后台已按组织过滤展示，且吊销本身只会收紧权限）。 */
    public void revokeAny(Long id) {
        McpPat row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "令牌不存在");
        }
        if (row.getRevokedAt() != null) {
            return;
        }
        doRevoke(id, row.getTenantId());
    }

    private void doRevoke(Long id, Long tenantId) {
        mapper.update(null, Wrappers.<McpPat>lambdaUpdate()
                .eq(McpPat::getId, id)
                .set(McpPat::getRevokedAt, LocalDateTime.now()));
        log.info("吊销 MCP 令牌: id={}, tenant={}", id, tenantId);
    }

    /**
     * 运行期校验：按明文哈希定位并判定可用性。
     *
     * @return 可用行；不可用（不存在/已吊销/已过期）返回 {@code null}，由调用方决定 401 文案
     */
    public McpPat verify(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return null;
        }
        McpPat row = mapper.byHash(sha256(plainToken.trim()));
        if (row == null) {
            return null;
        }
        if (!row.usable(LocalDateTime.now())) {
            return null;
        }
        return row;
    }

    /**
     * 刷新最近使用时间（失败不影响主流程）。
     *
     * <p>★必须走 {@link #touchUsedRaw}（关闭租户注入的原始 update）：本方法在鉴权阶段调用，
     * {@code TenantContext} 尚未设置，LambdaUpdateWrapper 会被租户拦截器追加
     * {@code AND tenant_id=NULL} → 永远 {@code Updates: 0}（09-24 实测，{@code last_used_at} 冻结）。
     */
    public void touchUsed(Long id) {
        try {
            mapper.touchUsedRaw(id, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("刷新 MCP 令牌使用时间失败（忽略）: {}", e.getMessage());
        }
    }

    // ==================== 工具 ====================

    /** 32 字节随机 → Base64Url（去 padding），约 43 字符。 */
    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String prefixOf(String plain) {
        // 只保留前 12 个字符 + 省略号：足以辨认，不足以还原
        return plain.length() <= 16 ? plain : plain.substring(0, 16) + "...";
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里只可能是环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }
}
