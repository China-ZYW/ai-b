package com.aibi.module.dashboard.service;

import com.aibi.module.chat.insight.LlmJson;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.dto.AiLayoutItem;
import com.aibi.module.dashboard.dto.AiLayoutRequest;
import com.aibi.module.dashboard.dto.AiLayoutSlot;
import com.aibi.module.dashboard.dto.AiLayoutVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 布局与样式顾问（大模型自定义布局 + 样式填充）。
 *
 * <p>在本项目的确定性自适应布局引擎（前端 {@code utils/smartLayout.ts}）之上，
 * 引入一次大模型调用：把「组件特征摘要 + 算法基线布局 + 用户风格指令」喂给模型，
 * 由它产出<b>语义分组更合理、主次更分明</b>的网格布局，并顺带填充一整套大屏视觉样式。
 *
 * <p><b>为什么后端不复制那套算法</b>：布局规则若两处实现必然漂移（本项目已多次踩此坑）。
 * 因此本类的职责严格收敛为三件事：① 组装 Prompt；② 解析模型输出；③ 严格校验。
 * 基线由前端随请求传入，校验不通过时本类<b>不产出任何布局</b>（返回空 slots + {@code source=algorithm}），
 * 由前端继续用它自己那一份基线——保证"大模型不可用"时功能依然完整。
 *
 * <p><b>安全与隐私</b>：只把特征摘要（行数/类目数/系列数/标签长度）与标题喂给模型，
 * <b>绝不外发业务明细数据行</b>。
 *
 * <p>定位为"辅助增强、永不抛错"：任何异常都收敛为回退，不向调用方抛。
 */
