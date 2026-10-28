package com.aibi.module.auth.mapper;

import com.aibi.module.auth.entity.UserIdentity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 自然人身份 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code user_identity} 为全局表（无 {@code tenant_id}），已列入租户拦截器 IGNORE 名单。
 * 登录的第一跳就是本表：先按 email/phone/username 定位自然人并校验口令，再据
 * {@code app_user.identity_id} 列出其在各组织的成员身份（见设计文档 2.3 方案 A）。
 *
 * <p>{@code email}/{@code phone} 各有唯一索引，但 MySQL 唯一索引允许多个 NULL，
 * 故"未填邮箱"的多条身份不会互相冲突；查询时必须先判空再按值查，不能拿 NULL 去等值匹配。
 */
public interface UserIdentityMapper extends BaseMapper<UserIdentity> {

    /** 按邮箱定位自然人（登录/找回密码/定向邀请的主入口）。 */
    @Select("SELECT * FROM user_identity WHERE email = #{email} LIMIT 1")
    UserIdentity selectByEmail(@Param("email") String email);

    /** 按手机号定位自然人（短信通道登录与邀请）。 */
    @Select("SELECT * FROM user_identity WHERE phone = #{phone} LIMIT 1")
    UserIdentity selectByPhone(@Param("phone") String phone);

    /**
     * 按用户名定位自然人：兼容存量登录（{@code app_user.username}），经 {@code identity_id} 反查。
     *
     * <p>存量用户在回填前 {@code identity_id} 为空，本查询取不到行，登录链路须回退到
     * {@code UserMapper.selectByUsername} 的老路径（见 {@code IdentityService}）。
     */
    @Select("""
            SELECT i.* FROM user_identity i
            JOIN app_user u ON u.identity_id = i.id
            WHERE u.username = #{username} LIMIT 1
            """)
    UserIdentity selectByUsername(@Param("username") String username);

    /** 某自然人在各组织的成员身份（多组织选择页；返回空表示尚无组织）。 */
    @Select("""
            SELECT u.id FROM app_user u
            WHERE u.identity_id = #{identityId} AND u.status = 1 ORDER BY u.id ASC
            """)
    List<Long> selectMemberUids(@Param("identityId") Long identityId);
}
