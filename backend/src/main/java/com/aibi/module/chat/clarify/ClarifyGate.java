package com.aibi.module.chat.clarify;

import com.aibi.common.gate.ConfidenceGate;
import com.aibi.module.chat.dto.ClarifyOption;
import com.aibi.module.chat.dto.ClarifyProposal;
import com.aibi.module.chat.entity.TermMap;
import com.aibi.module.chat.skill.QuestionMatcher;
import com.aibi.module.chat.skill.SkillIndexCache;
import com.aibi.module.chat.term.TermDictionary;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 口径歧义反问门（T10-2）：在<b>单个意图</b>提问进入技能路由前，判断它是否在口径层面存在多个合理答案，
 * 命中则产出一份 {@link ClarifyProposal} 供上层反问一轮；否则返回 {@code null}（直接执行）。
 *
 * <p><b>零 LLM、纯结构化、可单测</b>：判定只依赖两处本地数据——术语词典（{@link TermDictionary}）与
 * 指标候选（{@link MetricMapper}，复用 {@link MetricSkill} 同款短 TTL 索引缓存键），不发任何模型调用，
 * 与"低成本反问"的定位一致。置信度打分走可观测信号扣分（基线 1.0，命中信号按固定权重扣），
 * 再由 {@link ConfidenceGate} 依阈值决定 DIRECT/CLARIFY——阈值与归因同源、可配。
 *
 * <p><b>两类歧义信号（一期）</b>：
 * <ul>
 *   <li><b>一词多口径</b>：同一口语词在词典里映射出 <b>≥2 个不同规范名</b>（如"流水"既指"营收"又指"回款"），
 *       扣 {@link #CALIBER_PENALTY}；</li>
 *   <li><b>近似指标名</b>：提问命中本数据源 <b>≥2 个不同指标名</b>（{@link QuestionMatcher} 归一包含/相等），
 *       无法唯一定位，扣 {@link #SIMILAR_METRIC_PENALTY}。</li>
 * </ul>
 *
 * <p><b>fail-open 铁律</b>：任何数据加载异常、候选为空、开关关闭、已澄清过，一律返回 {@code null} 直接执行——
 * 反问是"锦上添花"的消歧，绝不能因自身判定失败而阻断正常问数或与并发/配额纠缠。
 *
 * <p><b>打分后果（须知）</b>：按设计稿的固定单信号扣分，<b>命中单一信号</b>时置信度为 0.65/0.70，
 * 仍 ≥ 直出阈值 0.60 → DIRECT；只有<b>两类信号同时命中</b>（1.0−0.35−0.30=0.35，落入 [0.30,0.60)）才触发 CLARIFY。
 * 即一期反问偏保守（宁少打扰）。若希望单信号即反问，调低 {@code ASK/clarify-direct-threshold} 即可，无需改码。
 */
@Component
public class ClarifyGate {

    private static final Logger log = LoggerFactory.getLogger(ClarifyGate.class);

    /** 置信度基线：无任何歧义信号时视为完全可信，直接执行。 */
    public static final double BASELINE_CONFIDENCE = 1.0;

    /** 一词多口径信号扣分权重（设计稿 §3）。 */
    public static final double CALIBER_PENALTY = 0.35;

    /** 近似指标名信号扣分权重（设计稿 §3）。 */
    public static final double SIMILAR_METRIC_PENALTY = 0.30;

    private final TermDictionary termDictionary;
    private final MetricMapper metricMapper;
    private final SkillIndexCache indexCache;
    private final AibiConfig aibiConfig;

    public ClarifyGate(TermDictionary termDictionary,
                       MetricMapper metricMapper,
                       SkillIndexCache indexCache,
                       AibiConfig aibiConfig) {
        this.termDictionary = termDictionary;
        this.metricMapper = metricMapper;
        this.indexCache = indexCache;
        this.aibiConfig = aibiConfig;
    }

    /**
     * 判定某单意图提问是否需要反问澄清。
     *
     * @param question     经术语归一/追问改写后的有效提问
     * @param tenantId     租户
     * @param datasourceId 数据源
     * @param clarified    本次请求是否已完成单轮澄清（true 直接返回 null，锁死<b>最多 1 轮</b>）
     * @return 需要澄清时返回候选提案；否则 {@code null}（放行执行）
     */
    public ClarifyProposal evaluate(String question, Long tenantId, Long datasourceId, boolean clarified) {
        // 短路一：空提问 / 已澄清（最多 1 轮）/ 总开关关闭 —— 零数据加载，行为与未接入本特性逐字节等价
        if (question == null || question.isBlank() || clarified || !aibiConfig.askClarifyEnabled()) {
            return null;
        }
        try {
            List<ClarifyOption> caliberOptions = detectMultiCaliber(question, tenantId, datasourceId);
            List<ClarifyOption> metricOptions = detectSimilarMetrics(question, tenantId, datasourceId);

            double confidence = BASELINE_CONFIDENCE;
            if (!caliberOptions.isEmpty()) {
                confidence -= CALIBER_PENALTY;
            }
            if (!metricOptions.isEmpty()) {
                confidence -= SIMILAR_METRIC_PENALTY;
            }
            if (caliberOptions.isEmpty() && metricOptions.isEmpty()) {
                return null; // 无任何歧义信号，直接执行
            }

            double min = aibiConfig.askClarifyMinThreshold() / 100.0;
            double direct = aibiConfig.askClarifyDirectThreshold() / 100.0;
            // 到此处开关必开、clarified 必假；问数侧 blockBelowMin=false（低于下限放弃反问而非硬阻断）
            ConfidenceGate.Decision decision = ConfidenceGate.of(
                    confidence, false, true, min, direct, false);
            if (decision != ConfidenceGate.Decision.CLARIFY) {
                return null;
            }

            List<ClarifyOption> merged = mergeOptions(caliberOptions, metricOptions);
            if (merged.isEmpty()) {
                return null;
            }
            int max = aibiConfig.askClarifyMaxOptions();
            if (max > 0 && merged.size() > max) {
                merged = new ArrayList<>(merged.subList(0, max));
            }
            String reason = !caliberOptions.isEmpty()
                    ? ClarifyProposal.REASON_MULTI_CALIBER
                    : ClarifyProposal.REASON_SIMILAR_METRIC;
            return new ClarifyProposal(reason, merged, merged.get(0).code());
        } catch (RuntimeException e) {
            // fail-open：判定链路任何意外都不阻断问数，视作"无歧义"放行
            log.debug("口径澄清判定降级（视为无歧义直接执行）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 一词多口径：把提问里出现的口语词按 {@code term} 分组，若同一词在本数据源词典中映射出 <b>≥2 个不同规范名</b>，
     * 则为每个规范名生成一个候选（refineQuestion 把该词替换为对应规范名，供点选后重发锁定）。
     */
    private List<ClarifyOption> detectMultiCaliber(String question, Long tenantId, Long datasourceId) {
        List<TermMap> terms = termDictionary.activeTerms(tenantId, datasourceId);
        if (terms.isEmpty()) {
            return List.of();
        }
        // term -> 该词在本源映射出的去重规范名集合（保持词典返回顺序）
        Map<String, Set<String>> canonicalsByTerm = new LinkedHashMap<>();
        for (TermMap t : terms) {
            String term = t.getTerm();
            String canonical = t.getCanonical();
            if (term == null || term.length() < 2 || canonical == null || canonical.isBlank()) {
                continue;
            }
            if (!question.contains(term)) {
                continue;
            }
            canonicalsByTerm.computeIfAbsent(term, k -> new LinkedHashSet<>()).add(canonical);
        }
        List<ClarifyOption> options = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : canonicalsByTerm.entrySet()) {
            if (e.getValue().size() < 2) {
                continue; // 唯一规范名不是歧义，交正常归一处理
            }
            String term = e.getKey();
            for (String canonical : e.getValue()) {
                String refined = question.replace(term, canonical);
                options.add(new ClarifyOption("term:" + canonical,
                        term + "（口径：" + canonical + "）", refined));
            }
        }
        return options;
    }

    /**
     * 近似指标名：取本数据源指标候选（复用指标直达同款缓存键），若提问<b>命中 ≥2 个不同指标名</b>
     * （归一后相等或完整包含），则每个指标名生成一个候选（refineQuestion 用该指标已存原始问法或指标名）。
     */
    private List<ClarifyOption> detectSimilarMetrics(String question, Long tenantId, Long datasourceId) {
        if (datasourceId == null) {
            return List.of();
        }
        List<Metric> metrics = indexCache.<Metric>list("metric:" + tenantId + ":" + datasourceId,
                () -> metricMapper.selectList(metricWrapper(datasourceId)));
        // 用指标名去重收集命中项（同名多指标只算一个口径候选）
        Map<String, Metric> hitByName = new LinkedHashMap<>();
        for (Metric m : metrics) {
            String name = m.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            boolean hit = QuestionMatcher.sameQuestion(question, name)
                    || QuestionMatcher.containsPhrase(question, name)
                    || (m.getQuestion() != null && QuestionMatcher.sameQuestion(question, m.getQuestion()));
            if (hit) {
                hitByName.putIfAbsent(name, m);
            }
        }
        if (hitByName.size() < 2) {
            return List.of(); // 唯一命中即正常指标直达，不构成歧义
        }
        List<ClarifyOption> options = new ArrayList<>();
        for (Metric m : hitByName.values()) {
            String refine = (m.getQuestion() != null && !m.getQuestion().isBlank())
                    ? m.getQuestion() : m.getName();
            options.add(new ClarifyOption("metric:" + m.getId(), m.getName(), refine));
        }
        return options;
    }

    /** 合并两类候选：一词多口径优先（更贴近用户原话），近似指标名次之，各自保持内部顺序。 */
    private List<ClarifyOption> mergeOptions(List<ClarifyOption> caliber, List<ClarifyOption> metric) {
        List<ClarifyOption> merged = new ArrayList<>(caliber.size() + metric.size());
        merged.addAll(caliber);
        merged.addAll(metric);
        return merged;
    }

    /**
     * 指标候选查询条件（与 {@code MetricSkill} 同款：仅按数据源限定，租户隔离由拦截器自动追加）。
     * 刻意逐语句追加而非链式一行，规避 {@code LambdaQueryWrapper} 自限定泛型在 return 位置的 javac 推断坑。
     */
    private LambdaQueryWrapper<Metric> metricWrapper(Long datasourceId) {
        LambdaQueryWrapper<Metric> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Metric::getDatasourceId, datasourceId);
        return wrapper;
    }
}
