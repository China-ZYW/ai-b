package com.aibi.module.chat.nl2sql;

import com.aibi.module.fewshot.entity.FewShotExample;
import com.aibi.module.fewshot.mapper.FewShotExampleMapper;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 少样本库（§3.6 + 配置化升级 V2）：三路合并供少样本注入，按与当前问题的词重合度排序：
 * <ol>
 *   <li><b>curated</b>：人工维护的 {@code few_shot_example}（enabled），权重最高，命中后 use_count+1；</li>
 *   <li><b>promoted</b>：历史中被用户点赞（{@code query_history.feedback=1} 且 status=OK）的问答对；</li>
 *   <li><b>history</b>：其余近期执行成功（status=OK）的历史问答对。</li>
 * </ol>
 *
 * <p>相似度：用"英文词 + 中文 2-gram"的重合度近似（curated/promoted 叠加来源加权），零依赖；
 * 表为空或查询异常时安全返回空列表，绝不影响主链路。读取由租户拦截器自动隔离。
 */
@Component
public class FewShotStore {

    private static final Logger log = LoggerFactory.getLogger(FewShotStore.class);

    /** 参与相似度排序的候选条数上限（最近的成功问答）。 */
    private static final int CANDIDATE_POOL = 50;

    /** 来源加权：curated 人工样例 > promoted 点赞历史 > 普通历史。 */
    private static final int WEIGHT_CURATED = 6;
    private static final int WEIGHT_PROMOTED = 3;

    private final QueryHistoryMapper queryHistoryMapper;
    private final FewShotExampleMapper fewShotExampleMapper;

    public FewShotStore(QueryHistoryMapper queryHistoryMapper, FewShotExampleMapper fewShotExampleMapper) {
        this.queryHistoryMapper = queryHistoryMapper;
        this.fewShotExampleMapper = fewShotExampleMapper;
    }

    /**
     * 取与当前问题最相似的样例（curated + promoted + history 三路合并）。
     *
     * <p>{@code tenant_id} 由租户拦截器自动注入（本方法在问数链路内调用，租户上下文已就绪）。
     *
     * <p>Phase-1 意图对齐：{@code runtimeIntentName} 为引擎运行时识别的意图枚举名（{@code Intent} 值之一）。
     * 非空时与样本自带 {@code intent} 双向对齐——<b>同</b>意图样例 {@code +intentWeight}、<b>异</b>意图样例
     * {@code -intentWeight/2}（仅微调、不替代词面重合度主信号）；样本 {@code intent==null}（旧样例/历史未标注）
     * 或 {@code runtimeIntentName==null}（开关关）时双向调整为 0，完全退回当前行为、向后兼容。
     *
     * @param limit             期望返回条数；&le;0 或无相似样本时返回空列表
     * @param runtimeIntentName 运行时识别的意图枚举名（可空；null=不启用意图对齐）
     * @param intentWeight      意图对齐强度（同意图加分值；仅 runtimeIntentName 非空且开关开时生效）
     */
    public List<FewShot> similar(Long datasourceId, String question, int limit,
                                 String runtimeIntentName, int intentWeight) {
        if (limit <= 0) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        List<String> qTokens = tokens(question);
        // ① curated 样例库（人工维护，权重最高）
        for (FewShot c : curatedCandidates(datasourceId)) {
            addScored(scored, qTokens, c, WEIGHT_CURATED, runtimeIntentName, intentWeight);
        }
        // ② + ③ 历史：点赞晋升（promoted）+ 普通成功历史
        for (HistoryCandidate hc : historyCandidates(datasourceId)) {
            addScored(scored, qTokens, hc.shot(), hc.promoted() ? WEIGHT_PROMOTED : 0,
                    runtimeIntentName, intentWeight);
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<FewShot> picked = new ArrayList<>();
        List<Long> hitIds = new ArrayList<>();
        for (Scored s : scored) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(s.shot());
            if (s.shot().exampleId() != null) {
                hitIds.add(s.shot().exampleId());
            }
        }
        bumpUseCount(hitIds);
        return picked;
    }

    /**
     * curated 样例的问题文本（推荐问法生成用，V3）：直接取人工维护样例的“问法”作为风格参考。
     * 与 {@link #similar} 不同，这里<b>不回写 use_count</b>——推荐问法只是借鉴风格，并非真实答题命中。
     *
     * @param limit 期望条数；&le;0 或无样例时返回空列表（绝不影响调用方）
     */
    public List<String> curatedQuestions(Long datasourceId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (FewShot c : curatedCandidates(datasourceId)) {
            if (out.size() >= limit) {
                break;
            }
            if (c.question() != null && !c.question().isBlank()) {
                out.add(c.question());
            }
        }
        return out;
    }

