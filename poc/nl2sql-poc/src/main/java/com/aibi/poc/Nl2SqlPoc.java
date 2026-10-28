package com.aibi.poc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * P0 阶段 NL2SQL 最小可运行验证程序。
 *
 * <p>流程：读取 MySQL 表结构 → 构建 Prompt → 调 DeepSeek 生成 SQL
 * → JSqlParser 安全校验 → 在用户库只读执行 → 打印结果。
 *
 * <p>运行前置：
 * <ol>
 *   <li>已导入 sql/sample_schema.sql（库名 aibi_demo）</li>
 *   <li>设置环境变量 DEEPSEEK_API_KEY</li>
 *   <li>可选：DB_URL / DB_USER / DB_PASS / DB_SCHEMA</li>
 * </ol>
 *
 * <p>运行：{@code mvn -q compile exec:java -Dexec.args="2026年8月每个地区的已支付订单总金额是多少"}
 */
public class Nl2SqlPoc {

    // ==== 配置（可用环境变量覆盖）====
    private static final String DB_URL = env("DB_URL",
            "jdbc:mysql://localhost:3306/aibi_demo?useSSL=false&serverTimezone=Asia/Shanghai"
                    + "&characterEncoding=utf8&allowPublicKeyRetrieval=true");
    private static final String DB_USER = env("DB_USER", "root");
    private static final String DB_PASS = env("DB_PASS", "root");
    private static final String DB_SCHEMA = env("DB_SCHEMA", "aibi_demo");

    private static final String API_KEY = env("DEEPSEEK_API_KEY", "");
    private static final String BASE_URL = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
    private static final String MODEL = env("DEEPSEEK_MODEL", "deepseek-chat");

    private static final long MAX_ROWS = 1000;
    private static final String DEFAULT_QUESTION = "2026年8月每个地区的已支付订单总金额是多少";

    public static void main(String[] args) {
        if (API_KEY.isBlank()) {
            System.err.println("[错误] 未设置环境变量 DEEPSEEK_API_KEY，无法调用 LLM。");
            System.exit(1);
        }
        String question = args.length > 0 ? String.join(" ", args) : DEFAULT_QUESTION;
        System.out.println("========== NL2SQL PoC ==========");
        System.out.println("问题: " + question);

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            // ① 读取表结构
            String schema = readSchema(conn, DB_SCHEMA);
            System.out.println("\n----- 表结构 -----\n" + schema);

            // ② 构建 Prompt
            String prompt = buildPrompt(schema, question);

            // ③ 调 DeepSeek 生成 SQL
            DeepSeekClient llm = new DeepSeekClient(BASE_URL, API_KEY, MODEL);
            String rawSql = llm.chat(prompt);
            System.out.println("\n----- LLM 原始输出 -----\n" + rawSql);

            // ④ JSqlParser 安全校验 + 强制 LIMIT
            String safeSql = SqlGuard.validateAndNormalize(rawSql, MAX_ROWS);
            System.out.println("\n----- 校验后 SQL -----\n" + safeSql);

            // ⑤ 只读执行并打印
            executeReadOnly(conn, safeSql);
        } catch (IllegalArgumentException e) {
            System.err.println("\n[SQL 校验失败] " + e.getMessage());
        } catch (Exception e) {
            System.err.println("\n[运行失败] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 读取指定库的表结构，输出为便于 LLM 理解的紧凑文本。 */
    private static String readSchema(Connection conn, String schema) throws SQLException {
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? "
                + "ORDER BY TABLE_NAME, ORDINAL_POSITION";
        StringBuilder sb = new StringBuilder();
        String lastTable = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (!table.equals(lastTable)) {
                        if (lastTable != null) {
                            sb.append("\n");
                        }
                        sb.append("表 ").append(table).append(": ");
                        lastTable = table;
                    } else {
                        sb.append(", ");
                    }
                    String col = rs.getString("COLUMN_NAME");
                    String type = rs.getString("COLUMN_TYPE");
                    String comment = rs.getString("COLUMN_COMMENT");
                    sb.append(col).append("(").append(type);
                    if (comment != null && !comment.isBlank()) {
                        sb.append(",").append(comment);
                    }
                    sb.append(")");
                }
            }
        }
        return sb.toString();
    }

    /** 构建 NL2SQL Prompt（强约束：只输出一条 SELECT）。 */
    private static String buildPrompt(String schema, String question) {
        return """
                你是一个 MySQL SQL 专家。请根据下面的表结构，把用户的自然语言问题翻译成一条 MySQL 查询 SQL。
                严格要求：
                1. 只输出一条 SELECT 语句，不要任何解释文字，不要 markdown 代码块。
                2. 只能使用下面列出的表和字段，不要臆造。
                3. "销售额/金额"通常用 orders.amount 或 order_items.price*quantity；涉及聚合必须写 GROUP BY。
                4. 涉及"已支付/有效订单"时用 orders.status='paid'。
                5. 无法回答时只输出 UNKNOWN。

                表结构：
                %s

                用户问题：%s
                SQL：
                """.formatted(schema, question);
    }

    /** 只读执行 SQL 并以简单表格打印结果。 */
    private static void executeReadOnly(Connection conn, String sql) throws SQLException {
        conn.setReadOnly(true);
        long t0 = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(30);
            st.setMaxRows((int) MAX_ROWS);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                List<String> header = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    header.add(md.getColumnLabel(i));
                }
                System.out.println("\n----- 查询结果 -----");
                System.out.println(String.join(" | ", header));
                System.out.println("-".repeat(Math.max(20, String.join(" | ", header).length())));
                int rowCount = 0;
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) {
                        row.add(String.valueOf(rs.getObject(i)));
                    }
                    System.out.println(String.join(" | ", row));
                    rowCount++;
                }
                long ms = System.currentTimeMillis() - t0;
                System.out.println("\n共 %d 行，耗时 %d ms".formatted(rowCount, ms));
            }
        } finally {
            conn.setReadOnly(false);
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
