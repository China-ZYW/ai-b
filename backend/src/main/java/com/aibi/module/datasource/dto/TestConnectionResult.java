package com.aibi.module.datasource.dto;

import java.util.List;

/**
 * 连通性测试结果（T2.2）。
 *
 * @param success   是否连通
 * @param message   结果说明（失败时为脱敏后的根因）
 * @param elapsedMs 探测耗时（毫秒，不含后面的库清单拉取）
 * @param catalogs  同一连接下可选作“跨库联合分析”的库/模式名（已降噪排序）；
 *                  文件类数据源、连接失败或驱动不支持时为空列表
 */
public record TestConnectionResult(boolean success, String message, long elapsedMs, List<String> catalogs) {

    /** 不带库清单的结果（连接失败时拿不到清单，文件类数据源无此概念）。 */
    public TestConnectionResult(boolean success, String message, long elapsedMs) {
        this(success, message, elapsedMs, List.of());
    }
}