    /** curated 样例（enabled，本数据源）。 */
    private List<FewShot> curatedCandidates(Long datasourceId) {
        try {
            List<FewShotExample> rows = fewShotExampleMapper.selectList(new LambdaQueryWrapper<FewShotExample>()
                    .eq(FewShotExample::getDatasourceId, datasourceId)
                    .eq(FewShotExample::getEnabled, 1)
                    .isNotNull(FewShotExample::getSqlText)
                    .orderByDesc(FewShotExample::getUseCount)
                    .last("LIMIT " + CANDIDATE_POOL));
            List<FewShot> out = new ArrayList<>(rows.size());
            for (FewShotExample e : rows) {
                out.add(new FewShot(e.getQuestion(), e.getSqlText(), e.getId(), e.getIntent()));
            }
            return out;
        } catch (Exception e) {
            log.debug("curated 样例读取跳过（表未就绪）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 历史候选（status=OK 且含 SQL）；点赞（feedback=1）标记为 promoted。 */
    private List<HistoryCandidate> historyCandidates(Long datasourceId) {
        try {
            List<QueryHistory> rows = queryHistoryMapper.selectList(new LambdaQueryWrapper<QueryHistory>()
                    .eq(QueryHistory::getDatasourceId, datasourceId)
                    .eq(QueryHistory::getStatus, "OK")
                    .isNotNull(QueryHistory::getSqlText)
                    .orderByDesc(QueryHistory::getCreatedAt)
                    .last("LIMIT " + CANDIDATE_POOL));
            List<HistoryCandidate> out = new ArrayList<>(rows.size());
            for (QueryHistory h : rows) {
                boolean promoted = h.getFeedback() != null && h.getFeedback() == 1;
                out.add(new HistoryCandidate(new FewShot(h.getQuestion(), h.getSqlText()), promoted));
            }
            return out;
        } catch (Exception e) {
            log.debug("少样本历史读取跳过（表空或未就绪）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 命中 curated 样例后 use_count+1（统计用，失败不影响主链路）。 */
    private void bumpUseCount(List<Long> ids) {
        for (Long id : ids) {
            try {
                FewShotExample row = fewShotExampleMapper.selectById(id);
                if (row != null) {
                    row.setUseCount((row.getUseCount() == null ? 0 : row.getUseCount()) + 1);
                    fewShotExampleMapper.updateById(row);
                }
            } catch (Exception e) {
                log.debug("use_count 回写跳过：{}", e.getMessage());
            }
        }
    }

    /**
     * 词面重合度 + 来源权重 + 意图双向对齐微调，得到样例分值。
     *
     * <p>意图对齐：仅当 {@code runtimeIntentName} 与样本 {@code shot.intent()} 均非空时生效——
     * 二者相等 {@code +intentWeight}（同意图提权），否则 {@code -intentWeight/2}（异意图降权，
     * 不替代词面主信号）。任一为空则调整恒为 0，退回纯词面+来源权重排序，向后兼容旧样例/历史。
     */
    private void addScored(List<Scored> scored, List<String> qTokens, FewShot shot, int weight,
                           String runtimeIntentName, int intentWeight) {
        int intentAdj = 0;
        if (runtimeIntentName != null && shot.intent() != null && intentWeight != 0) {
            intentAdj = shot.intent().equalsIgnoreCase(runtimeIntentName)
                    ? +intentWeight
                    : -(intentWeight / 2);
        }
        int sc = overlap(qTokens, tokens(shot.question())) + weight + intentAdj;
        // curated/promoted 即使词面不完全重合也值得注入（权重保底）；普通历史需真正命中
        if (sc > 0 && (weight > 0 || overlap(qTokens, tokens(shot.question())) > 0)) {
            scored.add(new Scored(shot, sc));
        }
    }

    private record HistoryCandidate(FewShot shot, boolean promoted) {
    }

    private int overlap(List<String> a, List<String> b) {
        int n = 0;
        for (String x : a) {
            if (b.contains(x)) {
                n++;
            }
        }
        return n;
    }

    /** 极简分词：英文/数字按连续串切词，中文按 2-gram 切分，够用于重合度比较。 */
    private List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String t = text.toLowerCase();
        for (String w : t.split("[^a-z0-9]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        String han = t.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i + 2 <= han.length(); i++) {
            out.add(han.substring(i, i + 2));
        }
        return out;
    }

    private record Scored(FewShot shot, int score) {
    }
}
