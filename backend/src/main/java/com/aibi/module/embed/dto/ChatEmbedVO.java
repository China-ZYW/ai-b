package com.aibi.module.embed.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对话问数嵌入视图对象（管理侧列表 / 创建返回）。
 *
 * <p><b>为什么不让实体直接出参</b>：实体的 {@code datasourceIds} 是落库用的<b>逗号串</b>
 * （{@code "3,1,2"}），前端契约却期望 {@code number[]}；直接返回会让前端把字符串按字符遍历，
 * 数据源列渲染成一堆碎块且无法显示名称。此 VO 把它解析成列表，并附带 {@code datasourceNames}
 * （id → 真实名称）供界面直接展示，避免前端再拉一次数据源列表去猜。
 *
 * @param datasourceIds   开放的数据源 id 列表（已解析、按开放顺序）
 * @param datasourceNames id → 数据源真实名称（仅含仍存在的数据源）
 */
public record ChatEmbedVO(
        Long id,
        String name,
        String token,
        Long datasourceId,
        List<Long> datasourceIds,
        Map<Long, String> datasourceNames,
        Integer enabled,
        LocalDateTime expireAt,
        String allowedOrigins,
        Integer viewCount,
        LocalDateTime createdAt) {
}
