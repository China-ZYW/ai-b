package com.aibi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * AI 数据助理 —— 模块化单体启动类。
 *
 * <p>包结构（见《前后端架构设计文档》3.1 与《SaaS 多租户与数据源接入设计》11）：
 * <pre>
 * com.aibi
 * ├── common/    通用基础设施：response / exception / security / tenant / config
 * └── module/    业务模块：auth / chat（问数）/ datasource（多数据源接入）...
 * </pre>
 */
@SpringBootApplication
@MapperScan({"com.aibi.module.**.mapper", "com.aibi.module.chat.distill"})
public class AiBiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiBiApplication.class, args);
    }

    /**
     * 面向"平台元数据库 aibi_meta"的查询模板（租户、数据源配置、查询历史）。
     *
     * <p>用户业务库不经此模板：由 {@code TenantDataSourceManager} 按 (租户, 数据源) 动态构建只读连接池，
     * 再交由数据源适配器执行（见《SaaS 多租户与数据源接入设计》6）。
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(30);   // 查询超时 30s
        jdbcTemplate.setMaxRows(10000);     // 单次最多 1 万行
        return jdbcTemplate;
    }
}
