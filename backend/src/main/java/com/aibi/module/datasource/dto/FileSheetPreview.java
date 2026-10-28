package com.aibi.module.datasource.dto;

import java.util.List;

/**
 * 上传文件中单个工作表的预览（供前端列出 sheet 清单、勾选要导入哪些）。
 *
 * <p>CSV 无工作表概念，清单里恒为一条（名为 {@code data}）。
 *
 * @param name        工作表名
 * @param index       在工作簿中的下标（前端回传选择时用<b>名字</b>，此下标仅供排序/展示）
 * @param columns     规范化后的列名；{@code importable=false} 时为空
 * @param previewRows 前 N 行预览数据
 * @param truncated   是否因超过预览上限而截断（还有更多行）
 * @param importable  是否可导入；{@code false} 表示该工作表首行没有表头（封面页/说明页/空表），
 *                    前端应置灰并提示"已跳过"，而不是让用户以为文件没解析出来
 */
public record FileSheetPreview(
        String name,
        int index,
        List<String> columns,
        List<List<String>> previewRows,
        boolean truncated,
        boolean importable) {
}
