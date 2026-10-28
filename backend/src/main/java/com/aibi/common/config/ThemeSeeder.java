package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 内置主题令牌播种（T8.1 拆分自 {@link DataSeeder}）：6 套基础预设 + 2 套「科技炫酷」追加款，
 * 与前端 {@code dashboardTheme.ts} 同构，使 DB 成为样式唯一事实源。
 * 追加款走 {@link #ensureTheme} 幂等补种，不受"表非空整批跳过"守卫影响，老库升级也能拿到新皮肤。
 */
@Component
public class ThemeSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ThemeSeeder.class);

    private final JdbcTemplate metaJdbc;

    public ThemeSeeder(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    @Override
    public String name() {
        return "themes";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void seed() {
        // 两套均为「科技炫酷」向：背景多层光晕渐变 + 霓虹主色 + L1/L2 装饰令牌（四角标/发光/扫描光/网格/流动）全开。
        ensureTheme("cyber", "赛博霓虹", 1, withDeco(themeTokens(
                "radial-gradient(900px 620px at 12% 0%, rgba(0,229,255,0.20) 0%, transparent 46%), radial-gradient(860px 600px at 88% 100%, rgba(255,45,149,0.18) 0%, transparent 46%), linear-gradient(160deg, #04060f 0%, #0a1024 58%, #04060f 100%)",
                "linear-gradient(90deg, rgba(0,229,255,0.30), rgba(255,45,149,0.16), rgba(0,229,255,0.30))",
                "linear-gradient(160deg, rgba(0,229,255,0.10), rgba(9,14,30,0.66))",
                "rgba(0,229,255,0.32)", "#eafcff", "rgba(178,234,255,0.76)", "rgba(136,198,232,0.56)", "#00e5ff",
                "#00E5FF,#FF2D95,#7C5CFF,#3DDC97,#FFD166,#FF7A6B,#22D3EE",
                "rgba(0,229,255,0.30)", "rgba(178,234,255,0.76)", "rgba(0,229,255,0.14)",
                "rgba(6,12,28,0.94)", "rgba(0,229,255,0.44)", "#eafcff"),
                "tech", true, true, true, true, true));
        ensureTheme("nebula", "星云深空", 1, withDeco(themeTokens(
                "radial-gradient(1000px 680px at 50% -8%, rgba(124,92,255,0.26) 0%, transparent 52%), radial-gradient(760px 620px at 82% 88%, rgba(0,194,255,0.16) 0%, transparent 50%), linear-gradient(170deg, #060a1c 0%, #0d1230 55%, #060a1c 100%)",
                "linear-gradient(90deg, rgba(124,92,255,0.30), rgba(0,194,255,0.14), rgba(124,92,255,0.30))",
                "linear-gradient(160deg, rgba(124,92,255,0.10), rgba(10,16,44,0.66))",
                "rgba(140,150,255,0.30)", "#eef0ff", "rgba(198,206,255,0.76)", "rgba(150,165,225,0.56)", "#8b9cff",
                "#7C5CFF,#00C2FF,#C084FC,#3DDC97,#FBBF24,#FF7AA2,#4ADE80",
                "rgba(150,160,255,0.28)", "rgba(198,206,255,0.76)", "rgba(150,160,255,0.13)",
                "rgba(10,14,36,0.94)", "rgba(150,160,255,0.42)", "#eef0ff"),
                "tech", true, true, true, true, true));
        Integer count = metaJdbc.queryForObject("SELECT COUNT(*) FROM theme_config", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        insertTheme("dark", "深邃蓝", 1, themeTokens(
                "radial-gradient(1200px 700px at 50% -10%, #0b2a7a 0%, #04123a 55%, #020c26 100%)",
                "linear-gradient(90deg, rgba(0,47,167,0.35), rgba(0,194,255,0.12), rgba(0,47,167,0.35))",
                "linear-gradient(160deg, rgba(30,90,232,0.16), rgba(0,22,79,0.42))",
                "rgba(74,144,245,0.28)", "#eaf1ff", "rgba(207,224,255,0.72)", "rgba(154,190,255,0.55)", "#00c2ff",
                "#00C2FF,#4A90F5,#6A5CFF,#16C784,#FF9F43,#FF6B8B,#3DDC97",
                "rgba(120,170,255,0.28)", "rgba(207,224,255,0.72)", "rgba(120,170,255,0.14)",
                "rgba(6,22,60,0.92)", "rgba(90,150,240,0.40)", "#eaf1ff"));
        insertTheme("light", "简洁白", 0, themeTokens(
                "linear-gradient(180deg, #f5f7fc 0%, #eef1f8 100%)",
                "linear-gradient(90deg, rgba(0,47,167,0.06), rgba(0,194,255,0.05), rgba(0,47,167,0.06))",
                "#ffffff", "#e4e8f2", "#141a2e", "#6b7488", "#9aa3b8", "#002FA7",
                "#002FA7,#1E5AE8,#4A90F5,#00C2FF,#6A5CFF,#16C784,#FF9F43",
                "#E4E8F2", "#6B7488", "#EEF1F8", "rgba(255,255,255,0.96)", "#E4E8F2", "#141A2E"));
        insertTheme("tech", "科技青", 1, themeTokens(
                "radial-gradient(1200px 700px at 50% -10%, #06364a 0%, #04222f 55%, #02141c 100%)",
                "linear-gradient(90deg, rgba(0,180,200,0.30), rgba(0,229,208,0.10), rgba(0,180,200,0.30))",
                "linear-gradient(160deg, rgba(0,200,220,0.14), rgba(2,32,44,0.46))",
                "rgba(0,220,230,0.26)", "#e6fbff", "rgba(190,235,245,0.72)", "rgba(150,215,230,0.55)", "#00e5d0",
                "#00E5D0,#00B4FF,#39D3FF,#7CF5C4,#FFD166,#FF7AA2,#4ADE80",
                "rgba(0,220,230,0.26)", "rgba(190,235,245,0.72)", "rgba(0,220,230,0.12)",
                "rgba(3,28,38,0.92)", "rgba(0,210,220,0.40)", "#e6fbff"));
        insertTheme("emerald", "翡翠绿", 1, themeTokens(
                "radial-gradient(1200px 700px at 50% -10%, #064635 0%, #03301f 55%, #01180f 100%)",
                "linear-gradient(90deg, rgba(16,185,129,0.30), rgba(52,211,153,0.10), rgba(16,185,129,0.30))",
                "linear-gradient(160deg, rgba(16,185,129,0.14), rgba(2,40,28,0.46))",
                "rgba(52,211,153,0.26)", "#e7fbf2", "rgba(190,240,220,0.72)", "rgba(150,220,190,0.55)", "#34d399",
                "#34D399,#10B981,#A3E635,#22D3EE,#FBBF24,#F472B6,#4ADE80",
                "rgba(52,211,153,0.26)", "rgba(190,240,220,0.72)", "rgba(52,211,153,0.12)",
                "rgba(2,32,22,0.92)", "rgba(52,211,153,0.40)", "#e7fbf2"));
        insertTheme("sunset", "暮光橙", 1, themeTokens(
                "radial-gradient(1200px 700px at 50% -10%, #5a1e2a 0%, #3a1020 55%, #1c0710 100%)",
                "linear-gradient(90deg, rgba(255,120,80,0.30), rgba(255,180,90,0.10), rgba(255,120,80,0.30))",
                "linear-gradient(160deg, rgba(255,140,90,0.14), rgba(50,16,26,0.48))",
                "rgba(255,150,110,0.26)", "#fff0e9", "rgba(255,220,205,0.72)", "rgba(255,190,170,0.55)", "#ff9f43",
                "#FF9F43,#FF6B8B,#FFD166,#FF7AA2,#FB923C,#F472B6,#FBBF24",
                "rgba(255,150,110,0.26)", "rgba(255,220,205,0.72)", "rgba(255,150,110,0.12)",
                "rgba(44,14,22,0.92)", "rgba(255,150,110,0.40)", "#fff0e9"));
        insertTheme("purple", "暗夜紫", 1, themeTokens(
                "radial-gradient(1200px 700px at 50% -10%, #3a1a6e 0%, #22103f 55%, #120722 100%)",
                "linear-gradient(90deg, rgba(140,90,255,0.32), rgba(200,120,255,0.10), rgba(140,90,255,0.32))",
                "linear-gradient(160deg, rgba(140,90,255,0.16), rgba(38,16,72,0.48))",
                "rgba(170,130,255,0.28)", "#f1eaff", "rgba(225,210,255,0.72)", "rgba(195,175,255,0.55)", "#a855f7",
                "#A855F7,#6A5CFF,#EC4899,#22D3EE,#F472B6,#818CF8,#C084FC",
                "rgba(170,130,255,0.26)", "rgba(225,210,255,0.72)", "rgba(170,130,255,0.12)",
                "rgba(28,14,52,0.92)", "rgba(170,130,255,0.40)", "#f1eaff"));
    }

    private void insertTheme(String code, String name, int dark, String tokens) {
        metaJdbc.update(
                "INSERT INTO theme_config(tenant_id, code, name, dark, tokens, builtin) VALUES (NULL, ?, ?, ?, ?, 1)",
                code, name, dark, tokens);
        log.info("已播种内置主题: {} ({})", name, code);
    }

    // 幂等补种单个内置主题令牌：按 (全局租户 NULL, code) 判存，版本升级追加新皮肤时不会重复插入。
    private void ensureTheme(String code, String name, int dark, String tokens) {
        Integer exist = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM theme_config WHERE tenant_id IS NULL AND code = ?", Integer.class, code);
        if (exist != null && exist > 0) {
            return;
        }
        insertTheme(code, name, dark, tokens);
    }

    // 在基础主题令牌 JSON 上叠加「装饰 / 动效」开关（L1 与 L2 的炫酷令牌），不改动原有 15 个基础令牌字段。
    private String withDeco(String tokens, String deco, boolean glow, boolean corner, boolean scan,
                            boolean grid, boolean flow) {
        return tokens.substring(0, tokens.length() - 1)
                + ",\"deco\":\"" + deco + "\""
                + ",\"glow\":" + glow
                + ",\"corner\":" + corner
                + ",\"scan\":" + scan
                + ",\"grid\":" + grid
                + ",\"flow\":" + flow
                + "}";
    }

    /** 构造与前端 DashboardTheme 同构的 tokens JSON（paletteCsv 为逗号分隔的图表色板）。 */
    private String themeTokens(String screenBg, String headerBg, String cardBg, String cardBorder,
                               String textPrimary, String textSecondary, String textMuted, String accent,
                               String paletteCsv, String axisLine, String axisLabel, String splitLine,
                               String tooltipBg, String tooltipBorder, String tooltipText) {
        StringBuilder palette = new StringBuilder("[");
        String[] cols = paletteCsv.split(",");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                palette.append(",");
            }
            palette.append('"').append(cols[i].trim()).append('"');
        }
        palette.append("]");
        return "{\"screenBg\":\"" + screenBg + "\""
                + ",\"headerBg\":\"" + headerBg + "\""
                + ",\"cardBg\":\"" + cardBg + "\""
                + ",\"cardBorder\":\"" + cardBorder + "\""
                + ",\"textPrimary\":\"" + textPrimary + "\""
                + ",\"textSecondary\":\"" + textSecondary + "\""
                + ",\"textMuted\":\"" + textMuted + "\""
                + ",\"accent\":\"" + accent + "\""
                + ",\"chartPalette\":" + palette
                + ",\"axisLine\":\"" + axisLine + "\""
                + ",\"axisLabel\":\"" + axisLabel + "\""
                + ",\"splitLine\":\"" + splitLine + "\""
                + ",\"tooltipBg\":\"" + tooltipBg + "\""
                + ",\"tooltipBorder\":\"" + tooltipBorder + "\""
                + ",\"tooltipText\":\"" + tooltipText + "\"}";
    }
}
