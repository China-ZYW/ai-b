package com.aibi.module.chat.service;

import com.aibi.common.security.UserContext;
import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.module.chat.chart.ChartRecommender;
import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.chart.ChartType;
import com.aibi.module.chat.dto.ClarifyProposal;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.ResultMeta;
import com.aibi.module.chat.dto.SkillInfo;
import com.aibi.module.chat.dto.SubAnswer;
import com.aibi.module.chat.dto.Verify;
import com.aibi.module.chat.guard.ConcurrencyGuard;
import com.aibi.module.chat.insight.InsightService;
import com.aibi.module.chat.insight.VerifyService;
import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.chat.nl2sql.ExecStats;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.QueryContext;
import com.aibi.module.chat.nl2sql.QuestionGuard;
import com.aibi.module.chat.nl2sql.QuestionRewriter;
import com.aibi.module.chat.term.TermDictionary;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkillRouter;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.history.dto.HistoryDetailVO;
import com.aibi.module.history.service.QueryHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 问数应用服务（应用层编排）：租户/数据源解析、<b>技能路由</b>、多轮上下文装配、并发护栏、
 * 图表推荐、历史落库。答问本身交给 {@link AskSkillRouter} 选中的技能执行，本类只负责"编排 + 副作用"，
 * 保证所有技能出口一致（脱敏 / 展示名 / 图表 / 解读 / 校验 / 历史都不会被绕过）。
 *
 * <p><b>T8.3 协作类拆分（2026-10-24，纯内部重构、对外方法签名零变化）</b>：
 * 计费扣额 → {@link QuotaCharger}；租户/数据源解析与可见性拦截 → {@link DatasourceResolver}；
 * 历史落库与序列化 → {@link HistoryWriter}；历史回放还原 → {@link HistoryReplayer}；
 * 脱敏与展示名装配 → {@link ResultDecorator}；辅助模型三处交叉复核（V5-A/D/E）→ {@link AuxModelReviewer}。
 *
 * <p><b>隔离</b>：租户来自 {@code TenantContext}、用户来自 {@link UserContext}；跨租户访问数据源在
 * {@code DataSourceService} 内被拦截；历史/多轮上下文均按 tenant_id + uid 隔离。
 *
 * <p><b>多轮对话（M4）</b>：首轮由后端生成 {@code conversationId} 并随响应返回；追问带上同一 id，
 * 服务据此取该会话最近若干轮成功问答注入 Prompt，让 LLM 理解省略式追问。
 *
 * <p><b>Skill 模式（V4）</b>：链路为「路由 → 技能执行 → 统一后置编排」；免 LLM 确定性技能不耗配额、
 * 不参与校验驱动重写（反映已确认口径，重写会让问题与执行口径不再一致）。
 *
 * <p>{@link #ask(Long, String, String, String, ProgressListener)} 带进度监听，供 SSE 流式接口复用同一编排。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AskSkillRouter askSkillRouter;
    private final ChartRecommender chartRecommender;
    private final ConcurrencyGuard concurrencyGuard;
    private final QueryHistoryService historyService;
    private final AibiConfig aibiConfig;
    private final InsightService insightService;
    private final VerifyService verifyService;
    private final QuestionRewriter questionRewriter;
    private final QuestionGuard questionGuard;
    private final TermDictionary termDictionary;
    private final DatasourceResolver datasourceResolver;
    private final QuotaCharger quotaCharger;
    private final HistoryWriter historyWriter;
    private final HistoryReplayer historyReplayer;
    private final ResultDecorator resultDecorator;
    private final AuxModelReviewer auxReviewer;
    private final MultiIntentSplitter multiIntentSplitter;
    private final ReportSplitter reportSplitter;
    private final com.aibi.module.chat.clarify.ClarifyGate clarifyGate;
    private final com.aibi.module.chat.lineage.LineageExtractor lineageExtractor;
    private final com.aibi.module.chat.mark.AnomalyDetector anomalyDetector;

    /** 校验闭环阈值：质量分低于该值视为"未真正回答"，触发一次校验意见驱动的重生成。 */
    private static final int VERIFY_RETRY_SCORE = 60;

    public ChatService(AskSkillRouter askSkillRouter,
                       ChartRecommender chartRecommender,
                       ConcurrencyGuard concurrencyGuard,
                       QueryHistoryService historyService,
                       AibiConfig aibiConfig,
                       InsightService insightService,
                       VerifyService verifyService,
                       QuestionRewriter questionRewriter,
                       QuestionGuard questionGuard,
                       TermDictionary termDictionary,
                       DatasourceResolver datasourceResolver,
                       QuotaCharger quotaCharger,
                       HistoryWriter historyWriter,
                       HistoryReplayer historyReplayer,
                       ResultDecorator resultDecorator,
                       AuxModelReviewer auxReviewer,
                       MultiIntentSplitter multiIntentSplitter,
                       ReportSplitter reportSplitter,
                       com.aibi.module.chat.clarify.ClarifyGate clarifyGate,
                       com.aibi.module.chat.lineage.LineageExtractor lineageExtractor,
                       com.aibi.module.chat.mark.AnomalyDetector anomalyDetector) {
        this.askSkillRouter = askSkillRouter;
        this.chartRecommender = chartRecommender;
        this.concurrencyGuard = concurrencyGuard;
        this.historyService = historyService;
        this.aibiConfig = aibiConfig;
        this.insightService = insightService;
        this.verifyService = verifyService;
        this.questionRewriter = questionRewriter;
        this.questionGuard = questionGuard;
        this.termDictionary = termDictionary;
        this.datasourceResolver = datasourceResolver;
        this.quotaCharger = quotaCharger;
        this.historyWriter = historyWriter;
        this.historyReplayer = historyReplayer;
        this.resultDecorator = resultDecorator;
        this.auxReviewer = auxReviewer;
        this.multiIntentSplitter = multiIntentSplitter;
        this.reportSplitter = reportSplitter;
        this.clarifyGate = clarifyGate;
        this.lineageExtractor = lineageExtractor;
        this.anomalyDetector = anomalyDetector;
    }

    /** 同步问数（无进度推送、技能自动路由）。 */
    @CheckResourceAccess(type = "DATASOURCE", idParam = "datasourceId", permission = "READ")
    public ChatQueryResponse ask(Long datasourceId, String question, String conversationId) {
        return ask(datasourceId, question, conversationId, null, ProgressListener.NOOP);
    }

    /**
     * 问数核心编排（同步 / SSE 共用）。
     *
     * <p>兼容重载：不带澄清标志（嵌入 / 流式 / MCP 等调用方不参与澄清重发，恒为 {@code false}）。
     */
    @CheckResourceAccess(type = "DATASOURCE", idParam = "datasourceId", permission = "READ")
    public ChatQueryResponse ask(Long datasourceId, String question, String conversationId,
                                 String skill, ProgressListener listener) {
        return ask(datasourceId, question, conversationId, skill, false, listener);
    }

    /**
     * 问数核心编排（同步 / SSE 共用）。
     *
     * @param conversationId 会话 id；为空则新建一个并随响应返回
     * @param skill          期望的技能编码（可空=自动路由）
     * @param clarified      本次是否为已完成单轮澄清的重发（T10-2；true 则跳过反问门，锁死最多 1 轮）
     * @param listener       进度监听器（同步传 {@link ProgressListener#NOOP}，SSE 传 emitter 包装）
     */
    @CheckResourceAccess(type = "DATASOURCE", idParam = "datasourceId", permission = "READ")
    public ChatQueryResponse ask(Long datasourceId, String question, String conversationId,
                                 String skill, boolean clarified, ProgressListener listener) {
        // ① 定位租户/用户与数据源 + 可见性 fail-closed 拦截（跨租户访问在 DataSourceService 内被拦截，凭证在此解密）
        DatasourceResolver.Resolved resolved = datasourceResolver.resolve(datasourceId);
        Long tenantId = resolved.tenantId();
        long uid = resolved.uid();
        DataSourceConfig config = resolved.config();

        // ② 会话 id：首轮生成，追问复用；据此取多轮上下文
        String convId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : conversationId;
        List<ConversationTurn> turns = historyService.recentTurns(convId, aibiConfig.conversationTurns());
        // ②' 护栏：域外（寒暄/闲聊）+ 权限域（越权敏感字段）两道前置拦截，零成本友好拒答——
        //     不路由、不耗配额、不打扰大模型
        QuestionGuard.GuardHit hit = guard(question, datasourceId);
        if (hit != null) {
            return guardResponse(convId, question, hit, listener);
        }
        // ②'' 拆题分流（先报告、再多意图，两道均默认关）：命中时拆成 ≤N 子问、串行复用单问管线各自作答
        //      再聚合成多段返回；两道拆题器都自带门控与 fail-open——关闭/未命中/失败一律单元素返回，
        //      则走下面现有单意图路径，行为逐字节等价。STAGE_START 在此统一发出。
        listener.onStage(ProgressListener.STAGE_START, "开始处理您的问题…");
        // 报告拆题：命中报告型问法时由 LLM 依标准报告角度展开成 ≤N 段原子子问，复用 askMulti 串行作答聚合。
        List<String> subQuestions = reportSplitter.expand(question);
        if (subQuestions.size() > 1) {
            return askMulti(datasourceId, config, tenantId, uid, convId, turns, skill, subQuestions, listener);
        }
        // 多意图拆分（T10-4）：识别复合问法里的并列原子意图，拆成 ≤N 子问。
        subQuestions = multiIntentSplitter.split(question);
        if (subQuestions.size() > 1) {
            return askMulti(datasourceId, config, tenantId, uid, convId, turns, skill, subQuestions, listener);
        }

        // ②''' 追问改写：把"那华东呢"补全成独立完整问句，让确定性技能在多轮场景下同样能命中。
        //       原文用于历史留痕与审计，改写句用于答问链路（意图识别、图表推荐、质量校验都以它为准）
        String effective = rewrite(question, turns, listener);
        //       术语归一：把口语词（如"流水"）映射为规范名（如"营收"），让指标/样例/字典问答等
        //       免 LLM 技能在用户用口语提问时也能命中；纯本地查表、零延迟，词典为空时原样返回。
        effective = termDictionary.normalize(effective, tenantId, datasourceId);
        // ②⁴ 口径歧义主动反问（T10-2，默认关）：单意图提问命中"一词多口径 / 近似指标名"歧义信号、置信度
        //     落到中等区间时，返回澄清候选卡（不执行、不扣配额），用户点选后带 clarified=true 重发。
        //     开关关 / 已澄清 / 无歧义时反问门返回 null，流程原样往下——行为与未接入逐字节等价。
        //     （多意图已在 ②'' 提前分流，子问不走澄清。）
        ClarifyProposal proposal = clarifyGate.evaluate(effective, tenantId, datasourceId, clarified);
        if (proposal != null) {
            listener.onStage(ProgressListener.STAGE_START, "检测到可能歧义的口径，请先确认…");
            Long clarifyHistoryId = historyWriter.writeClarify(uid, datasourceId, convId, question, proposal);
            return new ChatQueryResponse(convId, clarifyHistoryId, null, null, null, null, null, null, null, proposal);
        }
        ChartType requested = chartRecommender.detectRequestedChart(effective);
        return runSingleQuestion(datasourceId, config, tenantId, uid, convId, question, effective, turns, skill, requested, listener);
    }

    /**
     * 单意图答问全流程（技能路由 → 路由交叉复核 → 配额 → 并发护栏内执行 → 校验闭环 → 接地复核 →
     * 图表推荐 → 脱敏与展示名装配 → AI 解读 → 历史落库）。被 {@link #ask} 单意图路径调用一次，也被
     * {@link #askMulti} 逐子问<b>顺序</b>调用——二者共用同一段编排，保证多意图各子问出口与单意图完全一致
     * （脱敏/图表/解读/校验/历史/配额一个都不被绕过）。
     *
     * <p><b>并发红线（T10-4 方案 A）</b>：本方法只在叶子执行处进入 {@code concurrencyGuard.run}（一次一把许可、
     * 执行完即释放）。{@link #askMulti} 调用本方法时<b>不</b>在外层再包 run()，故串行跑 N 个子问绝不形成嵌套
     * 抢许可（{@code ConcurrencyGuard} 非可重入，嵌套会死锁雪崩）。
     */
    private ChatQueryResponse runSingleQuestion(Long datasourceId, DataSourceConfig config, Long tenantId, long uid,
                                                String convId, String question, String effective,
                                                List<ConversationTurn> turns, String skill, ChartType requested,
                                                ProgressListener listener) {
        ExecStats stats = new ExecStats();
        // 用户显式要求的图型写入生成 Prompt 的"呈现要求"（如热力图⇒必须 SELECT 经纬度列），
        // 避免"图型指定了、SQL 列却不满足"的脱节
        QueryContext ctx = new QueryContext(effective, convId, turns, listener, stats, null,
                requested == null ? null : requested.displayName());
        AskContext askCtx = new AskContext(datasourceId, config, ctx, tenantId, uid);
        log.debug("租户[{}] 用户[{}] 对数据源[{}] 问数[conv={}, turns={}]: {}",
                tenantId, uid, datasourceId, convId, turns.size(), question);

        // ③ 技能路由：先决定"怎么答"，再决定要不要花配额
        //    免 LLM 的确定性技能（直连 SQL / 指标直达 / 样例复用）不消耗问数配额——
        //    既省成本，也把"把问法沉淀为指标/样例"变成对用户有即时回报的行为
        AskSkillRouter.SkillDecision decision = askSkillRouter.route(askCtx, skill);
        // ③' 路由交叉复核（V5-A，提升问数准确率）：用辅助模型独立判定该走哪条技能，若与路由分歧、
        //    且辅助模型选的是更保守的免 LLM 确定性技能，则回落该技能（避免本可免大模型的确定性问法被路由到大模型）。
        //    永不抛错、无辅助模型 / 调用失败 / 分歧不满足"更保守"条件则保留原路由（主链路零侵入）。
        if (aibiConfig.routeCrossCheckEnabled()) {
            AskSkillRouter.SkillDecision rerouted = auxReviewer.routeCrossCheck(askCtx, decision, listener);
            if (rerouted != null) {
                decision = rerouted;
            }
        }
        listener.onStage(ProgressListener.STAGE_ROUTE,
                "已选定答问方式：" + decision.skill().name()
                        + (decision.skill().llmFree() ? "（免大模型）" : ""));
        quotaCharger.consumeIfNeeded(tenantId, decision.skill().llmFree());
        long start = System.currentTimeMillis();
        try {
            // ④ 并发护栏内执行选中技能的计划（计划已闭包解析结果，执行阶段不再重复查询）
            QueryResult result = concurrencyGuard.run(decision.plan()::run);

            // ④' 质量校验闭环：先校验，低分时把校验意见回喂技能重生成一次，取分数更高者；
            //    保证后续图表/历史落库/解读都针对同一份最终结果，杜绝"校验指出错、
            //    执行层与解读仍照错结果走"的脱节。
            //    免 LLM 的确定性技能不参与重写（用户给定 SQL 与已存指标口径是权威的，AI 无权改写）
            Verify verify = null;
            boolean verifyDidRetry = false;
            if (aibiConfig.aiVerify()) {
                listener.onStage(ProgressListener.STAGE_VERIFY, "正在进行质量校验…");
                verify = verifyService.review(effective, result);
                if (needsVerifyRetry(verify) && decision.skill().rewritable()) {
                    listener.onStage(ProgressListener.STAGE_VERIFY, "校验未达标，正依据校验意见重新生成 SQL…");
                    try {
                        // 重新解析计划：把校验意见注入上下文后交给同一技能。
                        // 确定性技能 rewritable=false，会在新一轮解析里原样重跑（等价于无变化），
                        // 只有 NL2SQL 会据此重新生成 SQL——这正是"谁能被 AI 改写"的边界所在
                        SkillPlan retryPlan = decision.skill().plan(
                                askCtx.withQuery(ctx.withRetryHint(verifyFeedback(result, verify))));
                        if (!retryPlan.matched()) {
                            throw new IllegalStateException(
                                    "技能[" + decision.skill().code() + "] 在携带重试意见后未能产生可执行计划");
                        }
                        QueryResult retried = concurrencyGuard.run(retryPlan::run);
                        Verify reVerify = verifyService.review(effective, retried);
                        if (scoreOf(reVerify) > scoreOf(verify)) {
                            log.info("校验驱动重试生效：质量分 {} -> {}", scoreOf(verify), scoreOf(reVerify));
                            result = retried;
                            verify = reVerify;
                            verifyDidRetry = true;
                        } else {
                            log.info("校验驱动重试未改善（{} -> {}），保留原结果", scoreOf(verify), scoreOf(reVerify));
                        }
                    } catch (RuntimeException e) {
                        log.warn("校验驱动重试失败，保留原结果：{}", e.getMessage());
                    }
                }
            }

            // ④'' 答非所问结果接地复核（V5-D，提升问数准确率）：结果返回后用辅助模型判定"结果是否真回答问题"，
            //    判否则把修正方向回喂、驱动一次重生成（复用 ④' 的校验闭环重试机制）。仅对可被 AI 重写的技能生效；
            //    若质量校验闭环已经重试成功（verifyDidRetry），则信任其结果、跳过本步以控制额外调用成本。
            //    永不抛错、无辅助模型 / 调用失败 / 判为已回答则保留原结果（主链路零侵入）。
            if (aibiConfig.groundednessCheckEnabled() && decision.skill().rewritable() && !verifyDidRetry) {
                AuxModelReviewer.Groundedness g = auxReviewer.groundednessCheck(effective, result, listener);
                if (g != null && !g.answered() && g.direction() != null && !g.direction().isBlank()) {
                    listener.onStage(ProgressListener.STAGE_VERIFY, "接地复核未通过，正依据复核意见重新生成 SQL…");
                    try {
                        SkillPlan retryPlan = decision.skill().plan(
                                askCtx.withQuery(ctx.withRetryHint(g.direction())));
                        if (retryPlan.matched()) {
                            QueryResult retried = concurrencyGuard.run(retryPlan::run);
                            Verify reVerify = aibiConfig.aiVerify() ? verifyService.review(effective, retried) : null;
                            log.info("接地复核驱动重试：依据方向[{}] 重新生成（{} 行）", g.direction(), retried.rowCount());
                            result = retried;
                            verify = reVerify;
                        }
                    } catch (RuntimeException e) {
                        log.warn("接地复核驱动重试失败，保留原结果：{}", e.getMessage());
                    }
                }
            }

            // ⑤ 图表推荐（M3 + 语义层列角色 + V3 AI 图表决策）；listener 透传以便推送 STAGE_CHART_AI 进度
            ChartSpec chart = chartRecommender.recommend(result, effective, datasourceId, listener);
            String chartRequested = requested == null ? null : requested.name();
            listener.onStage(ProgressListener.STAGE_CHART, "已推荐图表：" + chart.type().displayName());

            // ⑥ 展示名装配（脱敏 + 语义层业务别名，无别名兜底列名）：明细表表头、图表图例/系列名与 AI 解读文案
            //    统一讲"业务话"。只作用于返回体：历史快照、图表规格与质量校验用的仍是物理列名，
            //    故日后调整别名不会让已存指标/快照与图表字段失配
            QueryResult display = resultDecorator.decorate(result, datasourceId);
            // ⑦ AI 结果解读（V3-P6，开关控制；失败/关闭一律降级为 null，绝不阻断主链路）；
            //    质量校验已前移至 ④' 并闭环，此处直接复用其结论；喂给 LLM 的是展示名，解读用词与面板一致。
            //    解读刻意排在历史落库之前：使 insight/verify 能与结果、图表一并存库，历史回放才能还原完整结论
            Insight insight = null;
            if (aibiConfig.aiInsight()) {
                listener.onStage(ProgressListener.STAGE_INSIGHT, "正在生成结果解读…");
                insight = insightService.analyze(effective, display);
            }
            // ⑧ 历史落库（成功）：供审计、少样本与多轮上下文自我增强；同存结果快照 + 图表规格 + AI 解读 + 质量校验，
            //    历史会话点击可完整还原"问数当时"的数据/图表/解读/校验；失败不影响返回。
            //    一并记下本次技能编码，供统计各技能命中率与"免 LLM 占比"
            Long historyId = historyWriter.writeOk(uid, datasourceId, convId, question,
                    result, chartRequested, chart, insight, verify, decision.skill().code());
            // 结果完整度元信息（V3）：引擎回填的执行统计 + 结果组装；confidence 由 AI 质量校验分归一化(0~1)
            Double confidence = (verify != null && verify.score() != null)
                    ? verify.score() / 100.0 : null;
            // 溯源元信息（T10-3，默认开）：从实际执行 SQL 确定性抽出来源表并用语义层富化业务名。
            // 开关关→零计算直接 null；开启时 extractor 内部 fail-soft（异常/无表→null），绝不拖垮本次返回。
            com.aibi.module.chat.dto.Lineage lineage = aibiConfig.askLineageEnabled()
                    ? lineageExtractor.extract(datasourceId, result.sql()) : null;
            // 环比波动标记（T10-4b，默认开）：时间序列结果相邻周期波动达阈值时随 meta 下发格高亮与事实结论。
            // 开关关→零计算直接 null；检测器内部 fail-soft（形态不符/无命中/异常→null），绝不拖垮本次返回。
            com.aibi.module.chat.dto.AnomalyMarks marks = aibiConfig.askAnomalyMarksEnabled()
                    ? anomalyDetector.detect(datasourceId, result, aibiConfig.askAnomalyThresholdPct()) : null;
            ResultMeta meta = new ResultMeta(
                    stats.isTruncated(),
                    stats.isTruncated() ? null : result.rowCount(),
                    result.rowCount(),
                    stats.isRepaired(),
                    stats.isEmptyRetried(),
                    stats.getSqlAttempts(),
                    confidence,
                    stats.isCrossChecked(),
                    lineage,
                    marks);
            listener.onStage(ProgressListener.STAGE_DONE, "完成");
            SkillInfo skillInfo = new SkillInfo(decision.skill().code(), decision.skill().name(),
                    decision.skill().llmFree(), decision.plan().reason());
            return new ChatQueryResponse(convId, historyId, display, chart, meta, insight, verify, skillInfo);
        } catch (RuntimeException e) {
            // 失败也留痕（审计 + 便于排障），status=FAILED，error_msg 记失败原因
            historyWriter.writeFailed(uid, datasourceId, convId, question,
                    System.currentTimeMillis() - start, e.getMessage(), decision.skill().code());
            throw e;
        }
    }

    /**
     * 多意图编排（T10-4 方案 A）：拆分器给出的子问<b>在本方法层面（外层无任何并发许可持有）逐个串行</b>复用
     * {@link #runSingleQuestion}——每段仅在其内部叶子执行处短暂获取一把并发许可、执行完即释放，批内不额外
     * 包 run()，从根本上规避嵌套抢许可死锁。整批受 {@code ASK/multi-intent-batch-timeout-ms} 总预算约束，
     * 超预算的剩余子问标注未作答；个别子问失败（含 T10-1 代价熔断）不拖垮整批，同样标注未作答。
     *
     * <p>配额随每个子问在 {@link #runSingleQuestion} 内各自 {@code consumeIfNeeded}（耗 LLM 的子问才扣），
     * 与"用户实际问了几个真问题"对齐。历史按子问逐条落库（各自可审计、可回放）。
     */
    private ChatQueryResponse askMulti(Long datasourceId, DataSourceConfig config, Long tenantId, long uid,
                                       String convId, List<ConversationTurn> turns, String skill,
                                       List<String> subQuestions, ProgressListener listener) {
        long batchStart = System.currentTimeMillis();
        int budget = aibiConfig.multiIntentBatchTimeoutMs();
        List<SubAnswer> parts = new ArrayList<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            String sub = subQuestions.get(i);
            // 批量总超时预算：除首段外，累计耗时超预算则剩余子问标未作答，绝不把用户挂死
            if (i > 0 && System.currentTimeMillis() - batchStart > budget) {
                for (int j = i; j < subQuestions.size(); j++) {
                    parts.add(SubAnswer.unanswered(subQuestions.get(j)));
                }
                listener.onStage(ProgressListener.STAGE_DONE, "本批耗时已超预算，剩余子问未作答，请单独追问");
                break;
            }
            listener.onStage(ProgressListener.STAGE_START,
                    "第 " + (i + 1) + "/" + subQuestions.size() + " 问：" + sub);
            try {
                String effective = termDictionary.normalize(sub, tenantId, datasourceId);
                ChartType requested = chartRecommender.detectRequestedChart(effective);
                ChatQueryResponse resp = runSingleQuestion(datasourceId, config, tenantId, uid, convId,
                        sub, effective, turns, skill, requested, listener);
                parts.add(new SubAnswer(sub, resp.result(), resp.chart(), resp.insight(),
                        resp.verify(), resp.skill(), true));
            } catch (RuntimeException e) {
                // 个别子问失败（含代价熔断）不拖垮整批：该段标注未作答（失败历史已由 runSingleQuestion 落库）
                log.warn("多意图子问[{}] 作答失败，标注未作答：{}", sub, e.getMessage());
                parts.add(SubAnswer.unanswered(sub));
            }
        }
        listener.onStage(ProgressListener.STAGE_DONE, "完成");
        return new ChatQueryResponse(convId, null, null, null, null, null, null, null, parts);
    }

    /**
     * 历史回放详情：快照命中优先原样还原，无快照老记录重跑已存 SQL（不耗 LLM、不计配额）。
     * 实现已抽至 {@link HistoryReplayer}（T8.3），本方法保留原签名供 HistoryController 消费。
     */
    public HistoryDetailVO historyDetail(Long uid, Long id) {
        return historyReplayer.detail(uid, id);
    }

    /**
     * 域外护栏判定。
     *
     * @return null 表示放行；非 null 为面向用户的引导话术
     */
    private QuestionGuard.GuardHit guard(String question, Long datasourceId) {
        if (!aibiConfig.askGuardEnabled()) {
            return null;
        }
        // ②'（a）域外护栏：与数据无关的寒暄/闲聊，零成本友好拒答
        var domain = questionGuard.check(question);
        if (domain.isPresent()) {
            return QuestionGuard.GuardHit.of(QuestionGuard.CODE, "域外护栏",
                    "识别为与数据无关的问题，已免调用大模型", domain.get());
        }
        // ②'（b）权限域护栏：提问点名了当前角色无权查看的敏感字段，前置拦截（免配额、免大模型），
        //     与 FieldPermissionService.mask 的结果层脱敏形成"前置拦截 + 结果兜底"双保险
        if (aibiConfig.askPermissionGuardEnabled()) {
            return questionGuard.checkPermission(question, datasourceId).orElse(null);
        }
        return null;
    }

    /**
     * 多轮追问改写：仅在"存在历史 + 疑似省略式追问"时才消耗一次轻量 LLM 调用，其余情况原样返回。
     *
     * <p>改写失败由 {@link QuestionRewriter} 内部兜底（回落原句），故此处不再额外 catch——
     * 增强型能力不应该给主链路增加失败模式。
     */
    private String rewrite(String question, List<ConversationTurn> turns, ProgressListener listener) {
        if (!aibiConfig.askRewriteEnabled() || !questionRewriter.looksElliptical(turns, question)) {
            return question;
        }
        listener.onStage(ProgressListener.STAGE_UNDERSTAND, "正在结合上下文理解您的追问…");
        String rewritten = questionRewriter.rewrite(turns, question);
        // ②'' 追问改写交叉复核（V5-E，提升问数准确率）：用辅助模型校验改写是否真正消解指代、未臆造字段，
        //    判否则回落原始提问（由通用生成结合多轮上下文处理）。永不抛错、无辅助模型 / 调用失败则保留改写结果。
        if (aibiConfig.rewriteCrossCheckEnabled() && !rewritten.equals(question)) {
            String checked = auxReviewer.rewriteCrossCheck(question, turns, rewritten, listener);
            if (checked != null) {
                rewritten = checked;
            }
        }
        return rewritten;
    }

    /**
     * 构造护栏响应：一份零成本的提示性答复。
     *
     * <p>刻意<b>不落历史、不做图表推荐、不带置信度</b>——它不是一次真正的问数，不应污染历史会话与
     * 少样本素材库，也不应计入配额。结果仍以一份单格明细返回，使前端统一的处理链路不必特殊分支。
     */
    private ChatQueryResponse guardResponse(String convId, String question, QuestionGuard.GuardHit hit,
                                            ProgressListener listener) {
        listener.onStage(ProgressListener.STAGE_GUARD, hit.label());
        listener.onStage(ProgressListener.STAGE_DONE, "完成");
        log.debug("护栏拦截提问[{}] code={}", question, hit.code());
        String tip = hit.tip();
        QueryResult result = QueryResult.of(null, List.of("说明"),
                List.of(List.<Object>of(tip)), 1, 0L);
        Insight insight = new Insight(tip,
                List.of("试试这样问：近半年各月销售额趋势怎么样？", "或：上月各城市订单量排名前十是多少？"), null);
        ResultMeta meta = new ResultMeta(false, 1, 1, false, false, 0, null, false);
        SkillInfo skillInfo = new SkillInfo(hit.code(), hit.label(), true, hit.reason());
        return new ChatQueryResponse(convId, null, result, null, meta, insight, null, skillInfo);
    }

    /** 是否触发校验驱动重试：有分且低于阈值，或无分但明确判定未回答。 */
    private static boolean needsVerifyRetry(Verify verify) {
        if (verify == null) {
            return false;
        }
        if (verify.score() != null) {
            return verify.score() < VERIFY_RETRY_SCORE;
        }
        return Boolean.FALSE.equals(verify.answered());
    }

    private static int scoreOf(Verify verify) {
        return (verify == null || verify.score() == null) ? -1 : verify.score();
    }

    /** 回喂引擎的校验意见：问题点 + 改进建议 + 上一轮 SQL（供避坑）。 */
    private static String verifyFeedback(QueryResult result, Verify verify) {
        StringBuilder sb = new StringBuilder();
        for (String issue : verify.issues() == null ? List.<String>of() : verify.issues()) {
            sb.append("- ").append(issue).append('\n');
        }
        if (verify.suggestion() != null && !verify.suggestion().isBlank()) {
            sb.append("改进建议：").append(verify.suggestion()).append('\n');
        }
        sb.append("上一轮 SQL：").append(result.sql());
        return sb.toString();
    }
}
