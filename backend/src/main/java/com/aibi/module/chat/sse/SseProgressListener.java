package com.aibi.module.chat.sse;

import com.aibi.module.chat.nl2sql.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把核心引擎的分步进度（{@link ProgressListener}）实时写入 {@link SseEmitter}（M5 / 开发计划 T4.1）。
 *
 * <p>每个阶段以 {@code event: stage} 推送 {@code {stage, message}}；前端据此展示"正在加载表结构 → 生成 SQL →
 * 执行查询 → 推荐图表"的进度条，显著改善长查询的等待体验。
 *
 * <p>客户端断开导致 send 失败时仅记录 debug 并吞掉异常——进度推送是"尽力而为"，不应中断主查询。
 */
public class SseProgressListener implements ProgressListener {

    private static final Logger log = LoggerFactory.getLogger(SseProgressListener.class);

    private final SseEmitter emitter;

    public SseProgressListener(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onStage(String stage, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("message", detail == null ? "" : detail);
        try {
            emitter.send(SseEmitter.event().name("stage").data(payload));
        } catch (Exception e) {
            log.debug("SSE 阶段推送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }
}
