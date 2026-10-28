package com.aibi.module.audit.service;

import com.aibi.common.response.PageResult;
import com.aibi.module.audit.dto.OperationLogVO;
import com.aibi.module.audit.dto.OperationQuery;
import com.aibi.module.audit.entity.OperationLog;
import com.aibi.module.audit.mapper.OperationLogMapper;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局操作日志服务（《角色权限体系》10.2 审计落地之二）：请求级自动捕获 + 检索/导出。
 *
 * <p><b>写入</b>：由 {@code OperationLogInterceptor} 在请求线程捕获上下文并组装实体，
 * 通过 {@code auditExecutor} 线程池异步落库（与 {@link AuditService} 同池、同「失败只告警不阻断」口径）。
 *
 * <p><b>检索</b>：复用 {@code @InterceptorIgnore} 的 Mapper 跨租户查询，按 {@code tenantId} 显式收敛范围；
 * 批量联查 {@code tenant} 名（一次 {@code selectBatchIds}，避免 N+1）。
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    /** 单页最大条数（防 limit 过大拖垮查询）。 */
    public static final int MAX_PAGE_SIZE = 200;
    /** 导出一次性拉取上限（超出请收窄过滤条件）。 */
    public static final int EXPORT_MAX = 5000;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OperationLogMapper mapper;
    private final TenantMapper tenantMapper;
    private final ThreadPoolTaskExecutor auditExecutor;

    public OperationLogService(OperationLogMapper mapper, TenantMapper tenantMapper,
                               @Qualifier("auditExecutor") ThreadPoolTaskExecutor auditExecutor) {
        this.mapper = mapper;
        this.tenantMapper = tenantMapper;
        this.auditExecutor = auditExecutor;
    }

    /** 异步落库：复用审计线程池，失败只告警不阻断主流程。 */
    public void saveAsync(OperationLog entity) {
        try {
            auditExecutor.execute(() -> {
                try {
                    mapper.insertLog(entity);
                } catch (Exception e) {
                    log.warn("操作日志写入失败(uri={}, uid={}): {}", entity.getUri(), entity.getUserId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("操作日志任务提交失败(uri={}): {}", entity.getUri(), e.getMessage());
        }
    }

    /** 分页检索（按时间倒序）。租户范围由调用方决定（平台跨租户 / 组织钉死当前租户）。 */
    public PageResult<OperationLogVO> search(OperationQuery q) {
        int limit = clampLimit(q.limit());
        int offset = Math.max(0, q.offset());
        List<OperationLog> rows = mapper.search(q.tenantId(), q.module(), q.method(), q.uri(),
                q.username(), q.from(), q.to(), limit, offset);
        long total = mapper.count(q.tenantId(), q.module(), q.method(), q.uri(), q.username(), q.from(), q.to());
        return PageResult.of(toVOs(rows), total, limit, offset);
    }

    /** 导出（同检索条件，一次性至多 {@link #EXPORT_MAX} 条）。 */
    public List<OperationLogVO> exportList(OperationQuery q) {
        return toVOs(mapper.search(q.tenantId(), q.module(), q.method(), q.uri(),
                q.username(), q.from(), q.to(), EXPORT_MAX, 0));
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** 批量联查租户名，避免逐行 N+1（{@code tenant} 在 IGNORE 名单，无租户注入）。 */
    private List<OperationLogVO> toVOs(List<OperationLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> tids = new HashSet<>();
        for (OperationLog r : rows) {
            if (r.getTenantId() != null && r.getTenantId() != 0L) {
                tids.add(r.getTenantId());
            }
        }
        Map<Long, Tenant> tenants = new HashMap<>();
        if (!tids.isEmpty()) {
            for (Tenant t : tenantMapper.selectBatchIds(tids)) {
                tenants.put(t.getId(), t);
            }
        }
        List<OperationLogVO> list = new ArrayList<>(rows.size());
        for (OperationLog r : rows) {
            Tenant tn = r.getTenantId() == null ? null : tenants.get(r.getTenantId());
            list.add(new OperationLogVO(r.getId(), r.getTenantId(), tn == null ? null : tn.getName(),
                    r.getUserId(), r.getUsername(), r.getOrgRoles(), r.getPlatformRoles(), r.getRequestId(),
                    r.getModule(), r.getAction(), r.getMethod(), r.getUri(), r.getQueryString(),
                    r.getRequestBody(), r.getIp(), r.getUserAgent(), r.getHttpStatus(), r.getErrorMsg(),
                    r.getDurationMs(), r.getCreatedAt()));
        }
        return list;
    }
}
