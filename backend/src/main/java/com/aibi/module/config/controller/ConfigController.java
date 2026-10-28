package com.aibi.module.config.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.dto.ConfigForm;
import com.aibi.module.config.entity.SysConfig;
import com.aibi.module.config.service.ConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 配置中心接口（配置化升级 V2，租户侧）：合并视图供前端消费，租户级覆盖的增改删。
 *
 * <p>GLOBAL scope 的平台默认配置由超管经 {@code /api/admin/config} 管理；本控制器写入一律落
 * TENANT scope（scope_id=当前租户），实现"租户个性化覆盖平台默认"。
 *
 * <p><b>平台管控</b>：超管可把某个 GLOBAL 配置键标记为管控（{@code managed=1}）。此类键在解析时
 * 由 GLOBAL 压过 TENANT、租户写入被 {@code /upsert} 以 40301 拒绝；{@code GET /managed} 下发锁定清单，
 * 供前端把对应配置项显示为不可编辑（仅展示用，安全边界在服务层）。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 当前租户合并视图（GLOBAL 默认 + TENANT 覆盖后的最终值）：category → key → value。
     *
     * <p><b>故意不挂 {@code @RequiresPermission}</b>：本端点由前端 {@code configStore} 在应用启动时
     * 为<b>所有已认证用户</b>拉取，驱动结果面板/图表的展示配置（ResultPanel、WidgetCard、AppLayout 均消费）。
     * 若按 {@code config:sys:read}（VIEWER/BILLING 无权）门禁，只读成员每次进入都会收到 403 提示——
     * 那是行为回归。展示配置本身非敏感，认证即可读；敏感的<b>覆盖写</b>由下方 {@code upsert}/{@code delete}
     * 的 {@code config:sys:write} 收敛。故 {@code ApiPermissionRegistry} 对本端点的"未标注"告警属预期，
     * 是有意的基线放行，不是漏配。
     */
    @GetMapping("/effective")
    public R<Map<String, Map<String, Object>>> effective() {
        return R.ok(configService.effective(TenantContext.tenantId()));
    }

    /**
     * 平台管控键清单（{@code "CATEGORY:configKey"}）：供租户侧配置页把对应项标为"平台管控"并禁用编辑。
     *
     * <p><b>故意不挂 {@code @RequiresPermission}</b>：与 {@link #effective()} 同理——本端点只回答
     * "哪些配置项被平台锁定了"，不含任何敏感数据（category 与 key 名称本就在前端配置页明文展示），
     * 且被所有能进配置页的角色读取；按 {@code config:sys:read} 门禁会让只读角色拿到 403 提示。
     * 真正的拦截在 {@code ConfigService} 的解析与写入两处，本端点仅用于展示锁定态。
     * 故 {@code ApiPermissionRegistry} 对本端点的"未标注"告警属预期，是有意放行。
     */
    @GetMapping("/managed")
    public R<List<String>> managed() {
        return R.ok(configService.managedKeys());
    }

    /**
     * 合并视图的说明字典：category → key → description，供租户侧配置页「当前生效值」表展示中文说明。
     *
     * <p><b>故意不挂 {@code @RequiresPermission}</b>：与 {@link #effective()} / {@link #managed()} 同理——
     * 说明是配置页的展示文案（category 与 key 本就在该页明文展示），非敏感数据；按 {@code config:sys:read}
     * 门禁会让只读角色在配置页拿到 403 提示。真正的写拦截在 {@code upsert}/{@code delete}。
     * {@code ApiPermissionRegistry} 对本端点的"未标注"告警属预期，是有意放行。
     */
    @GetMapping("/descriptions")
    public R<Map<String, Map<String, String>>> descriptions() {
        return R.ok(configService.descriptions(TenantContext.tenantId()));
    }

    /** 本租户的配置覆盖行（管理页编辑用）。 */
    @GetMapping("/rows")
    @RequiresPermission("config:sys:read")
    public R<List<SysConfig>> rows(@RequestParam String category) {
        return R.ok(configService.listRows(ConfigService.SCOPE_TENANT, TenantContext.tenantId(), category));
    }

    /**
     * upsert 本租户配置覆盖；该键已被平台管控（GLOBAL 行 {@code managed=1}）时以 40301 拒绝。
     *
     * <p><b>幂等</b>：内容无变化时服务层不写库，{@code data} 返回 {@code null}（提交成功但未发生变更）。
     */
    @PutMapping
    @RequiresPermission("config:sys:write")
    public R<SysConfig> upsert(@RequestBody @Valid ConfigForm form) {
        Long tenantId = TenantContext.requireTenantId();
        return R.ok(configService.upsert(ConfigService.SCOPE_TENANT, tenantId,
                form.category(), form.configKey(), form.valueJson(), form.description()));
    }

    /** 删除本租户配置覆盖（恢复平台默认）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("config:sys:write")
    public R<Void> delete(@PathVariable Long id) {
        Long tenantId = TenantContext.requireTenantId();
        configService.delete(id, ConfigService.SCOPE_TENANT, tenantId);
        return R.ok();
    }
}
