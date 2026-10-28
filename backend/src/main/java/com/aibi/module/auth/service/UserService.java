package com.aibi.module.auth.service;

import com.aibi.module.auth.dto.AuthResponse;
import com.aibi.module.auth.dto.LoginRequest;
import com.aibi.module.auth.dto.RegisterRequest;
import com.aibi.module.auth.dto.UpdateProfileRequest;
import com.aibi.module.auth.dto.UserVO;
import com.aibi.module.auth.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户服务（T1.4）：注册 / 登录 / 资料，替换原演示登录。
 * 四层架构之业务层接口，继承 {@link IService} 获得 MyBatis-Plus 通用 CRUD。
 *
 * <p>注册采用 SaaS 自助开通语义：一次注册 = 新建一个租户（免费套餐 / POOL 隔离）+ 该租户下的首个用户。
 * 密码经 BCrypt 存储；登录成功后由 {@code AccessContextBuilder} 解析出完整访问上下文并签发带
 * {@code prole/trole/team/scope/sid/pv/tv} 的 JWT，同时登记 {@code login_session}；
 * 后续请求由 {@code JwtAuthFilter} 还原上下文（{@code TenantContext} 与 {@code UserContext} 已改为其门面），
 * 实现租户隔离与权限判定。
 *
 * <p><b>说明</b>：{@code app_user} 在租户拦截器 IGNORE 名单，CRUD 不会被自动追加 tenant_id；
 * 故注册时仍手动 {@code setTenantId}，用户级归属由业务显式维护。
 */
public interface UserService extends IService<User> {

    /** 注册：新建租户（FREE/POOL）+ 首个用户，并直接发放令牌。 */
    AuthResponse register(RegisterRequest request);

    /** 登录：校验 BCrypt 密码，加载租户套餐，签发令牌。 */
    AuthResponse login(LoginRequest request);

    /**
     * 登出：吊销当前令牌对应的 {@code login_session} 会话行。
     *
     * <p>无会话可吊（升级前的存量令牌、重复登出）不报错，用户侧结果一致。
     */
    void logout();

    /** 当前登录用户资料。 */
    UserVO currentUser();

    /** 更新当前登录用户的昵称 / 邮箱。 */
    UserVO updateProfile(UpdateProfileRequest request);
}
