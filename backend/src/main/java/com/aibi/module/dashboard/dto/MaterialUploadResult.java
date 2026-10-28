package com.aibi.module.dashboard.dto;

/**
 * 背景素材上传结果：返回可公开访问的图片 URL（供大屏背景 {@code url(...)} 引用）。
 *
 * <p>该 URL 指向 {@code /api/public/materials/{file}}，已在 {@code SecurityConfig} 放行，公开分享页可直接加载。
 */
public record MaterialUploadResult(String url) {
}
