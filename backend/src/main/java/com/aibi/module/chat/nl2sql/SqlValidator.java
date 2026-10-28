package com.aibi.module.chat.nl2sql;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/**
 * SQL 安全校验器（数据不出用户环境的静态防护）。
 *
 * <p>仅放行单条 SELECT，拦截写操作、多语句、文件导出。
 * <b>行数上限不在此注入</b>——改由 {@code SqlDialect.applyLimit} 按方言软限制 + JDBC {@code setMaxRows}
 * 硬限制双重兜底，以兼容 MySQL/PG/SQL Server/Oracle/ClickHouse 各不相同的分页语法。
 *
 * <p>校验通过后返回"清理过的原始 SQL"（仅去尾分号），不做 JSqlParser 重渲染，避免破坏各库专有语法。
 * 解析前会把非 ASCII 字符折叠为占位符，以兼容中文等多语言列名（JSqlParser 4.6 不接受未加引号的非 ASCII 标识符）。
 * 注意：JSqlParser 面向 ANSI/MySQL，个别库的专有语法可能解析失败，生产版可按方言放宽或换解析器。
 */
@Component
public class SqlValidator {

    /**
     * 校验 LLM 生成的 SQL 是否为安全的单条 SELECT。
     *
     * @param sql 原始 SQL
     * @return 清理后的安全 SELECT 语句（保留原始方言语法）
     * @throws BizException 当 SQL 不合法时（错误码 SQL_GENERATE_FAILED）
     */
    public String validate(String sql) {
        if (sql == null || sql.isBlank() || sql.trim().equalsIgnoreCase("UNKNOWN")) {
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "无法根据问题生成有效 SQL，请换个问法");
        }
        String cleaned = sql.trim();
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.contains(";")) {
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "不允许多条 SQL 语句");
        }
        String upper = cleaned.toUpperCase();
        if (upper.contains("INTO OUTFILE") || upper.contains("INTO DUMPFILE")) {
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "不允许导出文件类语句");
        }

        Statement statement;
        try {
            // JSqlParser 4.6（版本被 MyBatis-Plus 锁定，不可升级）默认语法不接受未加引号的非 ASCII 标识符，
            // 中文列名（如 SELECT 地区, SUM(销售额) AS 销售总额 FROM data）会解析失败并被误判为非法 SQL，
            // 表现为"数据源能连、能建、能预览，但一问数就无结果"。先把非 ASCII 字符逐位折叠为 ASCII 占位符再解析：
            // SQL 危险关键字与结构符号均为 ASCII，折叠不会掩盖注入或改变语法结构，字符串字面量内的非 ASCII 折叠后仍是合法字符串。
            statement = CCJSqlParserUtil.parse(foldNonAscii(cleaned));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "SQL 解析失败，请换个问法");
        }
        if (!(statement instanceof Select)) {
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "仅允许 SELECT 查询");
        }
        return cleaned;   // 返回未折叠的原始 SQL，保留中文列名与各方言专有语法
    }

    /**
     * 把所有非 ASCII 字符（&gt;= 0x80，如中文列名/字面量）逐位替换为 ASCII 占位符 {@code 'n'}，
     * 仅用于喂给 JSqlParser 做只读结构校验，不参与实际执行。折叠后重复字符不会拼出任何 SQL 关键字，
     * 且 ASCII 的表结构/关键字/运算符完全保留，因此既能兼容多语言标识符，又不降低注入防护强度。
     */
    private static String foldNonAscii(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            sb.append(c < 0x80 ? c : 'n');
        }
        return sb.toString();
    }
}
