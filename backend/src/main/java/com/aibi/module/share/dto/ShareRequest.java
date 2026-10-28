package com.aibi.module.share.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建分享链接请求（M7 / 开发计划 T4.7）。
 *
 * <p>分享的是<b>结果快照</b>：客户端把问数已得到的 {@code result}（列+行）与 {@code chart} 一并提交，
 * 服务端截断到 {@code snapshot-max-rows} 行后序列化落库；公开访问只读快照，绝不实时回查业务库。
 *
 * @param datasourceId 关联数据源（仅记录归属，公开访问不再连库）
 * @param question     分享展示的问题
 * @param sql          生成该结果的 SQL（可展示，便于他人理解口径）
 * @param result       结果快照来源（列 + 数据行）
 * @param chart        图表推荐快照（可空）
 * @param password     访问密码（可空；非空则以 BCrypt 哈希存储）
 * @param expireHours  有效期（小时）；{@code null} 表示永不过期
 * @param watermark    是否加水印；免费版强制为 true
 */
public record ShareRequest(
        @NotNull(message = "数据源不能为空") Long datasourceId,
        @NotBlank(message = "问题不能为空") @Size(max = 500, message = "问题过长（<=500 字）") String question,
        String sql,
        @NotNull(message = "分享结果不能为空") QueryResult result,
        ChartSpec chart,
        @Size(max = 64, message = "密码过长（<=64 字）") String password,
        @Min(value = 1, message = "有效期至少 1 小时") Integer expireHours,
        Boolean watermark
) {
}
