package com.aibi.module.admin.dto;

import java.util.List;

/**
 * 接口权限矩阵（平台后台「接口权限矩阵」页，§9.2 治理组 / 设计文档 §6.4）。
 *
 * <p>数据来自 {@code ApiPermissionRegistry} 启动期对全部 {@code @RequestMapping} 的扫描快照。
 * 治理价值在于把「新加接口忘了挂权限点」这一最易腐化点变成可见指标：{@code annotated=false}
 * 且 {@code failClosed=true} 的行即高危漏配，前端红色高亮。
 *
 * @param total                 扫描到的接口总数
 * @param annotated             已挂 {@code @RequiresPermission} 的接口数
 * @param unannotatedFailClosed 落在 fail-closed 域却未挂权限点的接口数（&gt;0 即需补注解）
 * @param failClosedDomains     生效的 fail-closed 域清单
 * @param endpoints             逐接口画像
 */
public record ApiMatrixVO(
        int total,
        int annotated,
        int unannotatedFailClosed,
        List<String> failClosedDomains,
        List<Row> endpoints) {

    /**
     * 单接口画像。
     *
     * @param permissionCodes 该接口要求的权限点码（空表示未挂注解）
     * @param resourceType    {@code @CheckResourceAccess} 的资源类型，无则 null
     * @param annotated       是否已挂权限点
     * @param failClosed      是否落在 fail-closed 域
     */
    public record Row(
            String httpMethod,
            String pattern,
            String controller,
            String handler,
            List<String> permissionCodes,
            String resourceType,
            boolean annotated,
            boolean failClosed) {
    }
}
