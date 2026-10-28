package com.aibi.module.chat.llm;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * OpenAI 兼容 Chat Completions 协议的通用客户端基类。
 *
 * <p>DeepSeek 与通义千问（DashScope 兼容模式 {@code /compatible-mode/v1}）都遵循 OpenAI 的
 * {@code POST {baseUrl}/chat/completions} 报文格式，故在此统一 HTTP 调用、请求体组装、响应解析与
 * markdown 代码围栏剥离；各供应商子类只需提供 base-url / api-key / model 与 {@link #name()}。
 *
 * <p>仅依赖 JDK 自带 {@link HttpClient} 与已有的 Jackson，不引入新的第三方 SDK。
 */
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiCompatibleClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    /** 额外请求体 JSON（逐字段并入请求体顶层），如 {"thinking":{"type":"disabled"}}；空=不加。 */
    private final String extraBodyJson;
    /** 输出 token 上限；空=用供应商默认。 */
    private final Integer maxTokens;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected AbstractOpenAiCompatibleClient(String baseUrl, String apiKey, String model, long timeoutSeconds) {
        this(baseUrl, apiKey, model, timeoutSeconds, null, null);
    }

    protected AbstractOpenAiCompatibleClient(String baseUrl, String apiKey, String model, long timeoutSeconds,
                                             String extraBodyJson, Integer maxTokens) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.extraBodyJson = extraBodyJson;
        this.maxTokens = maxTokens;
    }

    /**
     * 把一段 extra_body JSON 对象<b>逐字段并入请求体顶层</b>（后并入者覆盖先并入者）。
     *
     * <p>两个来源：① 模型行 {@code llm_config.extra_body}（如关闭推理模型的思考模式
     * {@code {"thinking":{"type":"disabled"}}}）；② 调用级 {@link LlmOptions} 覆盖（异步任务的长思考）。
     * 顺序上 ② 压过 ①，故"交互求快、异步求准"可以并存。
     *
     * <p>JSON 缺失 / 非法 / 非对象时一律忽略（仅告警），绝不影响主流程。
     */
    private void mergeExtraBody(ObjectNode body, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                log.warn("[{}] extra_body 不是 JSON 对象，已忽略：{}", name(), json);
                return;
            }
            node.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        } catch (Exception e) {
            log.warn("[{}] extra_body 解析失败，已忽略：{}", name(), e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String chat(String prompt) {
        return chat(prompt, LlmOptions.NONE);
    }

    @Override
    public String chat(String prompt, LlmOptions options) {
        LlmOptions opts = options == null ? LlmOptions.NONE : options;
        // 仅在"确有调用级覆盖"时留痕：便于线上排查"这次异步调用到底用没用长思考"
        if (opts.extraBodyOverrideJson() != null || opts.maxTokensOverride() != null) {
            log.debug("[{}] 应用调用级参数覆盖：extraBody={} maxTokens={}",
                    name(), opts.extraBodyOverrideJson(), opts.maxTokensOverride());
        }
        if (!isAvailable()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, name() + " 未配置 API Key");
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("stream", false);
            Integer cap = opts.maxTokensOverride() != null ? opts.maxTokensOverride() : maxTokens;
            if (cap != null && cap > 0) {
                body.put("max_tokens", cap);
            }
            // 额外字段在 messages 之前并入；"调用级覆盖"压在"模型行配置"之上（异步长思考即由此覆盖）
            mergeExtraBody(body, extraBodyJson);
            mergeExtraBody(body, opts.extraBodyOverrideJson());
            body.putArray("messages").addObject().put("role", "user").put("content", prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("[{}] LLM 返回 HTTP {}: {}", name(), response.statusCode(), response.body());
                throw new BizException(ErrorCode.SQL_GENERATE_FAILED, name() + " 服务异常(" + response.statusCode() + ")");
            }
            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return stripCodeFence(content).trim();
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.QUERY_TIMEOUT, name() + " 响应超时，请重试");
        } catch (Exception e) {
            log.error("[{}] 调用 LLM 失败", name(), e);
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "调用 " + name() + " 失败: " + e.getMessage());
        }
    }

    /** 去掉 ```sql ... ``` 围栏，只保留纯 SQL 文本。 */
    protected static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
        }
        return t;
    }
}
