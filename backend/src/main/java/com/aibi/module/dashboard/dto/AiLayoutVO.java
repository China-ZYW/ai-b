package com.aibi.module.dashboard.dto;

import java.util.List;
import java.util.Map;

/**
 * AI 布局与样式顾问的返回结果。
 *
 * <p><b>契约</b>：本接口<b>永不抛错</b>。任何异常（未配置大模型、调用失败、JSON 解析失败、
 * 布局越界/重叠、样式键不合法）都被收敛为 {@code source="algorithm"}，由前端继续使用它自己
 * 已经算好的确定性基线布局。这样即便大模型不可用，"AI 智能设计"按钮也始终可用（只是退化为算法方案）。
 *
 * @param slots   采纳的布局槽位（{@code source="algorithm"} 时为空列表，前端应忽略并沿用基线）
 * @param style   采纳的大屏级样式覆盖（键取自白名单，值为合法 CSS；可为空 map）
 * @param screenBg 建议的整屏背景（CSS background 值，可空）
 * @param summary 一句话中文摘要（说明本次设计的思路）
 * @param source  {@code llm}=大模型方案；{@code algorithm}=已回退确定性算法
 * @param message 给用户的提示（回退原因 / 成功说明）
 */
public record AiLayoutVO(
        List<AiLayoutSlot> slots,
        Map<String, Object> style,
        String screenBg,
        String summary,
        String source,
        String message) {

    /** 大模型方案来源标记。 */
    public static final String SRC_LLM = "llm";
    /** 回退到确定性算法的来源标记。 */
    public static final String SRC_ALGORITHM = "algorithm";
}
