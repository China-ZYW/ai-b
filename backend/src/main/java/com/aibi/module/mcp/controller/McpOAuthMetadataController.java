package com.aibi.module.mcp.controller;

import com.aibi.module.mcp.config.McpProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.1 资源服务器元数据（RFC 9728）：{@code GET /.well-known/oauth-protected-resource}。
 *
 * <p>面向互联网的 MCP Server 被要求实现资源服务器元数据，让客户端能<b>自动发现</b>该去哪儿取令牌，
 * 而不是靠人工在连接配置里抄地址。返回内容指向 {@code authorization_servers}（RFC 8414 元数据地址），
 * 客户端再据此走 OAuth 2.1 + PKCE 授权流程。
 *
 * <p><b>本服务只做资源服务器，不签发令牌</b>：这是规范对 MCP Server 的定位，
 * 也符合我们的原则——身份体系复用既有（PAT 或 IdP），不自己再造一套登录。
 *
 * <p>一期 PAT 闭环时 {@code authorization_servers} 可能为空（未配 {@code aibi.mcp.authorization-servers}），
 * 此时端点仍返回 {@code resource} 与 {@code scopes_supported}，客户端可据此知道"这里需要令牌"，
 * 只是没有自动发现入口（需由管理员下发 PAT）。
 */
@RestController
public class McpOAuthMetadataController {

    private final McpProperties props;

    public McpOAuthMetadataController(McpProperties props) {
        this.props = props;
    }

    /**
     * RFC 9728 资源服务器元数据。
     *
     * <p>放在 {@code /.well-known/oauth-protected-resource}（规范规定的固定路径）；
     * 该路径已由 {@code SecurityConfig} 放行（MCP 客户端发现阶段没有任何凭据）。
     */
    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResource() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resource", props.getResourceUrl());
        List<String> servers = props.authorizationServers();
        meta.put("authorization_servers", servers);
        // 一期只开放只读能力
        meta.put("scopes_supported", List.of("mcp:read"));
        meta.put("bearer_methods_supported", List.of("header"));
        // RFC 8707：客户端取令牌时必须带 resource 参数，把令牌绑定到本服务
        meta.put("resource_documentation", props.getResourceUrl());
        return meta;
    }

    /**
     * 服务自描述（非规范，便于人工/脚本确认 MCP 端点与鉴权方式）。
     */
    @GetMapping(value = "/.well-known/aibi-mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> selfDescription() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("endpoint", "/mcp");
        meta.put("transport", "Streamable HTTP (stateless core)");
        meta.put("protocolVersion", com.aibi.module.mcp.service.McpServerService.PROTOCOL_VERSION);
        meta.put("enabled", props.isEnabled());
        meta.put("resource", props.getResourceUrl());
        meta.put("tokenMethods", List.of("PAT (aibi_pat_*)", "Bearer JWT (OAuth 2.1)"));
        meta.put("note", "不提供裸 SQL 执行工具；所有调用按调用者真实角色受权限/脱敏/配额约束并写审计。");
        return meta;
    }

    /**
     * OAuth 2.1 授权服务器元数据（RFC 8414）：{@code GET /.well-known/oauth-authorization-server}。
     *
     * <p>面向互联网的 MCP Server 被要求暴露授权服务器元数据，让第三方客户端能<b>自动发现</b>授权端点、
     * 令牌端点与<b>动态客户端注册（DCR，RFC 7591）</b>端点，从而走"授权码 + PKCE"自助接入，
     * 不必由管理员手工下发 PAT。
     *
     * <p><b>本服务只做资源服务器，不签发令牌</b>：这些端点实际由配置的外部 IdP 提供，
     * 此处按规范把"已配置的授权服务器"发布成标准元数据文档（端点在 issuer 基础上派生标准 OAuth 路径，
     * 运维需在 {@code aibi.mcp.authorization-servers} / {@code aibi.mcp.issuer} 中填真实 IdP 根地址）。
     *
     * <p><b>未配置外部授权服务器时返回 404</b>：表明当前为 PAT 模式，客户端应走 PAT 而非 OAuth 发现。
     */
    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> authorizationServer() {
        List<String> servers = props.authorizationServers();
        if (servers.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "not_configured");
            body.put("error_description", "本 MCP 服务未配置外部授权服务器，使用 PAT 鉴权（详见 /.well-known/aibi-mcp）");
            return ResponseEntity.status(404).body(body);
        }
        // issuer 默认取第一个授权服务器；运维也可显式配置 aibi.mcp.issuer
        String issuer = (props.getIssuer() != null && !props.getIssuer().isBlank())
                ? props.getIssuer() : servers.get(0);
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("issuer", issuer);
        meta.put("authorization_endpoint", base + "/oauth/authorize");
        meta.put("token_endpoint", base + "/oauth/token");
        // DCR（RFC 7591）：对外自助接入时客户端可在此注册，无需人工下发 PAT
        meta.put("registration_endpoint", base + "/oauth/register");
        meta.put("scopes_supported", List.of("mcp:read"));
        meta.put("response_types_supported", List.of("code"));
        meta.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        // PKCE 强制（OAuth 2.1 公共客户端必选）
        meta.put("code_challenge_methods_supported", List.of("S256"));
        meta.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic", "private_key_jwt"));
        // 提示客户端取令牌时带 RFC 8707 resource 参数，把令牌绑定到本 MCP 资源
        meta.put("mcp_resource", props.getResourceUrl());
        return ResponseEntity.ok(meta);
    }
}
