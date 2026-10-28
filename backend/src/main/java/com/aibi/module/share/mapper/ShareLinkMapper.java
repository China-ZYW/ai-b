package com.aibi.module.share.mapper;

import com.aibi.module.share.entity.ShareLink;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分享链接 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>{@code share_link} 不在租户拦截器 IGNORE 名单：创建者侧（建/列/删）自动按 {@code tenant_id} 隔离。
 * 但<b>公开访问</b>凭 {@code token}（能力令牌）匿名只读，此时无租户上下文，故 {@link #selectByToken} 与
 * {@link #incrementViewCount} 用 {@link InterceptorIgnore} 关闭自动注入，避免被追加 {@code tenant_id=NULL} 而落空。
 */
public interface ShareLinkMapper extends BaseMapper<ShareLink> {

    /** 按公开令牌查一条分享（匿名访问，关闭租户注入）。无则返回 {@code null}。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM share_link WHERE token = #{token}")
    ShareLink selectByToken(@Param("token") String token);

    /** 公开访问命中后累加访问次数（匿名，关闭租户注入）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE share_link SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);
}
