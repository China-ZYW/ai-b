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
 * 对话内报告拆题器（{@code @Component} 全程 <b>fail-open</b>）：把"本月销售报告"这类<b>报告型</b>提问展开成
 * ≤N 个各自完整、可独立问数的原子子问，供编排层复用 {@code ChatService.askMulti} 串行作答、聚合成一份多段报告。
 *
 * <p><b>与 {@link MultiIntentSplitter} 同源不同用</b>：多意图拆的是"用户在一句话里并列写了几个问题"，
 * 本类拆的是"用户要一份报告、但没逐条列出报告该包含哪些分析"——靠 LLM 依标准报告角度
 * （①总体数值 ②环比/同比 ③按主要维度 TopN ④时间趋势）<b>主动展开</b>。二者共用同一套多段编排机器。
 *
 * <p><b>两段式、先免费后花钱</b>（逐字对齐 {@link MultiIntentSplitter#split}）：
 * <ol>
 *   <li><b>关键词预筛（零 LLM）</b>：命中「报告/报表/月报/…/分析一下」等报告信号才继续，否则原样单元素；</li>
 *   <li><b>轻量 LLM 拆题（门控 {@code AI/report-split}）</b>：仅命中且开关开启时发一次小模型调用，输出原子子问 JSON 数组。</li>
 * </ol>
 *
 * <p><b>保守展开（宁少拆不乱拆）</b>：总开关关、预筛未命中、拆题开关关、无模型、调用失败、解析失败、
 * 数量越界（&lt;2 或 &gt;maxParts）——<b>一律回落单元素（原问逐字节等价走单意图）</b>，绝不因拆题失败让问数报错。
 */
@Component
public class ReportSplitter {

    private static final Logger log = LoggerFactory.getLogger(ReportSplitter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 报告型触发信号（命中其一才疑似报告；均为明确的报告/汇总/分析词，保守避免误拆普通单问）。 */
    private static final String[] REPORT_MARKERS = {
            "报告", "报表", "月报", "周报", "日报", "年报", "汇总", "总结",
            "分析一下", "分析下", "整体情况", "全貌", "报告模板"
    };

    private final AibiConfig aibiConfig;
    private final LlmRouter llm;

    public ReportSplitter(AibiConfig aibiConfig, LlmRouter llm) {
        this.aibiConfig = aibiConfig;
        this.llm = llm;
    }

    /**
     * 把报告型需求展开成原子子问清单。
     *
     * @param question 原始提问
     * @return 长度 1 表示未展开（原样返回，等价未命中/单意图）；≥2 为展开成功的子问清单
     */
    public List<String> expand(String question) {
        // ① 总开关（关=逐字节等价现有单意图/多意图）
        if (!aibiConfig.reportEnabled()) {
            return single(question);
        }
        if (question == null || question.isBlank()) {
            return single(question);
        }
        // ② 关键词预筛（零成本判掉绝大多数非报告问题）
        if (!looksLikeReport(question)) {
            return single(question);
        }
        // ③ 拆题 LLM 门控（关=仅预筛、不拆；纯关键词无法展开出报告段落）
        if (!aibiConfig.reportSplitLlmEnabled()) {
            return single(question);
        }
        // ④ 轻量 LLM 拆题（复用辅助模型；任何失败一律回落单元素）
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return single(question);
            }
            int maxParts = aibiConfig.reportMaxParts();
            String prompt = buildPrompt(question, maxParts);
            String answer = llm.chatWithModel(prompt, model);
            List<String> parts = parseSubQuestions(answer, maxParts);
            if (parts.size() < 2) {
                // 展不出 ≥2 个原子子问即视为无需拆/拆题失败，回落单元素
                return single(question);
            }
            log.info("报告拆题：[{}] → {} 个段落子问（辅助模型 {}）", question, parts.size(), model);
            return parts;
        } catch (RuntimeException e) {
            log.warn("报告拆题失败，回落原问单意图：{}", e.getMessage());
            return single(question);
        }
    }

    /** 原样单元素清单（未展开）。 */
    private List<String> single(String question) {
        return List.of(question == null ? "" : question);
    }

    /**
     * 关键词预筛：是否疑似报告型提问。命中「报告/报表/月报/…/分析一下」等其一才继续；否则一律不拆。
     * 保守：宁可不拆也不误判普通单问为报告。
     */
    boolean looksLikeReport(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        for (String marker : REPORT_MARKERS) {
            if (q.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 解析辅助模型名（与 {@link MultiIntentSplitter} 同口径）：优先 cross-check-model，留空复用 analysis-model。 */
    private String resolveAuxModel() {
        String m = aibiConfig.crossCheckModel();
        if (m == null || m.isBlank()) {
            m = aibiConfig.aiAnalysisModel();
        }
        return m == null ? null : m;
    }

    /** 拆题 Prompt：要求把报告需求展开成各带主题与时间范围、可独立问数的原子子问，只输出 JSON 数组、不解释。 */
    private String buildPrompt(String question, int maxParts) {
        return "你是数据分析报告策划专家。用户会提出一个报告型需求（如\"本月销售报告\"），"
                + "但通常不会逐条列出这份报告应包含哪些分析。\n"
                + "请把它展开成若干<b>各自完整、可独立问数、自带主题与时间范围</b>的原子子问，最多 " + maxParts + " 个，"
                + "覆盖标准报告角度：①总体数值（如总额/总量）②环比或同比 ③按主要维度的 TopN 排名 ④时间趋势。"
                + "每个子问都要补全主题与时间范围，能脱离上下文独立执行；若其实只适合一个问题分析则原样返回这一句。\n"
                + "用户报告需求：" + question + "\n"
                + "严格只输出 JSON 字符串数组，例如 [\"本月销售总额是多少\",\"本月销售额环比上月变化如何\"]，"
                + "不要任何解释、不要代码围栏。\n";
    }

    /**
     * 从模型回复里提取原子子问数组：截取首个 '[' 到末个 ']' 解析为 JSON 数组，逐项 trim、去空。
     * 越界（&gt;maxParts）或非法 JSON 一律返回空清单（调用方据此回落单元素）。绝不抛异常。
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
            // 解析失败：返回空清单触发上层回落单元素（fail-open）
            log.debug("报告拆题 JSON 解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
        // 越界保守：展开得比上限还多，多半是模型过度拆分 → 整体判失败回落
        if (out.size() > maxParts) {
            return new ArrayList<>();
        }
        return out;
    }
}
