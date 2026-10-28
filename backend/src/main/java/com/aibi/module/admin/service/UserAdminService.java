package com.aibi.module.admin.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.UserContext;
import com.aibi.module.admin.dto.AdminUserForm;
import com.aibi.module.admin.dto.AdminUserVO;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号管理服务（超管后台）：跨租户的账号 CRUD、重置密码、启停。
 *
 * <p>{@code app_user} 在租户拦截器 IGNORE 名单，故此处按显式 {@code tenant_id} 条件操作，
 * 可列某租户账号或全平台账号。密码仅存 BCrypt 哈希；更新不改 {@code username}/{@code tenantId}/密码
 * （密码走独立的 resetPassword），一律用 {@code lambdaUpdate().set(...)} 精确写列。
 * 安全护栏：不可禁用/删除自己，不可删除最后一个超级管理员。
 */
@Service
public class UserAdminService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 列账号：{@code tenantId} 为空则全平台，否则限该租户。 */
    public List<AdminUserVO> list(Long tenantId) {
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .eq(tenantId != null, User::getTenantId, tenantId)
                        .orderByDesc(User::getId))
                .stream().map(this::toVO).toList();
    }

    public AdminUserVO get(Long id) {
        return toVO(requireUser(id));
    }

    @Transactional
    public AdminUserVO create(AdminUserForm form) {
        if (isBlank(form.password())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新建账号必须设置密码");
        }
        Long dup = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, form.username()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名已存在: " + form.username());
        }
        User user = new User();
        user.setTenantId(form.tenantId());
        user.setUsername(form.username());
        user.setPasswordHash(passwordEncoder.encode(form.password()));
        user.setNickname(form.nickname());
        user.setEmail(form.email());
        user.setRole(isBlank(form.role()) ? "USER" : form.role());
        user.setStatus(form.status() == null ? 1 : form.status());
        userMapper.insert(user);
        return toVO(user);
    }

    /** 更新资料：仅改 nickname/email/role/status，不动 username/tenantId/密码。 */
    @Transactional
    public AdminUserVO update(Long id, AdminUserForm form) {
        User user = requireUser(id);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, id)
                .set(User::getNickname, form.nickname())
                .set(User::getEmail, form.email())
                .set(User::getRole, isBlank(form.role()) ? user.getRole() : form.role())
                .set(User::getStatus, form.status() == null ? user.getStatus() : form.status()));
        return get(id);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        requireUser(id);
        if (isBlank(newPassword)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码不能为空");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, id)
                .set(User::getPasswordHash, passwordEncoder.encode(newPassword)));
    }

    @Transactional
    public AdminUserVO setStatus(Long id, Integer status) {
        User user = requireUser(id);
        if (status != null && status == 0 && id.equals(UserContext.userId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能禁用当前登录账号");
        }
        if (status != null && status == 0 && UserContext.ROLE_SUPER_ADMIN.equals(user.getRole())
                && countSuperAdmins() <= 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能禁用最后一个超级管理员");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, id).set(User::getStatus, status));
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        User user = requireUser(id);
        if (id.equals(UserContext.userId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除当前登录账号");
        }
        if (UserContext.ROLE_SUPER_ADMIN.equals(user.getRole()) && countSuperAdmins() <= 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除最后一个超级管理员");
        }
        userMapper.deleteById(id);
    }

    private long countSuperAdmins() {
        Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserContext.ROLE_SUPER_ADMIN));
        return count == null ? 0 : count;
    }

    private User requireUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "账号不存在: " + id);
        }
        return user;
    }

    private AdminUserVO toVO(User u) {
        return new AdminUserVO(u.getId(), u.getTenantId(), u.getUsername(), u.getNickname(),
                u.getEmail(), u.getRole(), u.getStatus(), u.getCreatedAt());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
