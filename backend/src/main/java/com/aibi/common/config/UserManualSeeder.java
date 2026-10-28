package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台级用户手册播种（T8.1 拆分自 {@link DataSeeder}，全局单例）：
 * 仅当 user_manual 表为空时插入一条已发布版，正文取自 classpath 资源
 * {@code manual/default-user-manual.md}，使开箱即有一份可读手册；
 * 超管日后可在后台改草稿并重新发布。读取资源失败不阻断启动（降级为空正文的已发布档）。
 */
@Component
public class UserManualSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(UserManualSeeder.class);

    private final JdbcTemplate metaJdbc;

    public UserManualSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    @Override
    public String name() {
        return "user-manual";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public void seed() {
        Integer count = metaJdbc.queryForObject("SELECT COUNT(*) FROM user_manual", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String title = "AI 数据助理用户手册";
        String content = "";
        try {
            ClassPathResource res = new ClassPathResource("manual/default-user-manual.md");
            try (java.io.InputStream in = res.getInputStream()) {
                content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("默认用户手册资源读取失败，降级为空正文播种: {}", e.getMessage());
        }
        metaJdbc.update(
                "INSERT INTO user_manual(title, content, published_title, published_content, status, version, published_at) "
                        + "VALUES (?, ?, ?, ?, 'PUBLISHED', 1, NOW())",
                title, content, title, content);
        log.info("已播种用户手册（已发布版，{} 字）", content.length());
    }
}
