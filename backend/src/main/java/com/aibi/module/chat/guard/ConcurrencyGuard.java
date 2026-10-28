package com.aibi.module.chat.guard;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 并发护栏（M9 / 开发计划 T4.5）：用信号量把"同时执行的问数（LLM 调用 + 数据库查询）"限制在
 * {@code maxConcurrent}（默认 5）以内，保护 4 核 4G 单机不被并发压垮。
 *
 * <p>获取许可采用<b>有限等待 + 快速失败</b>：等待超过 {@code acquireTimeoutMs} 仍拿不到许可，
 * 立即抛 {@link ErrorCode#TOO_MANY_REQUESTS}，让前端提示"稍后重试"，而不是无限排队拖垮线程池。
 *
 * <p>公平信号量（FIFO）避免饥饿。生产多实例部署时应平替为分布式限流（Redis + Lua / Sentinel），
 * 单机内存信号量已在架构文档 §3.8 标注为可替换点。
 */
@Component
public class ConcurrencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyGuard.class);

    private final Semaphore semaphore;
    private final int maxConcurrent;
    private final long acquireTimeoutMs;

    public ConcurrencyGuard(@Value("${aibi.query.max-concurrent:5}") int maxConcurrent,
                            @Value("${aibi.query.acquire-timeout-ms:3000}") long acquireTimeoutMs) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
        this.semaphore = new Semaphore(this.maxConcurrent, true);
        log.info("并发护栏初始化：maxConcurrent={}, acquireTimeoutMs={}", this.maxConcurrent, this.acquireTimeoutMs);
    }

    /**
     * 在并发许可保护下执行一个动作。
     *
     * @param action 受保护的昂贵操作（NL2SQL 生成 + 执行）
     * @throws BizException 等待许可超时（{@link ErrorCode#TOO_MANY_REQUESTS}）或线程被中断
     */
    public <T> T run(Supplier<T> action) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "查询等待被中断，请重试");
        }
        if (!acquired) {
            log.warn("并发护栏拒绝：已满 {} 且等待 {}ms 未获许可", maxConcurrent, acquireTimeoutMs);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "当前问数并发已满（上限 " + maxConcurrent + "），请稍后重试");
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }

    /** 当前可用许可数（监控/健康检查用）。 */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /** 并发上限。 */
    public int maxConcurrent() {
        return maxConcurrent;
    }
}
