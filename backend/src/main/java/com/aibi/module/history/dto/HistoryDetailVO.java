package com.aibi.module.history.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.Verify;
import com.aibi.module.datasource.model.QueryResult;

/**
 * 历史回放详情（历史会话点击还原）：历史元数据 + 结果数据 + 图表规格 + AI 解读 + 质量校验。
 *
 * <p>{@code result} 优先取问数当时落库的结果快照；无快照的老记录由详情接口
 * 重跑已存 SQL 回放（SqlValidator 复校、不耗 LLM、不计配额）并回填快照。
 * 失败类历史或回放失败时为 null，前端仅展示元数据。
 *
 * <p>{@code insight} / {@code verify} 取问数当时落库的解读与校验结论（不重算 LLM）；
 * 老记录、关闭对应开关或当时未产出时为 null，前端相应区块不展示。即便 {@code result}
 * 回放失败（数据源已删/改），只要当时存过解读/校验仍会带回，尽量还原"问数当时"的完整结论。
 *
 * @param history 历史元数据（问题 / SQL / 行数 / 耗时 / 反馈等）
 * @param result  结果数据（快照或回放产物；可空）
 * @param chart   图表规格（问数当时推荐或回放重建；可空）
 * @param insight AI 数据解读（问数当时落库；可空）
 * @param verify  AI 质量校验（问数当时落库；可空）
 */
public record HistoryDetailVO(
        HistoryVO history,
        QueryResult result,
        ChartSpec chart,
        Insight insight,
        Verify verify,
        /**
         * 维度波动归因结果（{@code AttributionVO} 反序列化后的 JSON 透传；可空）。
         * 与 insight / verify 同一套"问数当时产物、只存不重算"口径——归因在跑完时按 historyId 回写本行，
         * 回放据此还原归因面板；老历史从未落过归因则为 null，前端相应区块不展示。
         */
        Object attribution
) {
}
