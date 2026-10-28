package com.aibi.module.quota.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 每日配额用量 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code quota_usage} 带 {@code tenant_id}，但配额校验需要在“跨租户管理或按显式租户”下做原子增减，
 * 故所有方法用 {@link InterceptorIgnore} 关闭自动注入并显式携带 {@code tenant_id}，避免拦截器改写 upsert 语句。
 */
public interface QuotaUsageMapper {

    /**
     * 原子占用一个当日名额：无记录则插入 {@code used=1}；有记录则在 {@code used < limit} 时自增。
     *
     * <p>借助 {@code INSERT ... ON DUPLICATE KEY UPDATE} 的受影响行数语义判定：{@code 1}=新插入、
     * {@code 2}=已自增、{@code 0}=已达上限（值未变化）。故返回 {@code 0} 即当日配额用尽。
     *
     * @param limit 每日上限（调用方保证 {@code > 0}）
     * @return 受影响行数；{@code 0} 表示被限额拦截
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO quota_usage(tenant_id, usage_date, used) VALUES(#{tenantId}, #{date}, 1)
            ON DUPLICATE KEY UPDATE used = IF(used < #{limit}, used + 1, used)
            """)
    int tryConsume(@Param("tenantId") Long tenantId, @Param("date") LocalDate date, @Param("limit") int limit);

    /** 回滚一个当日名额（累计配额拦截时保持“每日/累计”一致）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE quota_usage SET used = used - 1
            WHERE tenant_id = #{tenantId} AND usage_date = #{date} AND used > 0
            """)
    int decrement(@Param("tenantId") Long tenantId, @Param("date") LocalDate date);

    /** 读取当日已用次数；无记录返回 {@code null}（视为 0）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT used FROM quota_usage WHERE tenant_id = #{tenantId} AND usage_date = #{date}")
    Integer currentUsed(@Param("tenantId") Long tenantId, @Param("date") LocalDate date);
}
