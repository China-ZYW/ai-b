package com.aibi.module.drift;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.config.service.AibiConfig;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 结构对账接口（Schema Drift）：检测下游元数据与物理表结构之间的漂移，并支持一键清理孤儿。
 *
 * <p>挂载在语义层路由下（{@code /api/semantic/drift}），复用 {@code semantic:meta:read}（检测）与
 * {@code semantic:meta:delete}（清理）权限点——结构对账同属"语义层治理"职责，不新增权限码以免抬升
 * {@code perm_version} 逼全员重登。
 *
 * <p>检测只读、对取数链路零副作用；清理仅删元数据（语义行 / 关联 / 权限），不动业务库，且由租户拦截器强制隔离。
 */
@RestController
@RequestMapping("/api/semantic/drift")
public class SchemaDriftController {

    private final SchemaDriftService driftService;
    private final SchemaWatchService watchService;
    private final AibiConfig aibiConfig;

    public SchemaDriftController(SchemaDriftService driftService,
                                 SchemaWatchService watchService,
                                 AibiConfig aibiConfig) {
        this.driftService = driftService;
        this.watchService = watchService;
        this.aibiConfig = aibiConfig;
    }

    /** 对指定数据源做全量结构对账，返回五类失配清单（只读）。 */
    @PostMapping("/check")
    @RequiresPermission("semantic:meta:read")
    public R<SchemaDriftService.DriftReport> check(@RequestParam Long datasourceId) {
        return R.ok(driftService.check(datasourceId));
    }

    /**
     * 一键清理勾选的孤儿元数据（语义行 / 关联 / 四类权限）。
     * 指标与驾驶舱属"坏引用高亮 + 跳转修复"，不在此删除。
     */
    @PostMapping("/prune")
    @RequiresPermission("semantic:meta:delete")
    public R<Map<String, Integer>> prune(@RequestBody DriftPruneRequest request) {
        return R.ok(driftService.prune(request));
    }

    /**
     * 结构变更感知（T12-3）：重扫 schema 与上轮快照 diff → 删表/删列反查受影响查询模板/指标，
     * 命中入治理台账并定向站内信创建人；首轮只建基准不告警。
     *
     * <p>门控 {@code METRIC / drift-watch}（默认开）：关闭则业务码 40301 拒用。同步执行（全量直扫，同 check 口径）。
     */
    @PostMapping("/watch")
    @RequiresPermission("semantic:meta:read")
    public R<SchemaWatchService.WatchResult> watch(@RequestParam Long datasourceId) {
        if (!aibiConfig.driftWatchEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "结构变更感知未启用（METRIC / drift-watch），请联系管理员开启");
        }
        return R.ok(watchService.watch(datasourceId));
    }
}
