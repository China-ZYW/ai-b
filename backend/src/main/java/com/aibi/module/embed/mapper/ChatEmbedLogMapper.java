package com.aibi.module.embed.mapper;

import com.aibi.module.embed.entity.ChatEmbedLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 嵌入对话日志 Mapper（MyBatis-Plus）。
 *
 * <p>查询均显式带 {@code tenant_id} 并关闭租户拦截器（匿名上下文下拦截器可能注入 {@code tenant_id=NULL} 导致落空）；
 * 写入继承 {@code BaseMapper.insert} 即可，由调用方显式 {@code setTenantId} 保证隔离。
 */
@InterceptorIgnore(tenantLine = "true")
public interface ChatEmbedLogMapper extends BaseMapper<ChatEmbedLog> {

    /** 某外部用户最近若干轮（最新在前），用于嵌入页右侧历史面板。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM chat_embed_log WHERE tenant_id = #{tid} AND embed_id = #{embedId} "
            + "AND ext_user = #{extUser} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<ChatEmbedLog> recent(@Param("tid") Long tid, @Param("embedId") Long embedId,
                              @Param("extUser") String extUser, @Param("limit") int limit);

    /** 某次会话的全部轮次（最旧在前），用于点击历史项续接时还原完整对话。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM chat_embed_log WHERE tenant_id = #{tid} AND embed_id = #{embedId} "
            + "AND ext_user = #{extUser} AND conversation_id = #{conversationId} "
            + "ORDER BY created_at ASC, id ASC")
    List<ChatEmbedLog> conversation(@Param("tid") Long tid, @Param("embedId") Long embedId,
                                    @Param("extUser") String extUser, @Param("conversationId") String conversationId);
}
