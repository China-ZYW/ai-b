package com.aibi.module.audit.interceptor;

import com.aibi.module.audit.service.OperationLogService;
import com.aibi.module.config.service.AibiConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局操作日志拦截器注册（项目内首个 {@code WebMvcConfigurer}）。
 *
 * <p>拦截 {@code /api/**}（业务 API），排除 actuator 与 error。审计页自身（{@code /audit/}）、
 * SSE 流式端点的二次过滤在 {@link OperationLogInterceptor#shouldSkip} 内完成。
 */
@Configuration
public class OperationLogWebConfig implements WebMvcConfigurer {

    private final OperationLogService service;
    private final AibiConfig aibiConfig;

    public OperationLogWebConfig(OperationLogService service, AibiConfig aibiConfig) {
        this.service = service;
        this.aibiConfig = aibiConfig;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OperationLogInterceptor(service, aibiConfig))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/actuator/**", "/error");
    }
}
