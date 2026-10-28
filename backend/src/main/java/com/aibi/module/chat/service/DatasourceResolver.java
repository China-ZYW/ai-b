package com.aibi.module.chat.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.UserContext;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.service.DataSourceService;
import org.springframework.stereotype.Component;

/**
 * 问数入口的"租户/用户/数据源"解析协作类（T8.3 自 ChatService 抽出）。
 *
 * <p>对应编排第①步：定位租户与用户、加载数据源配置（跨租户访问在
 * {@link DataSourceService} 内被拦截，凭证在此解密），并执行数据源可见性
 * fail-closed 前置拦截（数据权限 A：被隐藏的数据源连 schema 都不进 Prompt，
 * 在消耗配额与技能路由之前即拦截，避免模型/用户触达无权访问的数据源；
 * 解析异常按隐藏处理由 {@link DataMaskGate} 保证）。
 */
@Component
public class DatasourceResolver {

    private final DataSourceService dataSourceService;
    private final DataMaskGate dataMaskGate;

    public DatasourceResolver(DataSourceService dataSourceService, DataMaskGate dataMaskGate) {
        this.dataSourceService = dataSourceService;
        this.dataMaskGate = dataMaskGate;
    }

    /** ①+①' 解析结果：租户 id、当前用户 uid（匿名=0）与数据源配置。 */
    public record Resolved(Long tenantId, long uid, DataSourceConfig config) {
    }

    public Resolved resolve(Long datasourceId) {
        Long tenantId = TenantContext.requireTenantId();
        long uid = safeUid();
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        if (dataMaskGate.isDatasourceHidden(datasourceId)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "该数据源对您不可见");
        }
        return new Resolved(tenantId, uid, config);
    }

    /** uid 兜底：匿名/系统调用时为 0（与原 ChatService.safeUid 同语义）。 */
    private static long safeUid() {
        Long uid = UserContext.userId();
        return uid == null ? 0L : uid;
    }
}
