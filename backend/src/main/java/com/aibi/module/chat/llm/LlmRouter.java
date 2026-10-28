package com.aibi.module.chat.llm;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.llm.service.LlmConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 降级路由：按当前租户解析可用大模型链路（DB 动态配置），主力失败时自动切备选。
 *
 * <p>链路来源优先级：① {@code llm_config} 中该租户的专属启用配置；② 全局默认（{@code tenant_id IS NULL}）启用配置；
 * ③ 若 DB 无任何配置，回退到 {@code application.yml} 的 env Bean 链（DeepSeek→Qwen，向后兼容）。
 * 核心引擎（{@code Nl2SqlEngine} / {@code SqlRepairer}）只依赖本路由，不感知供应商与配置来源。
 *
 * <p>降级策略：只尝试可用客户端；某客户端抛任意运行时异常（超时 / HTTP 5xx / 网络）即视为失败，记录告警并切换下一个；
 * 全部失败时抛出最后一个业务异常，交由上层统一错误处理。
 *
 * <p><b>交互求快 / 异步求准</b>：模型行（{@code llm_config}）上的 {@code extra_body} 与 {@code max_tokens}
 * 默认按"交互低延迟"配置（如关闭推理模型的思考模式）。跑到后台线程的异步任务不受延迟约束，
 * 用 {@link #chatLongThinking(String)} 通过 {@link LlmOptions} 把这些参数<b>按次覆盖</b>为"长思考"，
 * 换取更好的结果质量；覆盖参数由 {@code aibi.llm.long-thinking.*} 配置。
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmConfigService llmConfigService;

    /** env 兜底链（application.yml 的 DeepSeek/Qwen Bean），仅在 DB 无任何配置时使用。 */
    private final List<LlmClient> envFallbackChain;

    /** 后台异步任务的"长思考"覆盖参数（extra-body 留空 / max-tokens<=0 表示该项不覆盖）。 */
    private final LlmOptions longThinkingOptions;

    public LlmRouter(LlmConfigService llmConfigService, DeepSeekClient deepSeekClient, QwenClient qwenClient,
                     @Value("${aibi.llm.long-thinking.extra-body:}") String longThinkingExtraBody,
                     @Value("${aibi.llm.long-thinking.max-tokens:0}") int longThinkingMaxTokens) {
        this.llmConfigService = llmConfigService;
        this.envFallbackChain = List.of(deepSeekClient, qwenClient);
        this.longThinkingOptions = new LlmOptions(
                longThinkingExtraBody == null || longThinkingExtraBody.isBlank() ? null : longThinkingExtraBody,
                longThinkingMaxTokens > 0 ? longThinkingMaxTokens : null);
    }

    /**
     * 发送单轮 prompt：按当前租户解析链路并依次尝试，成功即返回；全部失败抛出最后一个异常。
     *
     * @return 助手文本（已去除 markdown 代码围栏）
     * @throws BizException 无任何可用客户端，或所有可用客户端均调用失败
     */
    public String chat(String prompt) {
        return chat(prompt, LlmOptions.NONE);
    }

    /**
     * 长思考模式：供<b>后台异步任务</b>使用——不受交互延迟约束，覆盖模型行上的快速参数
     * （开启思考 + 放宽 max_tokens），换更好的结果质量。覆盖内容见 {@code aibi.llm.long-thinking.*}。
     */
    public String chatLongThinking(String prompt) {
        return chat(prompt, longThinkingOptions);
    }

    /**
     * 带调用选项的单轮 prompt：选项可覆盖模型行上的 {@code extra_body} / {@code max_tokens}。
     *
     * @param options 覆盖项；{@code null} 等价于 {@link LlmOptions#NONE}
     */
    public String chat(String prompt, LlmOptions options) {
        LlmOptions opts = options == null ? LlmOptions.NONE : options;
        List<LlmClient> chain = resolveClients();
        if (chain.isEmpty()) {
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID,
                    "未配置任何可用的大模型，请在平台管理后台配置大模型，或设置 DEEPSEEK_API_KEY 环境变量");
        }

        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmClient client = chain.get(i);
            try {
                String result = client.chat(prompt, opts);
                if (i > 0) {
                    log.info("LLM 降级命中 [{}]（第 {} 顺位）", client.name(), i + 1);
                }
                return result;
            } catch (RuntimeException e) {
                last = e;
                boolean hasNext = i < chain.size() - 1;
                if (hasNext) {
                    log.warn("LLM [{}] 调用失败，降级到下一个可用模型。原因：{}", client.name(), e.getMessage());
                } else {
                    log.error("LLM [{}] 调用失败，且无更多可用模型。原因：{}", client.name(), e.getMessage());
                }
            }
        }

        if (last instanceof BizException be) {
            throw be;
        }
        throw new BizException(ErrorCode.SQL_GENERATE_FAILED,
                "所有 LLM 均调用失败: " + (last == null ? "unknown" : last.getMessage()));
    }

    /**
     * 辅助交叉校验专用：用链路中<b>指定的另一模型</b>发起单轮调用（与 {@link #chat(String)} 的主模型/降级不同，
     * 这里是<b>精准命中</b>一个模型以产生"跨模型视角"）。
     *
     * <p>语义：① {@code model} 为空 → 返回 {@code null}（调用方应跳过交叉校验）；②链路中存在模型名匹配
     * （大小写不敏感包含）的客户端 → 单次尝试并返回其文本；③命中客户端调用失败（超时/异常）→ 回落
     * {@link #chat(String)} 主路由，保证主链路不中断；④链路中<b>根本不存在</b>该模型（未配置）→ 返回
     * {@code null}（调用方跳过，不使用主模型冒充辅助模型）。
     *
     * <p>永不抛出：任何异常都在方法内降级或转为 {@code null}，交由调用方"保留原 SQL"。
     *
     * @param model 目标辅助模型名（须已在 {@code llm_config} 中配置，大小写不敏感包含匹配）
     * @return 辅助模型文本，或 {@code null} 表示"未指定 / 链路中无该模型"（调用方据此跳过）
     */
    public String chatWithModel(String prompt, String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        List<LlmClient> chain = resolveClients();
        String target = model.toLowerCase(Locale.ROOT);
        for (LlmClient client : chain) {
            if (client.model() != null && client.model().toLowerCase(Locale.ROOT).contains(target)) {
                try {
                    String result = client.chat(prompt);
                    log.info("辅助交叉校验命中模型 [{}]（{}）", client.model(), client.name());
                    return result;
                } catch (RuntimeException e) {
                    log.warn("辅助模型 [{}] 调用失败，回落主路由：{}", model, e.getMessage());
                    return chat(prompt);
                }
            }
        }
        log.warn("辅助模型 [{}] 不在当前可用链路（未配置），无法交叉校验", model);
        return null;
    }

    /** 解析当前租户生效的客户端链路（DB 动态配置优先，无则回退 env Bean 链）。 */
    private List<LlmClient> resolveClients() {
        List<LlmClient> chain = new ArrayList<>(llmConfigService.buildChain(TenantContext.tenantId()));
        if (chain.isEmpty()) {
            for (LlmClient client : envFallbackChain) {
                if (client.isAvailable()) {
                    chain.add(client);
                }
            }
        }
        return chain;
    }
}
