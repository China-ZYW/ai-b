package com.aibi.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.aibi.common.config.SeederSupport.text;

/**
 * 驾驶舱模板库播种（T8.1 拆分自 {@link DataSeeder}，全局共享）：
 * 从 classpath 资源 {@code seed/dashboard-templates.json} 加载，按 code <b>幂等</b>插入——
 * 已存在的编码跳过（不影响存量，也不覆盖超管在 ContentOps 的编辑），使老库重启也能补齐新模板。
 *
 * <p>模板布局为骨架占位槽（metricId=null），实例化时由用户绑定指标；含 {@code kind=infographic}
 * 的槽位携带信息图配置（内容驱动，不取数）。资源读取/解析失败仅告警不阻断启动（降级为空模板库）。
 */
@Component
public class DashboardTemplateSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(DashboardTemplateSeeder.class);

    private final JdbcTemplate metaJdbc;

    public DashboardTemplateSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    @Override
    public String name() {
        return "dashboard-templates";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void seed() {
        JsonNode arr;
        try {
            ClassPathResource res = new ClassPathResource("seed/dashboard-templates.json");
            try (java.io.InputStream in = res.getInputStream()) {
                arr = new ObjectMapper().readTree(in);
            }
        } catch (Exception e) {
            log.warn("驾驶舱模板种子资源读取失败，跳过模板播种: {}", e.getMessage());
            return;
        }
        if (arr == null || !arr.isArray()) {
            log.warn("驾驶舱模板种子资源格式异常（非数组），跳过模板播种");
            return;
        }
        int total = 0;
        for (JsonNode t : arr) {
            String code = text(t, "code");
            if (code == null || code.isBlank()) {
                continue;
            }
            String layoutJson = t.has("layout") && !t.get("layout").isNull() ? t.get("layout").toString() : "[]";
            insertTemplateIfMissing(code, text(t, "name", code), text(t, "category"),
                    text(t, "description"), text(t, "icon", "DataBoard"),
                    text(t, "theme", "dark"), layoutJson);
            total++;
        }
        log.info("已校验/补齐驾驶舱模板库（资源 {} 套，按 code 幂等）", total);
    }

    private void insertTemplate(String code, String name, String category, String description,
                                String icon, String theme, String layoutJson) {
        metaJdbc.update(
                "INSERT INTO dashboard_template(code, name, category, description, icon, theme, layout, builtin) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                code, name, category, description, icon, theme, layoutJson);
        log.info("已播种驾驶舱模板: {} ({})", name, code);
    }

    /** 按 code 幂等插入模板：已存在该编码则跳过（不覆盖超管在 ContentOps 的编辑）。 */
    private void insertTemplateIfMissing(String code, String name, String category, String description,
                                         String icon, String theme, String layoutJson) {
        Integer exist = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM dashboard_template WHERE code = ?", Integer.class, code);
        if (exist != null && exist > 0) {
            return;
        }
        insertTemplate(code, name, category, description, icon, theme, layoutJson);
    }
}
