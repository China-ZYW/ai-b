package com.aibi.poc;

import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * SQL 安全护栏（PoC）：只放行单条 SELECT，并强制注入 LIMIT。
 * 使用 JSqlParser 做静态解析，杜绝写操作 / 多语句 / INTO OUTFILE。
 */
public final class SqlGuard {

    private SqlGuard() {
    }

    /**
     * 校验并规范化 SQL。
     *
     * @param sql     LLM 生成的原始 SQL
     * @param maxRows 最大返回行数（强制 LIMIT）
     * @return 规范化后的安全 SELECT 语句
     * @throws IllegalArgumentException 当 SQL 不合法时
     */
    public static String validateAndNormalize(String sql, long maxRows) {
        if (sql == null || sql.isBlank() || sql.trim().equalsIgnoreCase("UNKNOWN")) {
            throw new IllegalArgumentException("SQL 为空或模型无法回答(UNKNOWN)");
        }
        String cleaned = sql.trim();
        // 去掉结尾分号
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        // 拒绝多语句（防注入拼接）
        if (cleaned.contains(";")) {
            throw new IllegalArgumentException("不允许多条语句");
        }
        // 拒绝文件导出类语句
        String upper = cleaned.toUpperCase();
        if (upper.contains("INTO OUTFILE") || upper.contains("INTO DUMPFILE")) {
            throw new IllegalArgumentException("不允许 INTO OUTFILE/DUMPFILE");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL 解析失败: " + e.getMessage(), e);
        }
        // 只允许 SELECT（Insert/Update/Delete/Drop/Alter 等一律拒绝）
        if (!(statement instanceof Select select)) {
            throw new IllegalArgumentException("仅允许 SELECT 查询，实际为: "
                    + statement.getClass().getSimpleName());
        }
        // 强制 LIMIT（对 PlainSelect 生效，覆盖绝大多数问数场景）
        if (select instanceof PlainSelect plain && plain.getLimit() == null) {
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(maxRows));
            plain.setLimit(limit);
        }
        return statement.toString();
    }
}
