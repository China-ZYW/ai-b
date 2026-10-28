package com.aibi.module.dashboard.mapper;

import com.aibi.module.dashboard.entity.Dashboard;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 驾驶舱 Mapper（由 {@code @MapperScan} 扫描注册）。{@code dashboard} 为租户隔离表，
 * CRUD 由租户拦截器自动注入 {@code tenant_id}，天然按当前租户 + 上下文隔离。
 *
 * <p>例外：{@link #selectByShareToken} 供大屏<b>公开分享</b>匿名访问，此时无租户上下文，故用
 * {@link InterceptorIgnore} 关闭自动注入，避免被追加 {@code tenant_id=NULL} 而落空（能力令牌模式，同 {@code ShareLinkMapper}）。
 */
public interface DashboardMapper extends BaseMapper<Dashboard> {

    /** 按公开分享令牌查一个大屏（匿名访问，关闭租户注入）。无则返回 {@code null}。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM dashboard WHERE share_token = #{token}")
    Dashboard selectByShareToken(@Param("token") String token);
}
