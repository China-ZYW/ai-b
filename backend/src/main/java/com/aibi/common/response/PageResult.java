package com.aibi.common.response;

import java.util.List;

/**
 * 通用分页结果包装（M4 起用于历史/收藏等列表接口）。
 *
 * @param items  当前页数据
 * @param total  满足条件的总条数
 * @param limit  本页大小
 * @param offset 偏移量
 */
public record PageResult<T>(List<T> items, long total, int limit, int offset) {

    public static <T> PageResult<T> of(List<T> items, long total, int limit, int offset) {
        return new PageResult<>(items, total, limit, offset);
    }
}
