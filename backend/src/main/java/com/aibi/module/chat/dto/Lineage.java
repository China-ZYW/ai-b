package com.aibi.module.chat.dto;

import java.util.List;

/**
 * 结果溯源元数据（T10-3）：随 {@link ResultMeta#lineage()} 回传，告诉用户"这次结果从哪几张表查出来、
 * 这些表在语义层里叫什么"，把审计库里早已存在的执行 SQL 与表集合，兑现到结果面板里可见的出处说明。
 *
 * <p>纯确定性抽取产物（零 LLM）。为 {@code null} 的三种情形（前端据此整体隐藏「数据来源」块）：
 * 溯源开关关闭 / SQL 解析不出任何表 / 装配内部异常（fail-soft，绝不拖垮问数返回）。
 *
 * @param tables 来源表清单（按裸表名排序去重；空表示未解析出任何表）
 * @param sql    实际执行 SQL（与 {@code result.sql} 同值，冗余一份使溯源自洽、可脱离结果单独回放核对）
 */
public record Lineage(
        List<LineageTable> tables,
        String sql
) {
}
