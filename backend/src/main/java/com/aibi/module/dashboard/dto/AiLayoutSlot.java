package com.aibi.module.dashboard.dto;

/**
 * AI 布局顾问给出的一个网格槽位（与 {@code dashboard.layout[]} 的 x/y/w/h 对齐）。
 *
 * @param i      组件 key
 * @param x     起始列（0 基）
 * @param y     起始行（0 基）
 * @param w     占列数
 * @param h     占行数
 * @param reason 该尺寸/位置的中文依据（展示给用户，便于理解与人工微调）
 */
public record AiLayoutSlot(String i, int x, int y, int w, int h, String reason) {
}
