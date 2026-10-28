package com.aibi.module.audit.interceptor;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * 请求体缓存过滤器：在 DispatcherServlet 之前把 JSON 请求包成 {@link ContentCachingRequestWrapper}，
 * 使 {@link OperationLogInterceptor#afterCompletion} 能重复读取已被控制器消费过的请求体（用于审计留痕）。
 *
 * <p>仅对 {@code application/json} 请求包装：表单类走参数、无体可读，不必缓存。
 * 包装在 {@link Ordered#HIGHEST_PRECEDENCE} 执行，确保早于所有业务 Filter 与拦截器。
 */
@Component
public class RequestCachingFilter implements Filter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (http.getContentType() != null && http.getContentType().contains("application/json")
                && !(http instanceof ContentCachingRequestWrapper)) {
            chain.doFilter(new ContentCachingRequestWrapper(http), response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
