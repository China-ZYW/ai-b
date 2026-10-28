package com.aibi.module.chat.nl2sql;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.OrgRole;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 问题护栏（Question Guard）：在技能路由之前拦掉"明显不值得动用大模型"的提问。
 *
 * <p><b>为什么需要它</b>：标准 ChatBI 的能力边界是"回答数据问题"。用户实际输入里却总混着
 * "你好""谢谢""今天天气怎么样"这类内容。没有护栏时，这些输入会一路走进 NL2SQL：要么硬着头皮生成一条
 * 由平均人品决定的 SQL（用户看到一头雾水的空结果），要么浪费一次配额与十几秒等待。护栏把这类请求
 * 在<b>零成本</b>的前提下友好挡回：既省配额，也避免给用户"这个助手答非所问"的第一印象。
 *
 * <p><b>两道分支</b>：
 * <ol>
 *   <li><b>域外护栏（{@link #check}）</b>：纯文本判定，识别寒暄/闲聊/明显域外话题，零依赖、零成本。
 *       用"白名单关键词 + 数据词豁免"而非黑名单——宁可多放过一个真正无关的问题（走大模型），
 *       也绝不误拦一个真实的数据需求（那是不可接受的体验事故）。</li>
 *   <li><b>权限域护栏（{@link #checkPermission}）</b>：在路由前检测提问是否点名了<b>当前角色无权查看的敏感字段</b>
 *       （依据 {@code semantic_meta.sensitive=1} + {@code visible_roles}），命中则前置友好拦截，
 *       既省配额也避免返回一个"全 ***"的脱敏结果。它与 {@code FieldPermissionService.mask} 的结果层脱敏
 *       形成"前置拦截 + 结果兜底"双保险：前者优化体验与成本，后者是安全硬保证（即便前置漏判也不会泄密）。</li>
 * </ol>
 *
 * <p><b>长度闸门</b>：域外判定只对短句（默认 ≤ 16 字）生效——长句几乎不可能是纯闲聊。权限域护栏<b>不受长度限制</b>，
 * 因为"列出所有客户的手机号"这类越权意图往往很长，必须照样拦下。
 *
 * <p><b>安全兜底</b>：语义层查询失败 / 非租户上下文 / 入参缺失时权限预检<b>直接放行</b>（不阻断主链路），
 * 把判定权交还给既有链路（租户隔离 + 结果层脱敏），绝不因护栏异常把正常问数卡死。
 */
@Component
public class QuestionGuard {

    /** 编码：随响应 skillInfo 透出，便于统计"拦下了多少次无效问数 / 越权问数"。 */
    public static final String CODE = "guard";

    /** 权限域护栏编码：与 {@link #CODE} 区分，便于分别统计两类拦截。 */
    public static final String CODE_PERMISSION = "permission-guard";

    private static final Logger log = LoggerFactory.getLogger(QuestionGuard.class);

    /** 仅 OWNER/ADMIN 可见的敏感列（visible_roles 为空时的默认可见角色集）。 */
    private static final Set<String> ADMIN_ROLES = Set.of(OrgRole.OWNER, OrgRole.ADMIN);

    /** 列对象类型（与 {@link SemanticService#TYPE_COLUMN} 对齐）。 */
    private static final String TYPE_COLUMN = "COLUMN";

    /** 寒暄类输入（开启对话 / 礼貌用语）。 */
    private static final String[] GREETINGS = {
            "你好", "您好", "您好呀", "hi", "hello", "嗨", "哈喽", "早上好", "中午好", "下午好", "晚上好",
            "在吗", "在不", "有人吗", "谢谢", "感谢", "thanks", "多谢", "辛苦了", "再见", "拜拜", "好的",
            "你是谁", "你叫什么", "叫什么名字", "介绍一下你自己", "你能干什么", "你能做什么", "会什么"
    };

    /** 明确的域外话题关键词。 */
    private static final String[] OFF_TOPIC = {
            "天气", "讲个笑话", "笑话", "说个段子", "写首诗", "写首歌", "作诗", "编个故事", "讲个故事",
            "推荐电影", "推荐歌曲", "推荐首歌", "推荐什么电影", "帮我翻译", "翻译一下", "写作文",
            "写封邮件", "写一封", "做道题", "解道题", "算命", "星座", "今日运势", "彩票", "怎么做饭",
            "吃什么", "点外卖", "导航", "订机票", "修图", "ps一下"
    };

    /** 数据词豁免：命中则不拒答（保护真实数据需求不被误拦）。 */
    private static final String[] DATA_HINTS = {
            "多少", "几", "统计", "趋势", "走势", "对比", "比较", "排名", "排行", "top", "最高", "最低", "最多",
            "最少", "汇总", "合计", "求和", "平均", "占比", "比例", "数量", "金额", "销售额", "订单", "用户",
            "客户", "库存", "成本", "利润", "增长率", "同比", "环比", "明细", "清单", "列表", "表", "字段",
            "列", "分组", "按", "各", "每", "查询", "查一下", "看一下", "算", "数一数"
    };

    /** 短句闸门：超过该长度的输入不做域外判定（长句几乎不可能是纯闲聊）。 */
    private static final int MAX_GUARD_LENGTH = 16;

    private final SemanticService semanticService;

    public QuestionGuard(SemanticService semanticService) {
        this.semanticService = semanticService;
    }

    /**
     * 域外护栏判定（纯文本，零依赖）。
     *
     * @param question 用户原始提问
     * @return 命中护栏时返回面向用户的引导话术；否则 {@link Optional#empty()} 表示放行
     */
    public Optional<String> check(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String raw = question.trim();
        String q = raw.toLowerCase(Locale.ROOT);
        if (q.length() > MAX_GUARD_LENGTH) {
            return Optional.empty();
        }
        // 数据词优先：任何数据分析意图都无条件放行（护栏的唯一底线是不能误伤真实需求）
        if (containsAny(q, DATA_HINTS)) {
            return Optional.empty();
        }
        if (matches(q, GREETINGS)) {
            return Optional.of("你好，我是数据助理，只回答与数据相关的问题。"
                    + "你可以试着问：近半年各月销售额趋势怎么样？或：上月各城市订单量排名前十是多少？");
        }
        if (containsAny(q, OFF_TOPIC)) {
            return Optional.of("这个问题超出了我能回答的数据范围。"
                    + "我可以帮你查询和分析已接入数据源里的数据，例如：各地区的客户数量分布情况如何？");
        }
        return Optional.empty();
    }

    /**
     * 权限域护栏判定：提问点名了当前角色无权查看的敏感字段时，前置友好拦截。
     *
     * <p>判定依据：数据源下 {@code semantic_meta.object_type=COLUMN && sensitive=1} 的列，其
     * {@code visible_roles}（空=仅 OWNER/ADMIN）若与当前用户的组织角色无交集，则该列对当前用户不可见；
     * 一旦提问文本包含该列的任何可识别词（列名 / 中文别名 / 同义词），即视为越权意图并拦截。
     *
     * <p><b>fail-closed 兜底</b>：语义层查询异常 / 非租户上下文 / datasourceId 为空时<b>直接放行</b>，
     * 不阻断主链路——安全由 {@code FieldPermissionService.mask} 的结果层脱敏最终兜住。
     *
     * @param question     用户原始提问（不依赖改写/归一，避免漏判显式越权问法）
     * @param datasourceId 当前问数所属数据源（跨租户访问已在上层 {@code @CheckResourceAccess} 拦下）
     * @return 命中越权时返回 {@link GuardHit}（含面向用户的提示）；否则 {@link Optional#empty()}
     */
    public Optional<GuardHit> checkPermission(String question, Long datasourceId) {
        if (question == null || question.isBlank() || datasourceId == null) {
            return Optional.empty();
        }
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || !ctx.isTenantScope()) {
            // 无租户上下文：交给既有链路（租户拦截 + 结果层脱敏）处理，护栏不判断
            return Optional.empty();
        }
        // 收集本数据源的敏感列（异常即放行，不阻断）
        List<SemanticMeta> sensitive;
        try {
            sensitive = new ArrayList<>();
            for (SemanticMeta m : semanticService.list(datasourceId)) {
                if (!TYPE_COLUMN.equals(m.getObjectType())
                        || m.getSensitive() == null || m.getSensitive() != 1
                        || m.getColumnName() == null || m.getColumnName().isBlank()) {
                    continue;
                }
                sensitive.add(m);
            }
        } catch (RuntimeException e) {
            log.warn("权限域护栏：语义层查询失败，跳过权限预检（不阻断主链路）：{}", e.getMessage());
            return Optional.empty();
        }
        if (sensitive.isEmpty()) {
            return Optional.empty();
        }
        String q = question.toLowerCase(Locale.ROOT);
        Set<String> forbidden = new LinkedHashSet<>();
        for (SemanticMeta m : sensitive) {
            Set<String> allowedRoles = (m.getVisibleRoles() == null || m.getVisibleRoles().isBlank())
                    ? ADMIN_ROLES
                    : new LinkedHashSet<>(List.of(m.getVisibleRoles().split(",")));
            // 当前角色可见该列 → 跳过（不视为越权）
            if (ctx.orgRoles().stream().anyMatch(allowedRoles::contains)) {
                continue;
            }
            // 该列对当前用户不可见：看提问是否点名它（列名 / 别名 / 同义词，长度≥2 避免单字误命中）
            for (String term : columnTerms(m)) {
                if (term.length() >= 2 && q.contains(term.toLowerCase(Locale.ROOT))) {
                    forbidden.add(displayOf(m));
                    break;
                }
            }
        }
        if (forbidden.isEmpty()) {
            return Optional.empty();
        }
        String tip = "你当前的角色无权查看「" + String.join("、", forbidden)
                + "」等敏感字段，相关问题已前置拦截。如需访问，请联系组织管理员为你授予相应角色权限。";
        return Optional.of(GuardHit.of(CODE_PERMISSION, "权限域护栏",
                "命中敏感字段且无查看权限，已免调用大模型", tip));
    }

    /** 列的可用识别词：列名 / 中文别名 / 同义词（按逗号、中文逗号、分号拆分）。 */
    private List<String> columnTerms(SemanticMeta m) {
        List<String> terms = new ArrayList<>();
        if (m.getColumnName() != null && !m.getColumnName().isBlank()) {
            terms.add(m.getColumnName().trim());
        }
        if (m.getAliasCn() != null && !m.getAliasCn().isBlank()) {
            terms.add(m.getAliasCn().trim());
        }
        if (m.getSynonyms() != null && !m.getSynonyms().isBlank()) {
            for (String s : m.getSynonyms().split("[,，;；]")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    terms.add(t);
                }
            }
        }
        return terms;
    }

    /** 列的展示名：优先中文别名，否则列名。 */
    private String displayOf(SemanticMeta m) {
        return (m.getAliasCn() != null && !m.getAliasCn().isBlank()) ? m.getAliasCn().trim() : m.getColumnName().trim();
    }

    /**
     * 护栏命中结果：携带面向用户的提示，以及统计用的技能编码 / 标签 / 原因。
     * 供 {@code ChatService.guardResponse} 统一构造零成本拦截响应。
     */
    public record GuardHit(String code, String label, String reason, String tip) {
        public static GuardHit of(String code, String label, String reason, String tip) {
            return new GuardHit(code, label, reason, tip);
        }
    }

    /**
     * 精确命中：短输入整体就是该寒暄词（允许结尾标点）。
     * 用"整体相等"而非"包含"，防止"hello world 表有多少行"这种含英文单词的数据问题被拦。
     */
    private boolean matches(String q, String[] words) {
        String compact = q.replaceAll("[\\s\\p{Punct}]+", "");
        for (String w : words) {
            if (compact.equals(w)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String q, String[] words) {
        for (String w : words) {
            if (q.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
