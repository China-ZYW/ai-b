package com.aibi.module.chat.insight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 结构化输出容错工具（问数准确率与图表能力升级 V3 - AI 分析层）。
 *
 * <p>AI 分析环节（图表决策 / 结果解读 / 质量校验）统一要求 LLM “只输出纯 JSON”，但模型仍可能包裹
 * markdown 代码围栏或附带解释性文字。本工具从任意文本中稳健地截取首个 JSON 主体（对象或数组），
 * 供各 Service 交给 Jackson 解析；解析失败由调用方降级到规则链路（辅助功能永不抛错）。
 */
public final class LlmJson {

    private LlmJson() {
    }

    /**
     * 从 LLM 原始文本中截取 JSON 主体：先剥离 markdown 代码围栏，再取靠前的容器起点
     * （左花括号或左方括号）到与之配对的最后一个右括号之间的内容。无 JSON 时返回去围栏后的原文。
     *
     * @param raw LLM 原始输出（可空）
     * @return 截取出的 JSON 文本；{@code raw} 为 null 时返回 null
     */
    public static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        String s = stripFence(raw.trim());
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        if (objStart < 0 && arrStart < 0) {
            return s;
        }
        // 对象优先（多数场景为对象）；否则取数组
        if (objStart >= 0 && (arrStart < 0 || objStart <= arrStart)) {
            int end = s.lastIndexOf('}');
            return end > objStart ? s.substring(objStart, end + 1) : s;
        }
        int end = s.lastIndexOf(']');
        return end > arrStart ? s.substring(arrStart, end + 1) : s;
    }

    /** 剥离 markdown 代码围栏（形如三反引号包裹、可带 json 语言标注）。 */
    private static String stripFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int nl = s.indexOf('\n');
        String body = nl > 0 ? s.substring(nl + 1) : s.substring(3);
        int fence = body.lastIndexOf("```");
        if (fence >= 0) {
            body = body.substring(0, fence);
        }
        return body.trim();
    }

    /** 取对象节点某字段的文本值（节点/字段缺失或为 null 时返回 null）。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** 取某字段为字符串数组（缺失/非数组返回空列表；剔除空白项并 trim）。 */
    public static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        JsonNode arr = node.get(field);
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n == null || n.isNull()) {
                    continue;
                }
                String s = n.asText("");
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
