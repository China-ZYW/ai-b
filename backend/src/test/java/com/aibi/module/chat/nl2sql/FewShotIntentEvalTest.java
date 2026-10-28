package com.aibi.module.chat.nl2sql;

import com.aibi.module.fewshot.entity.FewShotExample;
import com.aibi.module.fewshot.mapper.FewShotExampleMapper;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 少样本「意图对齐」离线评测（Phase-1，不依赖共享 MySQL）。
 *
 * <p>用 mock 的 {@code FewShotExampleMapper} / {@code QueryHistoryMapper} 喂固定样例集，直接驱动
 * {@link FewShotStore#similar(Long, String, int, String, int)} 的打分与排序，断言：
 * <ul>
 *   <li><b>正例（召回提升）</b>：词面重合略低、但意图与问句<b>相同</b>的样例，在意图对齐开启后
 *       必须排到"词面重合更高、但意图不同"的干扰样例之前（首次命中率提升）；关闭对齐时后者反而更靠前
 *       （这正是被修复的排序偏差，作为对照一并断言）。</li>
 *   <li><b>阴性对照（防误伤）</b>：意图对齐只应<b>微调</b>——绝不能把正确样例挤出版面、也不能让
 *       意图不同的干扰样例靠 penalty 被彻底丢弃（仍按词面主信号保底）；无意图标注（旧样例/历史）的样例
 *       必须完全不受对齐影响，向后兼容。</li>
 *   <li><b>回归数 = 0</b>：对齐开启后，预期正确样例的排名不得劣于关闭时。</li>
 * </ul>
 *
 * <p>token 重合度用手工设计的英文句子精确控制（{@code tokens()} 按 [^a-z0-9]+ 切词、≥2 字符），
 * 使打分可预期、断言可复现。
 */
class FewShotIntentEvalTest {

    private static final long DS = 999L;

    /** 构造一条人工样例（curated），enabled + 有 SQL 才会被 curatedCandidates 纳入。 */
    private static FewShotExample curated(long id, String question, String sql, String intent) {
        FewShotExample e = new FewShotExample();
        e.setId(id);
        e.setDatasourceId(DS);
        e.setQuestion(question);
        e.setSqlText(sql);
        e.setIntent(intent);
        e.setEnabled(1);
        e.setUseCount(0);
        return e;
    }

    private static FewShotStore store(List<FewShotExample> curatedRows, List<QueryHistory> historyRows) {
        FewShotExampleMapper exMapper = mock(FewShotExampleMapper.class);
        QueryHistoryMapper hisMapper = mock(QueryHistoryMapper.class);
        when(exMapper.selectList(any())).thenReturn(curatedRows);
        when(hisMapper.selectList(any())).thenReturn(historyRows);
        return new FewShotStore(hisMapper, exMapper);
    }

    /** 在返回列表中按 exampleId 找 0-based 排名；找不到返回 -1。 */
    private static int rank(List<FewShot> shots, long exampleId) {
        for (int i = 0; i < shots.size(); i++) {
            if (shots.get(i).exampleId() != null && shots.get(i).exampleId() == exampleId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 正例 + 阴性对照：词面重合更高的"异意图"干扰项，在关闭对齐时排到正确样例之前（被修的偏差）；
     * 开启对齐（runtimeIntentName 与正确样例意图相同）后，正确样例必须反超，且干扰项仍留在名单内
     * （仅降权、不丢弃）——即"微调而非替代主信号"。
     */
    @Test
    void intentBoostPromotesSameIntentAndOnlyPenalizesNotDrops() {
        // 运行时问句意图 = AGGREGATION；预期正确样例 S 与问句同意图，干扰项 D 为 DETAIL 且词面重合更高
        List<FewShotExample> rows = new ArrayList<>();
        rows.add(curated(1L, "total sales region detail", "SELECT ...", "DETAIL"));   // D：词面重合 3
        rows.add(curated(2L, "total sales amount", "SELECT ...", "AGGREGATION"));     // S：词面重合 2（正确）
        FewShotStore store = store(rows, List.of());

        // —— 关闭对齐（旧行为 / 回归基线）——
        List<FewShot> off = store.similar(DS, "total sales region", 3, null, 4);
        int dOff = rank(off, 1L);
        int sOff = rank(off, 2L);
        assertNotNull(off);
        assertTrue(dOff >= 0 && sOff >= 0, "关闭对齐时两条样例都应在名单内");
        assertTrue(dOff < sOff, "回归基线：词面重合更高的异意图样例 D 应排在正确样例 S 之前（被修的偏差）");

        // —— 开启对齐（runtimeIntentName = AGGREGATION，与 S 同意图）——
        List<FewShot> on = store.similar(DS, "total sales region", 3, "AGGREGATION", 4);
        int dOn = rank(on, 1L);
        int sOn = rank(on, 2L);
        assertTrue(sOn >= 0 && dOn >= 0, "开启对齐两条样例都应在名单内（penalty 只降权不丢弃）");
        assertTrue(sOn < dOn, "开启对齐后：同意图正确样例 S 必须排到异意图干扰项 D 之前");
        assertTrue(sOn == 0, "开启对齐后：正确样例 S 应升至 top-1（recall@1 提升）");
        assertTrue(sOn <= sOff, "回归数=0：对齐开启后正确样例排名不得劣于关闭时");
    }

    /**
     * 阴性对照（向后兼容）：未标注意图（intent=null）的旧样例 / 历史问法，完全不受对齐影响——
     * 对齐开启时其得分 = 词面重合 + 来源权重，既不提权也不降权。
     */
    @Test
    void nullIntentSampleUntouchedByAlignment() {
        List<FewShotExample> rows = new ArrayList<>();
        rows.add(curated(10L, "total sales amount", "SELECT ...", "AGGREGATION")); // S：同意图，应被提权
        rows.add(curated(11L, "total sales", "SELECT ...", null));                 // Z：旧样例未标意图
        FewShotStore store = store(rows, List.of());

        List<FewShot> on = store.similar(DS, "total sales region", 3, "AGGREGATION", 4);
        int sOn = rank(on, 10L);
        int zOn = rank(on, 11L);
        assertTrue(sOn >= 0 && zOn >= 0, "开启对齐时：同意图样例与未标意图样例都应正常出现");
        // S 被提权(+4) 必然排在 Z 之前（Z 无调整，得分 = 词面重合+6）
        assertTrue(sOn < zOn, "未标意图样例 Z 不应获得提权，必须排在已对齐的 S 之后");
    }

    /**
     * 阴性对照（历史无 intent 列）：历史成功问法经 historyCandidates 构造时 intent 恒为 null，
     * 不应被提权或降权，仅按词面主信号排序。
     */
    @Test
    void historyCandidateIntentIsNullAndUntouched() {
        // 仅放一条 curated 做对照，再放一条会被 historyCandidates 读取的历史；二者词面重合不同
        List<FewShotExample> curatedRows = new ArrayList<>();
        curatedRows.add(curated(20L, "total sales region", "SELECT ...", "AGGREGATION")); // 词面重合最高
        List<QueryHistory> historyRows = new ArrayList<>();
        QueryHistory h = new QueryHistory();
        h.setDatasourceId(DS);
        h.setQuestion("total sales amount");
        h.setSqlText("SELECT ...");
        h.setStatus("OK");
        h.setFeedback(1); // promoted
        historyRows.add(h);

        FewShotStore store = store(curatedRows, historyRows);
        List<FewShot> on = store.similar(DS, "total sales region", 5, "AGGREGATION", 4);
        // curated(20) 词面重合 3 + 权重 6 + 提权 4 = 13；历史词面重合 2 + promoted权重 3 + 无意图调整 = 5
        // 断言：列表非空、curated 排第一、历史也在名单内（历史未因"异意图"被错误降权）
        assertTrue(!on.isEmpty(), "结果不应为空");
        assertEquals(20L, on.get(0).exampleId(), "词面+意图双高的 curated 应排第一");
        boolean historyPresent = on.stream().anyMatch(s -> s.exampleId() == null
                && "total sales amount".equals(s.question()) && s.intent() == null);
        assertTrue(historyPresent, "历史候选（intent=null）应照常进入名单，不因对齐被误伤/丢弃");
    }

    /**
     * 回归门禁：对齐强度为 0 等价于关闭对齐（调整恒为 0），开启但 weight=0 时的排序必须与
     * runtimeIntentName=null 完全一致。
     */
    @Test
    void weightZeroEqualsBoostOff() {
        List<FewShotExample> rows = new ArrayList<>();
        rows.add(curated(1L, "total sales region detail", "SELECT ...", "DETAIL"));
        rows.add(curated(2L, "total sales amount", "SELECT ...", "AGGREGATION"));
        FewShotStore store = store(rows, List.of());

        List<FewShot> off = store.similar(DS, "total sales region", 3, null, 4);
        List<FewShot> weightZero = store.similar(DS, "total sales region", 3, "AGGREGATION", 0);
        assertEquals(off.size(), weightZero.size(), "weight=0 应与关闭对齐返回相同条数");
        for (int i = 0; i < off.size(); i++) {
            assertEquals(off.get(i).exampleId(), weightZero.get(i).exampleId(),
                    "weight=0 时每条排序必须与关闭对齐完全一致（回归门禁）");
        }
    }
}
