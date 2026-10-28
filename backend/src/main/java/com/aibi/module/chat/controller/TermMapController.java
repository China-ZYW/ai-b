package com.aibi.module.chat.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.common.response.R;
import com.aibi.module.chat.entity.TermMap;
import com.aibi.module.chat.mapper.TermMapMapper;
import com.aibi.module.datasource.service.DataSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 业务术语词典管理接口（按数据源维度维护）。
 *
 * <p>所有写操作都先经 {@link DataSourceService#loadConfig} 做跨租户校验——字典归属的
 * 数据源不属于当前租户时直接拒绝，复用既有的数据源行级权限，不另开权限点。
 *
 * <p>本接口只负责"维护词典"，归一化的消费方是 {@code TermDictionary}（问数编排层在路由前调用）。
 * 词典为空不影响任何问数（纯增强）。
 *
 * <p><b>权限点（L2，补挂）</b>：跨租户校验只解决"别动别人租户的数据"，不解决"本租户内谁有资格动"。
 * 词典是<b>全租户共享</b>的归一化资产（任何人的问数都会消费），此前任何已登录成员（含只读成员）
 * 都能改写它——属共享治理资产的越权写。此处<b>不新发明权限码</b>，复用已有的
 * {@code semantic:meta:read/write}（语义层查看/编辑，LEVEL=TENANT，仅 OWNER/ADMIN/DATA_STEWARD 可写），
 * 与"问数调优域内维护语义资产"的产品归属一致。
 * 注：本控制器在前端暂无调用方（预留接口面），故补挂注解不会改变任何现有页面行为。
 */
@RestController
@RequestMapping("/api/chat/term")
public class TermMapController {

    private final TermMapMapper mapper;
    private final DataSourceService dataSourceService;

    public TermMapController(TermMapMapper mapper, DataSourceService dataSourceService) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
    }

    /** 列出某数据源下的全部词典条目（含停用）。 */
    @GetMapping("/list")
    @RequiresPermission("semantic:meta:read")
    public R<List<TermMap>> list(@RequestParam Long datasourceId) {
        dataSourceService.loadConfig(datasourceId); // 跨租户拦截
        Long tenantId = TenantContext.requireTenantId();
        List<TermMap> rows = mapper.selectList(query(tenantId, datasourceId));
        return R.ok(rows);
    }

    /** 新增一条映射（tenant_id 由服务端按当前租户强制写入，杜绝越权写入他租户）。 */
    @PostMapping
    @RequiresPermission("semantic:meta:write")
    public R<Long> create(@RequestBody TermMap body) {
        if (body.getDatasourceId() == null || body.getTerm() == null || body.getTerm().isBlank()
                || body.getCanonical() == null || body.getCanonical().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源、口语词、规范名均必填");
        }
        dataSourceService.loadConfig(body.getDatasourceId()); // 跨租户拦截
        TermMap entity = new TermMap();
        entity.setTenantId(TenantContext.requireTenantId());
        entity.setDatasourceId(body.getDatasourceId());
        entity.setTerm(body.getTerm().trim());
        entity.setCanonical(body.getCanonical().trim());
        entity.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        mapper.insert(entity);
        return R.ok(entity.getId());
    }

    /** 修改映射（仅 term / canonical / enabled 可改；归属与租户不可变）。 */
    @PutMapping("/{id}")
    @RequiresPermission("semantic:meta:write")
    public R<Void> update(@PathVariable Long id, @RequestBody TermMap body) {
        TermMap exist = mapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "词典条目不存在");
        }
        dataSourceService.loadConfig(exist.getDatasourceId()); // 跨租户拦截
        if (body.getTerm() != null && !body.getTerm().isBlank()) {
            exist.setTerm(body.getTerm().trim());
        }
        if (body.getCanonical() != null && !body.getCanonical().isBlank()) {
            exist.setCanonical(body.getCanonical().trim());
        }
        if (body.getEnabled() != null) {
            exist.setEnabled(body.getEnabled());
        }
        mapper.updateById(exist);
        return R.ok();
    }

    /** 删除一条映射。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("semantic:meta:delete")
    public R<Void> delete(@PathVariable Long id) {
        TermMap exist = mapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "词典条目不存在");
        }
        dataSourceService.loadConfig(exist.getDatasourceId()); // 跨租户拦截
        mapper.deleteById(id);
        return R.ok();
    }

    /** 列表查询条件：按租户 + 数据源，按 id 倒序（新维护的在前）。 */
    private LambdaQueryWrapper<TermMap> query(Long tenantId, Long datasourceId) {
        LambdaQueryWrapper<TermMap> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TermMap::getTenantId, tenantId);
        wrapper.eq(TermMap::getDatasourceId, datasourceId);
        wrapper.orderByDesc(TermMap::getId);
        return wrapper;
    }
}
