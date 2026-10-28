package com.aibi.module.manual.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户手册草稿保存表单（超管用）。仅写草稿列，不触碰已发布快照——发布是独立动作。
 *
 * @param title   手册标题（非空，最长 128）
 * @param content 手册正文（Markdown，可空表示清空草稿正文）
 */
public record ManualSaveForm(
        @NotBlank @Size(max = 128) String title,
        String content
) {
}
