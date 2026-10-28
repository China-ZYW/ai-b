package com.aibi.module.mcp.mapper;

import com.aibi.module.mcp.entity.McpPat;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

/**
 * MCP 个人访问令牌 Mapper（MyBatis-Plus）。
 *
 * <p>管理侧（建/列/吊销）走租户拦截器自动隔离；运行期校验（{@code POST /mcp}）在<b>匿名</b>上下文执行
 * ——此刻 {@code AccessContextHolder} 尚无租户，租户拦截器会注入 {@code tenant_id=NULL} 导致查空，
 * 故 {@link #byHash} 显式关闭租户行并只按哈希定位（哈希本身已是 256 位随机值，无法被猜）。
 */
public interface McpPatMapper extends BaseMapper<McpPat> {

    /**
     * 按令牌哈希定位（跨租户：令牌明文本身即凭据，且哈希不可枚举）。
     */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM mcp_pat WHERE token_hash = #{hash}")
    McpPat byHash(@Param("hash") String hash);

    /** 某租户下的全部令牌（管理侧列表，按创建倒序）。 */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM mcp_pat WHERE tenant_id = #{tid} ORDER BY id DESC")
    List<McpPat> byTenant(@Param("tid") Long tid);

    /**
     * 刷新最近使用时间（只按 id 定位）。
     *
     * <p>★必须关闭租户注入：{@code touchUsed} 在鉴权阶段（{@code TenantContext} 尚未设置）调用，
     * 走 BaseMapper 泛型 update 会被拦截器追加 {@code AND tenant_id=NULL} → 永远 {@code Updates: 0}、
     * {@code last_used_at} 永远记不上（09-24 实测）。
     */
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE mcp_pat SET last_used_at = #{now} WHERE id = #{id}")
    int touchUsedRaw(@Param("id") Long id, @Param("now") LocalDateTime now);
}
