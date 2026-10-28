package com.aibi.module.embed.mapper;

import com.aibi.module.embed.entity.ChatEmbed;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 嵌入 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>与 {@code share_link} 同理：管理侧（建/列/删）由租户拦截器按 {@code tenant_id} 自动隔离；
 * 公开访问凭 {@code token} 匿名进行，无租户上下文，故 {@link #selectByToken} / {@link #incrementViewCount}
 * 用 {@link InterceptorIgnore} 关闭自动注入，避免被追加 {@code tenant_id=NULL} 而落空。
 */
public interface ChatEmbedMapper extends BaseMapper<ChatEmbed> {

    /** 按公开令牌查一条嵌入（匿名访问，关闭租户注入）。无则返回 {@code null}。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM chat_embed WHERE token = #{token}")
    ChatEmbed selectByToken(@Param("token") String token);

    /** 公开访问命中后累加访问次数（匿名，关闭租户注入）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE chat_embed SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);
}
