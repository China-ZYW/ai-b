package com.aibi.module.chat.nl2sql;

/**
 * 问数进度监听器（M4 引入，M5 SSE 落地）：核心引擎在各关键阶段回调，
 * 让上层能把"分步进度"实时推给前端（SSE），显著改善长查询的等待体验。
 *
 * <p>同步一次性接口用 {@link #NOOP} 空实现即可；SSE 场景实现为把阶段事件写入 {@code SseEmitter}。
 * 阶段名用本接口内的常量，保证前后端约定一致。
 */
public interface ProgressListener {

    /** 空实现：不关心进度的调用方（如同步 REST）直接用它。 */
    ProgressListener NOOP = new ProgressListener() {
    };

    // ===== 阶段常量（前后端事件名约定）=====
    String STAGE_START = "start";       // 开始处理
    String STAGE_ROUTE = "route";       // 技能路由：已选定答问方式（V4 Skill 模式）
    String STAGE_UNDERSTAND = "understand"; // 多轮追问理解与补全（V4.1 追问改写）
    String STAGE_GUARD = "guard";           // 域外护栏：识别为与数据无关的问题（V4.1）
    String STAGE_SCHEMA = "schema";     // Schema 加载与相关表裁剪完成
    String STAGE_INTENT = "intent";     // 意图识别完成
    String STAGE_GENERATE = "generate"; // 正在调用 LLM 生成 SQL
    String STAGE_CONSISTENCY = "consistency"; // self-consistency 多路候选生成与择优（V3）
    String STAGE_SQL = "sql";           // SQL 生成并通过安全校验
    String STAGE_EXECUTE = "execute";   // 正在执行查询
    String STAGE_REPAIR = "repair";     // 执行报错，正在自动修正
    String STAGE_CHART_AI = "chart-ai"; // AI 图表决策（V3）
    String STAGE_CHART = "chart";       // 图表推荐完成
    String STAGE_INSIGHT = "insight";   // AI 结果解读（V3）
    String STAGE_VERIFY = "verify";     // AI 质量校验（V3）
    String STAGE_DONE = "done";         // 全部完成

    /**
     * 阶段回调。
     *
     * @param stage  阶段名（建议用本接口常量）
     * @param detail 面向用户的简短进度描述（可空）
     */
    default void onStage(String stage, String detail) {
    }
}
