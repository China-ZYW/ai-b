package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 从内置模板实例化驾驶舱表单：拷贝模板骨架布局，把已存指标按顺序绑定到槽位。
 *
 * @param templateCode 内置模板编码（见 {@code dashboard_template.code}）
 * @param metricIds    依序绑定到模板槽位的指标 id；数量不足则余槽留空（未绑定），超出则忽略多余
 */
public record FromTemplateForm(
        @NotBlank(message = "驾驶舱名称不能为空") String name,
        @NotBlank(message = "模板编码不能为空") String templateCode,
        List<Long> metricIds,
        String description,
        String theme,
        Integer refreshSeconds,
        Integer isScreen) {
}