@Component
public class AiLayoutAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiLayoutAdvisor.class);

    private static final int DEFAULT_COLS = 12;
    private static final int MIN_W = 2;
    private static final int MIN_H = 2;

    /**
     * 行数预算（全屏大屏模式）。大屏网格按视口高度<b>等比分配行高</b>（前端
     * {@code repeat(maxRow, minmax(0,1fr))}），行数越多每行越扁——超过预算会把图表压到不可读。
     * 因此大屏模式把总行数压在 8 行（宽限到 10）、单组件 h≤6，与前端「8 行布局」新样式规范对齐。
     * 这是<b>硬约束</b>：违反即整体回退基线，而非仅仅写进 prompt 请求模型遵守。
     */
    private static final int SCREEN_MAX_ROWS = 10;
    private static final int SCREEN_MAX_H = 6;
    /** 行数预算（分析驾驶舱模式）：可纵向滚动，故放宽。 */
    private static final int PANEL_MAX_ROWS = 16;
    private static final int PANEL_MAX_H = 12;

    /** 允许模型填充的「文本型」样式键（与前端 {@code DashboardStyleConfig} 一一对应）。 */
    private static final Set<String> STYLE_TEXT_KEYS = Set.of(
            "headerBg", "cardBg", "cardBorder", "accent",
            "textPrimary", "textSecondary", "textMuted", "fontFamily");

    /**
     * 合法 CSS 颜色/渐变：hex / rgb(a) / hsl(a) / 三种 gradient。
     * 用宽松白名单拦住"模型把解释文字塞进颜色字段"这类脏值，避免污染前端样式。
     */
    private static final Pattern CSS_COLOR =
            java.util.regex.Pattern.compile("(?is)^(?:#[0-9a-f]{3,8}"
                    + "|(?:rgb|rgba|hsl|hsla)\\([^;{}]{0,120}\\)"
                    + "|(?:linear|radial|conic)-gradient\\([^;{}]{0,400}\\))$");

    private final LlmRouter llm;
    private final ObjectMapper objectMapper;
    private final AibiConfig config;

    public AiLayoutAdvisor(LlmRouter llm, ObjectMapper objectMapper, AibiConfig config) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * 给出 AI 布局与样式建议（永不抛错）；交互路径用（低延迟优先）。
     *
     * @param req 请求（含组件特征与算法基线）
     * @return 建议；不可用时 {@code source=algorithm} 且 slots 为空
     */
    public AiLayoutVO advise(AiLayoutRequest req) {
        return advise(req, false);
    }

    /**
     * 给出 AI 布局与样式建议（永不抛错）。
     *
     * @param req          请求（含组件特征与算法基线）
     * @param longThinking 是否用"长思考"模式：跑在后台线程的 AI 大屏编排传 {@code true}（不受延迟约束、
     *                     换更好的布局）；交互编辑页传 {@code false}，走模型行上的快速配置
     * @return 建议；不可用时 {@code source=algorithm} 且 slots 为空
     */
    public AiLayoutVO advise(AiLayoutRequest req, boolean longThinking) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            return fallback("画布为空，无需设计");
        }
        if (!config.aiLayoutAdvisor()) {
            return fallback("AI 布局顾问已关闭（AI / layout-advisor），沿用确定性算法方案");
        }
        try {
            String raw = longThinking ? llm.chatLongThinking(buildPrompt(req)) : llm.chat(buildPrompt(req));
            return parse(raw, req);
        } catch (RuntimeException e) {
            log.info("AI 布局顾问调用失败，回退确定性算法：{}", e.getMessage());
            return fallback("大模型不可用或响应超时，已沿用确定性算法方案");
        }
    }

    // ==================== Prompt ====================

    private String buildPrompt(AiLayoutRequest req) {
        int cols = colsOf(req);
        boolean screen = isScreen(req);
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据大屏 / BI 驾驶舱的视觉设计专家。请为下面这批图表组件设计 ")
                .append(cols).append(" 列网格布局，并给出配套的视觉样式。\n\n");
        sb.append("【画布】列数=").append(cols).append("；左上角为 (0,0)，x=列、y=行，w/h 为占的列数/行数。")
                .append("模式：").append(screen ? "全屏大屏（强调视觉冲击、整屏铺满、少留白）" : "分析驾驶舱（强调信息密度）").append("。\n");
        String intent = blankToNull(req.intent());
        sb.append("【用户风格要求】").append(intent == null ? "无特殊要求，专业、清晰、易读即可。" : intent).append("\n\n");

        sb.append("【组件清单】（共 ").append(req.items().size()).append(" 个）\n");
        for (AiLayoutItem it : req.items()) {
            sb.append("- i=").append(nz(it.i()))
                    .append(" | 名称=").append(nz(it.name()))
                    .append(" | 图型=").append(nz(it.chartType()))
                    .append(" | 角色提示=").append(nz(it.roleHint()))
                    .append(" | 行数=").append(nvl(it.rowCount()))
                    .append(" | 列数=").append(nvl(it.colCount()))
                    .append(" | 类目数=").append(nvl(it.categories()))
                    .append(" | 系列数=").append(nvl(it.series()))
                    .append(" | 最长标签=").append(nvl(it.maxLabel()))
                    .append(" | 单值=").append(Boolean.TRUE.equals(it.single()) ? "是" : "否")
                    .append('\n');
        }

        List<AiLayoutSlot> base = req.baseline();
        if (base != null && !base.isEmpty()) {
            sb.append("\n【基线布局（确定性算法给出，可作为起点；允许优化，也允许完全重排）】\n");
            for (AiLayoutSlot s : base) {
                sb.append("- ").append(nz(s.i())).append(": x=").append(s.x()).append(",y=").append(s.y())
                        .append(",w=").append(s.w()).append(",h=").append(s.h()).append('\n');
            }
        }

        sb.append("\n【设计原则】\n");
        sb.append("1. 主次分明：单值的核心经营指标置顶排成一行小卡片；趋势图/地图等主视觉给足宽度；明细表沉底。\n");
        sb.append("2. 语义分组：业务相关的图放相邻位置（如「流量 → 转化 → 成交」从上到下）。\n");
        sb.append("3. 在行数预算内尽量填满每一行，避免右侧出现大片空白（绝不能靠增加行数来腾地方）。\n");
        sb.append("4. 尺寸贴合数据：类目多的排行/明细要加高；单值指标卡小而齐；类目名长的不宜过窄。\n");
        sb.append("5. 硬约束：每个组件必须出现且仅出现一次，矩形之间不得重叠，不得超出列数。\n");

        sb.append("\n【行数预算（最重要：违反会直接判定为无效方案）】\n");
        if (screen) {
            sb.append("- 这是全屏大屏：网格高度按视口等比分配，行数越多每行越扁，图表会被压扁到不可读。\n");
            sb.append("- 所有组件的 y+h 必须 ≤8（最多 10）；单个组件 h 不超过 6。\n");
            sb.append("- 建议结构：第 1 行放单值指标卡（每个 w=2、h=2，最多 6 个）；其下两排图表每排 h=3"
                    + "（一排 8 宽主视觉 + 4 宽次视觉，另一排 3 个 4 宽）。\n");
            sb.append("- 放不下时优先合并或放大已有组件，而不是继续往下堆行。\n");
        } else {
            sb.append("- 这是分析驾驶舱（可纵向滚动）：所有组件的 y+h 必须 ≤16，单个组件 h 不超过 12。\n");
        }

        sb.append("\n【style 可用键（只能从这些里选）】\n");
        sb.append("headerBg / cardBg / cardBorder / accent / textPrimary / textSecondary / textMuted / fontFamily")
                .append("（值必须是合法 CSS 颜色或 gradient）；fontScale（0.8~1.4 的数字）；cardRadius（0~24 的数字）；")
                .append("showCardBorder（布尔 false=隐藏卡片外边框，做成无边框科技风；true 或省略=显示边框）。\n");

        sb.append("\n只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块，格式如下：\n");
        sb.append("{\"slots\":[{\"i\":\"<组件key>\",\"x\":0,\"y\":0,\"w\":12,\"h\":4,\"reason\":\"<一句话中文依据>\"}],")
                .append("\"style\":{\"accent\":\"#00C2FF\",\"cardBg\":\"rgba(20,40,90,0.45)\"},")
                .append("\"screenBg\":\"<整屏背景 CSS，如 linear-gradient(...)，可空字符串>\",")
                .append("\"summary\":\"<一句话中文说明你的设计思路>\"}\n");
        return sb.toString();
    }

    // ==================== 解析（严格校验，非法即整体回退） ====================

    private AiLayoutVO parse(String raw, AiLayoutRequest req) {
        String json = LlmJson.extract(raw);
        if (json == null || json.isBlank()) {
            return fallback("大模型未返回可解析的 JSON，已沿用确定性算法方案");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("AI 布局顾问 JSON 解析失败：{}", e.getMessage());
            return fallback("大模型输出格式无法解析，已沿用确定性算法方案");
        }
        if (node == null || !node.isObject()) {
            return fallback("大模型输出不是 JSON 对象，已沿用确定性算法方案");
        }
        List<AiLayoutSlot> slots = readSlots(node.get("slots"), req, colsOf(req), isScreen(req));
        if (slots == null) {
            return fallback("大模型给出的布局越界、重叠或未覆盖全部组件，已沿用确定性算法方案");
        }
        Map<String, Object> style = readStyle(node.get("style"));
        String screenBg = readColor(node.get("screenBg"));
        String summary = truncate(blankToNull(text(node, "summary")), 120);
        String message = "已应用大模型布局方案" + (style.isEmpty() ? "" : "（含 " + style.size() + " 项样式）");
        return new AiLayoutVO(slots, style, screenBg, summary, AiLayoutVO.SRC_LLM, message);
    }

    /**
     * 读取并校验槽位。任一非法（越界/重叠/缺项/多余/重复）即返回 {@code null} 表示整体拒绝。
     *
     * <p>选择"整体拒绝"而非"逐项修补"：半 LLM 半算法的缝合布局既难以预期、也难以向用户解释，
     * 不如干脆回退到一份自洽的基线。
     */
    private List<AiLayoutSlot> readSlots(JsonNode arr, AiLayoutRequest req, int cols, boolean screen) {
        if (arr == null || !arr.isArray()) {
            return null;
        }
        int maxRows = screen ? SCREEN_MAX_ROWS : PANEL_MAX_ROWS;
        int maxH = screen ? SCREEN_MAX_H : PANEL_MAX_H;
        Set<String> expected = new LinkedHashSet<>();
        for (AiLayoutItem it : req.items()) {
            expected.add(String.valueOf(it.i()));
        }
        List<AiLayoutSlot> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                return null;
            }
            String i = blankToNull(text(n, "i"));
            if (i == null || !expected.contains(i) || !seen.add(i)) {
                return null;
            }
            Integer x = intOf(n, "x");
            Integer y = intOf(n, "y");
            Integer w = intOf(n, "w");
            Integer h = intOf(n, "h");
            if (x == null || y == null || w == null || h == null) {
                return null;
            }
            if (x < 0 || y < 0 || y + h > maxRows) {
                return null;
            }
            if (w < MIN_W || w > cols || x + w > cols) {
                return null;
            }
            if (h < MIN_H || h > maxH) {
                return null;
            }
            String reason = truncate(blankToNull(text(n, "reason")), 60);
            out.add(new AiLayoutSlot(i, x, y, w, h, reason));
        }
        if (out.size() != expected.size()) {
            return null;
        }
        // 两两重叠检测（组件数通常 < 30，O(n²) 足够）
        for (int a = 0; a < out.size(); a++) {
            AiLayoutSlot sa = out.get(a);
            for (int b = a + 1; b < out.size(); b++) {
                AiLayoutSlot sb2 = out.get(b);
                boolean overlap = sa.x() < sb2.x() + sb2.w()
                        && sb2.x() < sa.x() + sa.w()
                        && sa.y() < sb2.y() + sb2.h()
                        && sb2.y() < sa.y() + sa.h();
                if (overlap) {
                    return null;
                }
            }
        }
        return out;
    }

    /** 读取样式：仅保留白名单键且值合法者，非法项静默丢弃（样式是"锦上添花"，不必整体回退）。 */
    private Map<String, Object> readStyle(JsonNode node) {
        Map<String, Object> out = new HashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (key == null) {
                continue;
            }
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            // 是否显示卡片外边框（布尔）：false = 无边框科技风
            if ("showCardBorder".equals(key)) {
                if (v.isBoolean()) {
                    out.put(key, v.asBoolean());
                }
                continue;
            }
            if ("fontScale".equals(key)) {
                double fs = v.asDouble(0);
                if (fs >= 0.8 && fs <= 1.4) {
                    out.put(key, Math.round(fs * 100.0) / 100.0);
                }
                continue;
            }
            if ("cardRadius".equals(key)) {
                int cr = v.asInt(-1);
                if (cr >= 0 && cr <= 24) {
                    out.put(key, cr);
                }
                continue;
            }
            if (!STYLE_TEXT_KEYS.contains(key)) {
                continue;
            }
            String s = v.asText("");
            String color = readColor(v);
            if (color != null) {
                out.put(key, color);
            } else if ("fontFamily".equals(key) && s != null && !s.isBlank() && s.length() <= 120) {
                // 字体族不是颜色，只需限长并剔除危险字符
                String safe = s.replaceAll("[<>{};]", "").trim();
                if (!safe.isEmpty()) {
                    out.put(key, safe);
                }
            }
        }
        return out;
    }

    /** 合法 CSS 颜色/渐变则返回去空格后的值，否则 null。 */
    private String readColor(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        if (s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return CSS_COLOR.matcher(t).matches() ? t : null;
    }

    // ==================== 工具 ====================

    private AiLayoutVO fallback(String message) {
        return new AiLayoutVO(List.of(), Map.of(), null, null, AiLayoutVO.SRC_ALGORITHM, message);
    }

    private int colsOf(AiLayoutRequest req) {
        int c = req.cols() == null ? DEFAULT_COLS : req.cols();
        return Math.max(4, Math.min(24, c));
    }

    /** 是否全屏大屏模式：决定行数预算与单组件行高上限（两处共用，避免规则漂移）。 */
    private boolean isScreen(AiLayoutRequest req) {
        return req != null && req.isScreen() != null && req.isScreen() == 1;
    }

    private Integer intOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) {
            return null;
        }
        return v.asInt();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String nz(String s) {
        return (s == null || s.isBlank()) ? "-" : s.trim();
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
