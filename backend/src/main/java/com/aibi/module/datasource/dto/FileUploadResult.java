package com.aibi.module.datasource.dto;

import java.util.List;

/**
 * 文件上传结果（问题3）：落盘路径 + 元信息 + 工作表清单预览，供前端回填表单、勾选导入范围并确认后再入库。
 *
 * <p>顶层 {@code columns}/{@code previewRows}/{@code truncated} 是<b>首个可导入工作表</b>的预览，
 * 为单表（CSV、单 sheet Excel）调用方保留的兼容入口；多 sheet 场景请读 {@code sheets}。
 *
 * @param filePath     服务器落盘路径（回填到数据源 filePath 字段）
 * @param originalName 原始文件名（展示用）
 * @param size         文件字节数
 * @param type         推断出的数据源类型（CSV / EXCEL）
 * @param columns      首个可导入工作表的列名
 * @param previewRows  首个可导入工作表的前 N 行预览数据
 * @param truncated    首个可导入工作表是否因超过预览上限而截断
 * @param sheets       全部工作表的预览（CSV 恒为一条）
 */
public record FileUploadResult(
        String filePath,
        String originalName,
        long size,
        String type,
        List<String> columns,
        List<List<String>> previewRows,
        boolean truncated,
        List<FileSheetPreview> sheets) {
}
