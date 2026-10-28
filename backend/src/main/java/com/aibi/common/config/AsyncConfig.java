package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时执行配置。
 *
 * <p>SSE 请求需"立即返回 emitter、后台线程产出事件"，故用独立线程池承载问数任务，避免占用 Tomcat 请求线程。
 * 池大小与并发护栏（{@code aibi.query.max-concurrent}）匹配：核心 5、峰值 10、有界队列，
 * 队满时 {@link ThreadPoolExecutor.AbortPolicy} 直接拒绝，由控制器转为"服务繁忙"事件，防止无限堆积拖垮 4C4G 单机。
 *
 * <p>{@code @EnableScheduling} 服务于 {@code LoginSessionService} 的过期会话清理（每日 03:30）：
 * 不清理会让 {@code login_session} 随登录次数无限增长。
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** SSE 问数任务线程池。 */
    @Bean("sseExecutor")
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 审计写入线程池（《角色权限体系与组织协同设计》10.2：审计异步写，失败只告警、绝不阻断主流程）。
     *
     * <p>与 {@code sseExecutor} 隔离：审计写入是低频小任务，单线程 + 深队列即可串行落库，
     * 不与问数 SSE 抢占线程。拒绝策略用“告警并丢弃”而非 {@code CallerRunsPolicy}（那会把审计写入
     * 拉回业务线程、阻塞主流程）或 {@code AbortPolicy}（抛异常），确保审计永远不会反压业务。
     */
    @Bean("auditExecutor")
    public ThreadPoolTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("audit-");
        RejectedExecutionHandler warnAndDiscard = (task, pool) ->
                log.warn("审计写入队列已满（待处理 {}），丢弃一条审计任务以避免反压业务", pool.getQueue().size());
        executor.setRejectedExecutionHandler(warnAndDiscard);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 指标 / 大屏 AI 自动化 Skill 后台线程池。
     *
     * <p>两个 Skill（{@code MetricAutoAnalysisService} / {@code CockpitAutoAnalysisService}）改为"提交即返回、
     * 后台线程跑完"的异步模式，避免大库扫描 / LLM 布局增强阻塞 HTTP 请求线程。与 SSE / 审计池隔离，
     * 防止 AI 任务抢占问数或审计线程。拒绝策略用 {@link ThreadPoolExecutor.AbortPolicy}：
     * 队列满时直接拒绝（由控制器转为"服务繁忙，请稍后"），防止无限堆积拖垮 4C4G 单机。
     *
     * <p>异步线程必须手动把当前访问上下文（租户 / 用户 / 团队 / 角色）传播进去——
     * {@code AccessContextHolder} 是线程局部，裸 {@code @Async} 会丢上下文导致跨租户或拿不到创建者
     * （参考 {@code AuditService} 的教训）。两个 Skill 统一用 {@code TenantContext.wrap} 包装任务体。
     */
    /**
     * SSE 心跳调度器：单线程守护线程池。
     *
     * <p>单次问数含多次 LLM 调用（自一致性多路 / 交叉一致性 / AI 解读·校验·选图），实测可达数分钟，
     * 其间可能长时间没有任何 SSE 事件产出。控制器用它按固定周期向连接发 SSE 注释事件（{@code :ping}）
     * <b>保活</b>，避免中间代理按空闲超时断开；单线程足以（发送注释是纯内存小操作）。
     */
    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean("analysisExecutor")
    public ThreadPoolTaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
