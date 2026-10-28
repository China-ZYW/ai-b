package com.aibi.module.chat.service;

import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多意图拆分器（T10-4 一期，{@code @Component} 全程 <b>fail-open</b>）：把一条并列复合问法拆成
 * ≤N 个原子子问，供编排层顺序复用单问管线各自作答。
 *
 * <p><b>两段式、先免费后花钱</b>：
 * <ol>
 *   <li><b>规则预筛（零 LLM）</b>：命中并列连接词或多问号才继续，否则原样返回单元素——绝大多数单意图问题到此为止；</li>
 *   <li><b>轻量 LLM 拆分（门控 {@code AI/multi-intent-split}）</b>：仅疑似多子句且开关开启时发一次小模型调用，
 *       输出原子问句 JSON 数组。</li>
 * </ol>
 *
 * <p><b>保守拆分（宁少拆不乱拆）</b>：总开关关、预筛未命中、拆分开关关、无模型、调用失败、解析失败、
 * 数量越界（&lt;2 或 &gt;maxParts）——<b>一律回落单意图原问</b>，绝不因拆分失败让问数报错。
 * 拆错最多是"把一句拆成两句分别答"，各段答案仍独立正确，不是灾难。
 */
@Component
public class MultiIntentSplitter {

    private static final Logger log = LoggerFactory.getLogger(MultiIntentSplitter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 并列/多子句触发信号（命中其一才疑似多意图；均为明确并列词，保守避免误拆）。 */
    private static final String[] PARALLEL_MARKERS = {
            "以及", "并且", "另外", "还有", "同时", "分别", "此外",
            "，并", ",并", "；", ";"
    };

    private final AibiConfig aibiConfig;
    private final LlmRouter llm;

    public MultiIntentSplitter(AibiConfig aibiConfig, LlmRouter llm) {
        this.aibiConfig = aibiConfig;
        this.llm = llm;
    }

    /**
     * 把复合问法拆成原子子问清单。
     *
     * @param question 原始提问
     * @return 长度 1 表示未拆（原样返回，等价单意图）；≥2 为拆分成功的子问清单
     */
    public List<String> split(String question) {
        // ① 总开关（关=逐字节等价现有单意图）
        if (!aibiConfig.multiIntentEnabled()) {
            return single(question);
        }
        if (question == null || question.isBlank()) {
            return single(question);
        }
        // ② 规则预筛（零成本判掉绝大多数单意图问题）
        if (!looksMultiClause(question)) {
            return single(question);
        }
        // ③ 拆分 LLM 门控（关=仅预筛、不拆；纯规则能拆的很有限）
        if (!aibiConfig.multiIntentSplitLlmEnabled()) {
            return single(question);
        }
        // ④ 轻量 LLM 拆分（复用辅助模型；任何失败一律回落单意图）
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return single(question);
            }
            int maxParts = aibiConfig.multiIntentMaxParts();
            String prompt = buildPrompt(question, maxParts);
            String answer = llm.chatWithModel(prompt, model);
            List<String> parts = parseSubQuestions(answer, maxParts);
            if (parts.size() < 2) {
                // 拆不出 ≥2 个原子问句即视为无需拆/拆分失败，回落单意图
                return single(question);
            }
            log.info("多意图拆分：[{}] → {} 个子问（辅助模型 {}）", question, parts.size(), model);
            return parts;
        } catch (RuntimeException e) {
            log.warn("多意图拆分失败，回落单意图：{}", e.getMessage());
            return single(question);
        }
    }

    /** 原样单元素清单（未拆）。 */
    private List<String> single(String question) {
        return List.of(question == null ? "" : question);
    }

    /**
     * 规则预筛：是否疑似多子句。命中并列连接词、或问号数量 ≥2 才继续；否则一律不拆。
     * 保守：无任何明确并列信号即判单意图。
     */
    boolean looksMultiClause(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        for (String marker : PARALLEL_MARKERS) {
            if (q.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // 多个问号（中英文都计入）：如"销售额多少？环比如何？"
        int marks = 0;
        for (int i = 0; i < question.length(); i++) {
            char ch = question.charAt(i);
            if (ch == '?' || ch == '？') {
                marks++;
            }
        }
        return marks >= 2;
    }

    /** 解析辅助模型名（与 {@code AuxModelReviewer} 同口径）：优先 cross-check-model，留空复用 analysis-model。 */
    private String resolveAuxModel() {
        String m = aibiConfig.crossCheckModel();
        if (m == null || m.isBlank()) {
            m = aibiConfig.aiAnalysisModel();
        }
        return m == null ? null : m;
    }

    /** 拆分 Prompt：要求只输出原子问句 JSON 数组、各句自包含可独立问数，不解释。 */
    private String buildPrompt(String question, int maxParts) {
        return "你是问数意图拆分专家。用户可能在一句话里并列问了多个独立的数据问题。\n"
                + "请把它拆成若干<b>各自完整、可独立问数</b>的原子问句（补齐被省略的主语/时间范围），"
                + "最多 " + maxParts + " 个。\n"
                + "若其实只有一个问题，则原样返回这一句。\n"
                + "用户问题：" + question + "\n"
                + "严格只输出 JSON 字符串数组，例如 [\"子问1\",\"子问2\"]，不要任何解释、不要代码围栏。\n";
    }

    /**
     * 从模型回复里提取原子问句数组：截取首个 '[' 到末个 ']' 解析为 JSON 数组，逐项 trim、去空、剔除与
     * 原问无差异意义不明的项。越界（&gt;maxParts）或非法 JSON 一律返回空清单（调用方据此回落单意图）。绝不抛异常。
     */
    List<String> parseSubQuestions(String answer, int maxParts) {
        List<String> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return out;
        }
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        try {
            JsonNode arr = MAPPER.readTree(answer.substring(start, end + 1));
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode node : arr) {
                String s = node.isTextual() ? node.asText() : node.toString();
                s = s == null ? "" : s.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            // 解析失败：返回空清单触发上层回落单意图（fail-open）
            log.debug("多意图拆分 JSON 解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
        // 越界保守：拆得比上限还多，多半是模型过度拆分 → 整体判失败回落
        if (out.size() > maxParts) {
            return new ArrayList<>();
        }
        return out;
    }
}
