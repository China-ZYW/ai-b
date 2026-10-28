package com.aibi.common.security;

import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.security.annotation.RequiresRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口权限点覆盖护栏：断言<b>每个 HTTP 端点都挂了 L2 门禁</b>（或已在白名单中显式登记为有意放行）。
 *
 * <p><b>为什么需要这个测试</b>：{@code ApiPermissionRegistry} 只在启动期"报告"未标注的接口，
 * 且只对 fail-closed 域告警、默认不阻断启动。本轮审计发现该机制有两处静默失效——
 * ①{@code /api/theme/**}、{@code /api/metric/**}、{@code /api/dashboard/**} 等业务对象域
 * <b>本就不在 fail-closed 清单里</b>，漏配不产生任何日志；
 * ②即便在清单内的域，默认 {@code fail-on-unannotated=false}，告警也只躺在启动日志里。
 * 结果：{@code ThemeController} 三个写端点裸奔、{@code MetricController} 新建与 {@code GET} 全裸奔，
 * 而权限字典里对应的码早已播种并授予角色——前端只是把按钮藏起来了，直接调接口即可越权。
 *
 * <p><b>判定口径</b>与 {@code PermissionAspect} 严格一致：方法级 {@code @RequiresPermission} /
 * {@code @RequiresRole} 优先，其次回落类级同名注解。端点的识别方式与 Spring 一致——
 * 方法上的注解若被 {@code @RequestMapping} 元标注（即 {@code @GetMapping} 等），即视为端点。
 *
 * <p><b>白名单维护约定</b>：新增白名单条目必须在该行注明"为什么可以不放行权限点"，
 * 且优先补充权限字典而不是扩大白名单（本项目的既有教训：权限码是白名单式的，发明新码需要同步
 * {@code RbacSeeder} 与权限矩阵）。
 */
class ApiPermissionCoverageTest {

    /** 扫描根包：控制器全部位于其下。 */
    private static final String BASE_PACKAGE = "com.aibi";

    /**
     * 有意不挂权限点的端点，格式 {@code 控制器简名#方法名}。逐条给出理由，禁止无理由添加。
     */
    private static final Set<String> INTENTIONALLY_OPEN = Set.of(
            // === 匿名域：SecurityConfig 中 permitAll，登录前就要可用，挂权限点必然 401 ===
            "AuthController#register",      // 注册
            "AuthController#login",         // 登录
            "AuthController#logout",        // 注销自身会话
            "AuthController#me",            // 当前登录态（前端准入判断的地基）
            "AuthController#updateProfile", // 改自己的昵称/头像
            "AuthController#permissions",   // 下发当前用户的权限集，是前端门禁的输入
            "IdentityController#myTenants", // 多组织身份：列出本人可切的组织
            "IdentityController#switchTenant", // 多组织身份：切换本人在组织间的上下文

            // === 免认证公开域：/api/public/** 走独立 token（分享码/大屏令牌），与 JWT 无关 ===
            "PublicDashboardController#access",
            "PublicMaterialController#serve",
            "PublicShareController#access",
            "PublicThemeController#list",
            "PublicThemeController#index",
            "PublicThemeController#get",
            "PublicInvitationController#preview", // 邀请公开预览（/api/public/** 匿名，只读无副作用）
            // 对话问数嵌入（/api/public/chat-embed/**）：匿名域，凭嵌入 token + allowedOrigins 校验访问，
            // 与 JWT 无关；令牌即能力，只开放"对该数据源提问"及其会话回看，不含任何管理面能力
            // （history/conversation/followups 与 query 同构：均先 service.resolve(token)+originAllowed 再响应）
            "PublicChatEmbedController#meta",
            "PublicChatEmbedController#suggestions",
            "PublicChatEmbedController#query",
            "PublicChatEmbedController#history",
            "PublicChatEmbedController#conversation",
            "PublicChatEmbedController#followups",

            // === MCP 对外协议端点：鉴权由控制器内部完成，不走本系统 JWT/权限点 ===
            // McpController（/mcp）：每个请求自带 PAT 或外部 IdP 签发的 JWT（McpTokenResolver 解析），
            // 401 按 RFC 6750 带 WWW-Authenticate；本系统登录令牌在此无效，挂权限点既拦不住也不该拦
            "McpController#handle",
            "McpController#handleGet",
            "McpController#handleDelete",
            // McpOAuthMetadataController（/.well-known/**）：RFC 9728/8414 发现端点，
            // 规范要求客户端取得任何凭据之前就能匿名读取，SecurityConfig 已显式放行
            "McpOAuthMetadataController#protectedResource",
            "McpOAuthMetadataController#selfDescription",
            "McpOAuthMetadataController#authorizationServer",

            // === 已文档化的有意放行（各自控制器类注释中已说明原因） ===
            "InvitationController#accept",         // 被邀请者接受邀请：只要求已登录（不能要求 member:invite:* 管理权），
                                                   // 令牌校验/席位消费/角色授予全部由 InvitationService.accept 内部完成
            "AskSkillController#skills",           // 只描述"平台有哪些答问能力"，不含任何数据
            "ConfigController#effective",          // 启动时全角色拉展示配置，按读码门禁会让只读成员整页 403
            "ConfigController#managed",            // 平台管控清单（仅 category:key 名称，无敏感数据）；锁定态展示，
                                                   // 真正拦截在 ConfigService 解析/写入两处，理由同 effective
            "ConfigController#descriptions",       // 配置说明字典（category→key→中文说明），配置页「生效值」表展示用；
                                                   // 说明是展示文案非敏感数据，理由同 effective/managed
            "DashboardTemplateController#list",    // 内置模板为全局只读参考数据
            "DashboardTemplateController#get",
            "ThemeController#list",                // 字典中无 theme 读码，且全局配色需人人可读
            "ThemeController#get");

    @Test
    void everyEndpointCarriesPermissionPoint() throws Exception {
        List<String> offenders = new ArrayList<>();
        int endpointCount = 0;

        for (Class<?> controller : scanControllers()) {
            boolean classLevel = hasGate(controller.getAnnotations());
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) {
                    continue;
                }
                endpointCount++;
                if (classLevel || hasGate(method.getAnnotations())) {
                    continue;
                }
                String key = controller.getSimpleName() + "#" + method.getName();
                if (!INTENTIONALLY_OPEN.contains(key)) {
                    offenders.add(key);
                }
            }
        }

        assertTrue(endpointCount > 100,
                "扫描到的端点数异常（" + endpointCount + "），类路径扫描可能失效——本测试将失去意义");
        assertTrue(offenders.isEmpty(),
                "以下端点未挂 @RequiresPermission / @RequiresRole，且不在有意放行白名单中。"
                        + "若确为漏配，请补权限点（权限码须取自 RbacSeeder 已播种的字典）；"
                        + "若确为有意放行，请在 INTENTIONALLY_OPEN 中登记并写明理由：\n  "
                        + String.join("\n  ", offenders));
    }

    /** 扫描 {@code BASE_PACKAGE} 下所有 {@code @RestController}，返回其 Class（按类名排序，失败信息稳定）。 */
    private List<Class<?>> scanControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<BeanDefinition> candidates = new LinkedHashSet<>(scanner.findCandidateComponents(BASE_PACKAGE));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : candidates) {
            controllers.add(Class.forName(definition.getBeanClassName()));
        }
        controllers.sort((a, b) -> a.getName().compareTo(b.getName()));
        return controllers;
    }

    /**
     * 该方法是否是一个 HTTP 端点：其注解里存在被 {@code @RequestMapping} 元标注的注解。
     *
     * <p>用元标注而非"注解名以 Mapping 结尾"来判断，可让自定义组合注解（{@code @MyGet}）也被覆盖。
     */
    private boolean isEndpoint(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (AnnotatedElementUtils.hasAnnotation(annotation.annotationType(), RequestMapping.class)) {
                return true;
            }
        }
        return false;
    }

    /** 注解集合中是否存在任一 L2 门禁注解（与 PermissionAspect 的拦截范围一致）。 */
    private boolean hasGate(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequiresPermission || annotation instanceof RequiresRole) {
                return true;
            }
        }
        return false;
    }
}
