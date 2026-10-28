package com.aibi.common.security;

import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.common.security.annotation.RequiresPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 接口权限矩阵扫描器（见《角色权限体系与组织协同设计》6.4）。
 *
 * <p>权限体系最容易腐化的地方是"新加了接口忘了挂权限点"。本类在启动期扫描
 * {@link RequestMappingHandlerMapping} 的全部映射，解析每个接口方法上的 {@link RequiresPermission}
 * （方法级优先、回落类级）与 {@link CheckResourceAccess}，生成接口清单，并把
 * <b>fail-closed 域中未挂权限点的接口</b>以 warn 日志高亮——把"漏配"变成可见的运维指标。
 *
 * <p><b>当前只报告、不阻断启动</b>：{@code fail-on-unannotated=false}（默认）。P6 灰度阶段
 * 逐域打开 {@code strict-domains} 后，可把它切成"未标注即拒绝启动"的硬门禁。
 *
 * <p>扫描结果通过 {@link #snapshot()} 暴露，供 P5 平台后台"接口权限矩阵"页消费。
 */
@Component
public class ApiPermissionRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiPermissionRegistry.class);

    /**
     * fail-closed 域默认清单（§6.4）：这些域一旦漏配权限点即可能造成越权，代价不可接受，
     * 故未标注即告警。可经 {@code aibi.rbac.fail-closed-domains} 覆盖。
     *
     * <p><b>2026-09-19 扩容（重要）</b>：原清单只有 11 个"组织治理/调优"域，而
     * {@code /api/theme/**}、{@code /api/metric/**}、{@code /api/dashboard/**}、{@code /api/history/**}、
     * {@code /api/favorite/**}、{@code /api/share/**}、{@code /api/chat/**} 等<b>业务对象域</b>
     * 从未纳入——它们的接口长期只靠"登录态"兜底，{@code ThemeController} 三个写端点裸奔就是产物之一。
     * 根因是"清单本身漏了"，而清单漏了不会产生任何日志，属静默失效。
     *
     * <p>扩容后这些域的接口已逐端点补齐权限点（权限码与前端按钮门禁同名同义），
     * 故新增告警必然是<b>真实的新漏配</b>。少数端点为<b>有意基线放行</b>，会在告警中出现，均已在其
     * 控制器 Javadoc 中说明原因：{@code GET /api/config/effective}（所有角色启动时拉展示配置）、
     * {@code GET /api/theme/list} 与 {@code /api/theme/{code}}（字典中无 theme 读码且全局配色需人人可读）、
     * {@code GET /api/chat/skills}（只描述能力不含数据）、{@code GET /api/dashboard-template} 与 {@code /{id}}
     * （全局只读参考数据）。判断某条告警属"有意"还是"漏配"时，先读该控制器的类注释。
     */
    private static final List<String> DEFAULT_FAIL_CLOSED_DOMAINS = List.of(
            "/api/admin/**", "/api/ops/**", "/api/member/**", "/api/team/**",
            "/api/org/**", "/api/invitation/**", "/api/datasource/**",
            "/api/semantic/**", "/api/fewshot/**", "/api/chart-rule/**", "/api/config/**",
            // 业务对象域（2026-09-19 扩容）：指标/驾驶舱可执行 SQL，主题/分享可对外暴露数据，
            // 历史与收藏为个人信息，问数直接消耗大模型配额——漏配权限点的代价都不可接受
            "/api/metric/**", "/api/dashboard/**", "/api/theme/**", "/api/history/**",
            "/api/favorite/**", "/api/share/**", "/api/chat/**", "/api/manual/**", "/api/quota/**");

    private final RequestMappingHandlerMapping handlerMapping;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${aibi.rbac.fail-closed-domains:}")
    private List<String> configuredFailClosedDomains;

    /** 未标注即拒绝启动（P6 硬门禁开关，默认关闭，仅告警）。 */
    @Value("${aibi.rbac.registry.fail-on-unannotated:false}")
    private boolean failOnUnannotated;

    private final List<ApiEndpoint> endpoints = new ArrayList<>();

    public ApiPermissionRegistry(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /** 单个接口的权限画像。 */
    public record ApiEndpoint(
            String httpMethod,
            String pattern,
            String controller,
            String handler,
            List<String> permissionCodes,
            String resourceType) {

        boolean annotated() {
            return permissionCodes != null && !permissionCodes.isEmpty();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        endpoints.clear();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            List<String> permCodes = resolvePermCodes(handler);
            String resourceType = resolveResourceType(handler);
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new ApiEndpoint(method.name(), pattern,
                            handler.getBeanType().getSimpleName(), handler.getMethod().getName(),
                            permCodes, resourceType));
                }
            }
        }
        report();
    }

    /** 方法级 {@link RequiresPermission} 优先，回落类级；均无则返回空列表。 */
    private List<String> resolvePermCodes(HandlerMethod handler) {
        RequiresPermission rp = handler.getMethodAnnotation(RequiresPermission.class);
        if (rp == null) {
            rp = handler.getBeanType().getAnnotation(RequiresPermission.class);
        }
        return rp == null ? List.of() : Arrays.asList(rp.value());
    }

    /** 方法上的 {@link CheckResourceAccess#type()}，无则 {@code null}。 */
    private String resolveResourceType(HandlerMethod handler) {
        CheckResourceAccess cra = handler.getMethodAnnotation(CheckResourceAccess.class);
        return cra == null ? null : cra.type();
    }

    /** 输出扫描报告：总量、已标注量、fail-closed 域未标注清单。 */
    private void report() {
        List<String> failClosed = effectiveFailClosedDomains();
        List<ApiEndpoint> unannotated = new ArrayList<>();
        int annotated = 0;
        for (ApiEndpoint e : endpoints) {
            if (e.annotated()) {
                annotated++;
            } else if (inFailClosed(e.pattern(), failClosed)) {
                unannotated.add(e);
            }
        }
        log.info("接口权限矩阵扫描完成：共 {} 个接口，已挂权限点 {} 个，fail-closed 域未标注 {} 个",
                endpoints.size(), annotated, unannotated.size());
        if (!unannotated.isEmpty()) {
            for (ApiEndpoint e : unannotated) {
                log.warn("[未标注权限点] {} {} → {}.{}（fail-closed 域，需补 @RequiresPermission）",
                        e.httpMethod(), e.pattern(), e.controller(), e.handler());
            }
            if (failOnUnannotated) {
                throw new IllegalStateException("fail-closed 域存在 " + unannotated.size()
                        + " 个未挂 @RequiresPermission 的接口，严格模式下拒绝启动（详见上方告警）");
            }
        }
    }

    private List<String> effectiveFailClosedDomains() {
        return configuredFailClosedDomains == null || configuredFailClosedDomains.isEmpty()
                ? DEFAULT_FAIL_CLOSED_DOMAINS
                : configuredFailClosedDomains;
    }

    private boolean inFailClosed(String pattern, List<String> domains) {
        for (String domain : domains) {
            if (pathMatcher.match(domain, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 扫描结果快照（供 P5 平台后台"接口权限矩阵"页消费）。 */
    public List<ApiEndpoint> snapshot() {
        return List.copyOf(endpoints);
    }

    /**
     * 生效的 fail-closed 域清单（供后台"接口权限矩阵"页标注每个接口是否落在强约束域）。
     * 暴露此方法是为了让消费方复用同一份判定口径，而非各自硬编码域清单造成漂移。
     */
    public List<String> failClosedDomains() {
        return List.copyOf(effectiveFailClosedDomains());
    }

    /** 某接口路径是否落在 fail-closed 域（未挂权限点即构成越权风险，后台高亮用）。 */
    public boolean isFailClosed(String pattern) {
        return inFailClosed(pattern, effectiveFailClosedDomains());
    }
}
