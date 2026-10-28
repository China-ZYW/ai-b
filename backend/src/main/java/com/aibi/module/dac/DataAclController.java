package com.aibi.module.dac;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.dac.dto.DataAclClearForm;
import com.aibi.module.dac.dto.DataAclForm;
import com.aibi.module.dac.dto.DataAclItemVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据权限 A · 管理接口（设计文档《组织治理专项设计》§3）。
 *
 * <p>功能权限复用 {@code org:customrole:write}（组织管理员级）；成员直授与角色继承的数据授权都在本控制器维护。
 * 解析与脱敏由 {@link DataMaskGate} 在问数 / 指标 / 驾驶舱 / 归因 / 分享 出口统一施加。
 */
@RestController
@RequestMapping("/api/org/data-acl")
@RequiresPermission("org:customrole:write")
public class DataAclController {

    private final DataAclService dataAclService;

    public DataAclController(DataAclService dataAclService) {
        this.dataAclService = dataAclService;
    }

    /** 列出某成员的全部数据授权（数据源可见性 + 字段脱敏）。 */
    @GetMapping("/user/{uid}")
    public R<List<DataAclItemVO>> listUser(@PathVariable Long uid) {
        return R.ok(dataAclService.listUser(uid));
    }

    /** 列出某自定义角色继承的全部数据授权。 */
    @GetMapping("/role/{customRoleId}")
    public R<List<DataAclItemVO>> listRole(@PathVariable Long customRoleId) {
        return R.ok(dataAclService.listRole(customRoleId));
    }

    /** 设置一条数据授权（整体替换语义：删旧插新）。 */
    @PostMapping("/set")
    public R<Void> set(@RequestBody DataAclForm form) {
        dataAclService.setAcl(form);
        return R.ok();
    }

    /** 清除一条数据授权（恢复默认：可见 / 按语义层默认掩码）。 */
    @PostMapping("/clear")
    public R<Void> clear(@RequestBody DataAclClearForm form) {
        dataAclService.clearAcl(form);
        return R.ok();
    }
}
