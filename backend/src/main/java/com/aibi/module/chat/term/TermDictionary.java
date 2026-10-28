package com.aibi.module.chat.term;

import com.aibi.module.chat.entity.TermMap;
import com.aibi.module.chat.mapper.TermMapMapper;
import com.aibi.module.chat.skill.SkillIndexCache;
import com.aibi.module.config.service.AibiConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 业务术语词典（Term Dictionary）：在技能路由之前把提问里的口语词归一为规范名。
 *
 * <p><b>为什么需要它</b>：标准 ChatBI 用户不会严格使用系统里的规范口径词。销售说"流水"、指标叫"营收"，
 * 若只靠大模型理解，每次都得走 NL2SQL（慢、烧配额、且"流水"和"营收"在不同库含义未必相同）。
 * 把"流水→营收"这类映射沉淀为词典后，路由前做一次轻量文本归一，
 * 指标直达 / 样例复用 / 数据字典问答等<b>免 LLM 技能</b>就能直接命中——既快又口径稳定。
 *
 * <p><b>与追问改写（QuestionRewriter）的分工</b>：改写解决"上下文省略"（那华东呢→华东地区月度趋势），
 * 词典解决"同义异词"（流水→营收）。两者都在路由前、按不同维度运作：改写依赖多轮历史（可能耗一次轻量 LLM），
 * 词典是纯本地查表（零成本、零延迟），且按<b>数据源维度</b>维护以隔离不同库的口径。
 *
 * <p><b>替换策略</b>：长词优先替换（避免 "GMV" 先被替换成 "销售额" 后 "GMV_V2" 变成 "销售额_V2" 的半替换）；
 * 单字口语词不替换（防误伤）；词典为空或开关关闭时原样返回，绝不给主链路增加失败模式。
 *
 * <p><b>缓存</b>：复用 {@link SkillIndexCache} 的短 TTL（默认 30s），词典读多写少，
 * 管理员新增映射最迟 30 秒在下次问数生效。
 */
@Component
public class TermDictionary {

    private static final Logger log = LoggerFactory.getLogger(TermDictionary.class);

    /** 编码：随响应 skillInfo 透出（命中归一后路由到某免 LLM 技能时，便于统计"因词典免 LLM"的次数）。 */
    public static final String CODE = "term";

    private final TermMapMapper mapper;
    private final SkillIndexCache cache;
    private final AibiConfig aibiConfig;

    public TermDictionary(TermMapMapper mapper, SkillIndexCache cache, AibiConfig aibiConfig) {
        this.mapper = mapper;
        this.cache = cache;
        this.aibiConfig = aibiConfig;
    }

    /**
     * 把提问里的业务口语词归一为规范名。
     *
     * @param question      原始或经改写后的提问（保留标点，便于下游展示名与 AI 解读用词一致）
     * @param tenantId      租户
     * @param datasourceId  数据源（词典按数据源维度维护，避免跨源串义）
     * @return 归一后的提问；无词典 / 开关关闭 / 未命中时原样返回
     */
    public String normalize(String question, Long tenantId, Long datasourceId) {
        if (question == null || question.isBlank() || !aibiConfig.askTermEnabled()
                || tenantId == null || datasourceId == null) {
            return question;
        }
        List<TermMap> terms = loadTerms(tenantId, datasourceId);
        if (terms.isEmpty()) {
            return question;
        }
        // 长词优先：避免短词先把长规范名的一部分替换掉，造成半替换（显式标注 lambda 参数类型，
        // 规避 javac 对 Comparator.comparingInt(t -> …) 的目标类型推断失败的坑）
        List<TermMap> sorted = new ArrayList<>(terms);
        sorted.sort(Comparator.comparingInt(
                (TermMap t) -> t.getTerm() == null ? 0 : t.getTerm().length()).reversed());

        String q = question;
        boolean changed = false;
        for (TermMap t : sorted) {
            String term = t.getTerm();
            String canonical = t.getCanonical();
            if (term == null || term.length() < 2 || canonical == null || canonical.isBlank()) {
                continue;
            }
            if (q.contains(term)) {
                q = q.replace(term, canonical);
                changed = true;
            }
        }
        if (changed) {
            log.debug("术语归一：\"{}\" -> \"{}\"", question, q);
        }
        return q;
    }

    /** 候选集：该数据源下启用的词典条目（短 TTL 缓存，异常降级为空列表）。 */
    private List<TermMap> loadTerms(Long tenantId, Long datasourceId) {
        try {
            return cache.list("term:" + tenantId + ":" + datasourceId,
                    () -> mapper.selectList(termWrapper(tenantId, datasourceId)));
        } catch (RuntimeException e) {
            log.debug("术语词典加载失败，视为无映射：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 对外暴露候选集（T10-2 口径歧义反问 {@code ClarifyGate} 复用）：返回该数据源下启用的词典条目，
     * 沿用同一短 TTL 缓存与异常降级语义。用于判定"同一 {@code term} 是否映射出多个不同 {@code canonical}"（一词多口径）。
     */
    public List<TermMap> activeTerms(Long tenantId, Long datasourceId) {
        if (tenantId == null || datasourceId == null) {
            return List.of();
        }
        return loadTerms(tenantId, datasourceId);
    }

    /**
     * 候选集查询条件：刻意"先构造对象、再逐条追加条件"而非链式一行——{@code LambdaQueryWrapper}
     * 链式方法返回自限定泛型 {@code Children}，出现在 return/赋值等目标类型位置时 javac 推断会报费解的 "需要 ')'"，
     * 逐语句调用不触发该推断（项目已踩过此坑，见 MEMORY.md）。
     */
    private LambdaQueryWrapper<TermMap> termWrapper(Long tenantId, Long datasourceId) {
        LambdaQueryWrapper<TermMap> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TermMap::getTenantId, tenantId);
        wrapper.eq(TermMap::getDatasourceId, datasourceId);
        wrapper.eq(TermMap::getEnabled, 1);
        return wrapper;
    }
}
