package com.aibi.module.org.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.org.dto.MemberPasswordForm;
import com.aibi.module.org.dto.MemberRoleForm;
import com.aibi.module.org.dto.MemberUpdateForm;
import com.aibi.module.org.dto.MemberVO;
import com.aibi.module.org.dto.OwnerTransferForm;
import com.aibi.module.org.service.MemberService;
import jakarta.validation.Valid;
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
 * 组织内 · 成员管理接口（§9.2 组织设置 · 成员，写侧治理）。
 *
 * <p>挂在 {@code /api/org/**} 域（{@code SecurityConfig} 中为 {@code authenticated()}，非平台门禁，
 * 且在 {@code fail-closed-domains} 内），与 {@code OrgAuditController} 同域。方法级 {@code @RequiresPermission}
 * 按组织权限矩阵收敛：{@code member:*} 多为 {@code YYNNNN}（仅 OWNER/ADMIN），{@code member:self:leave}
 * 为 {@code NYYYYY}（全组织角色可退出），{@code org:org:transfer} 为 {@code YNNNNN}（仅 OWNER）。
 *
 * <p>租户范围一律由服务层从当前登录上下文强制推导，<b>不接受任何 {@code tenantId} 入参</b>。所有护栏
 * （不可操作自己/越级/最后一个 OWNER）在服务层强制，前端仅呈现错误。回收角色用路径参数而非请求体，
 * 避免 DELETE 携带 body 在部分网关被剥离。
 */
@RestController
@RequestMapping("/api/org/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 本组织在册成员列表（联组织角色）。 */
    @GetMapping
    @RequiresPermission("member:member:read")
    public R<List<MemberVO>> list() {
        return R.ok(memberService.list());
    }

    /** 授予组织角色（OWNER 不可直接授予，须走转让）。 */
    @PostMapping("/roles")
    @RequiresPermission("member:role:assign")
    public R<Void> assignRole(@RequestBody @Valid MemberRoleForm form) {
        memberService.assignRole(form);
        return R.ok();
    }

    /** 回收组织角色（按 uid + roleCode 路径参数定位，OWNER 角色不可回收）。 */
    @DeleteMapping("/roles/{uid}/{roleCode}")
    @RequiresPermission("member:role:assign")
    public R<Void> revokeRole(@PathVariable Long uid, @PathVariable String roleCode,
                              @RequestParam(required = false) Long customRoleId) {
        memberService.revokeRole(new MemberRoleForm(uid, roleCode, customRoleId));
        return R.ok();
    }

    /** 更新成员组织内展示资料（昵称、职位）。 */
    @PutMapping("/{uid}")
    @RequiresPermission("member:member:write")
    public R<MemberVO> update(@PathVariable Long uid, @RequestBody MemberUpdateForm form) {
        return R.ok(memberService.update(uid, form));
    }

    /** 移除成员（置 status=0 + 吊销全部会话 + 释放席位）。 */
    @DeleteMapping("/{uid}")
    @RequiresPermission("member:member:delete")
    public R<Void> remove(@PathVariable Long uid) {
        memberService.remove(uid);
        return R.ok();
    }

    /** 重置成员口令（强制其重新登录）；审计绝不记口令明文。 */
    @PostMapping("/{uid}/reset-password")
    @RequiresPermission("member:password:reset")
    public R<Void> resetPassword(@PathVariable Long uid, @RequestBody @Valid MemberPasswordForm form) {
        memberService.resetPassword(uid, form);
        return R.ok();
    }

    /** 转让组织所有权（仅 OWNER）：原 OWNER 降 ADMIN，目标升 OWNER。 */
    @PostMapping("/transfer")
    @RequiresPermission("org:org:transfer")
    public R<Void> transfer(@RequestBody @Valid OwnerTransferForm form) {
        memberService.transferOwnership(form);
        return R.ok();
    }

    /** 主动退出组织（全组织角色可用）；OWNER 须先转让所有权。 */
    @PostMapping("/leave")
    @RequiresPermission("member:self:leave")
    public R<Void> leave() {
        memberService.selfLeave();
        return R.ok();
    }
}
