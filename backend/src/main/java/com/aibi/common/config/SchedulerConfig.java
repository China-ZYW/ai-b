package com.aibi.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度配置：开启 Spring 定时任务能力（{@code @Scheduled} 才能生效）。
 *
 * <p>集中一处开启，便于将来按需关掉整体调度（如只读副本场景）。业务调度器本身用
 * {@code @Scheduled} 注解，是否真正工作还受各调度器内部的 {@code enabled} 开关控制。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
