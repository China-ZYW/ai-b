package com.aibi.module.query.template;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 模板解析器：把 {@code :param} 命名占位符改写为 JDBC 的 {@code ?}，并给出按出现顺序排列的参数名。
 *
 * <p><b>这是本模块的注入红线</b>：参数值<b>永远</b>由 {@code PreparedStatement.setObject} 绑定，
 * 绝不做"字符串替换 + 加引号"的拼接。所以解析器只负责两件事——① 找占位符位置；② 把占位符换成 {@code ?}。
 * 值本身一个字符都不会进入最终 SQL 文本。
 *
 * <p>词法上的三重保障，避免把不该当占位符的东西误改（误改会让 SQL 直接语法错，误判漏改才是安全隐患）：
 * <ol>
 *   <li>跳过单引号字符串（含 {@code ''} 转义）与双引号标识符；</li>
 *   <li>跳过 {@code --} 行注释与块注释标记；</li>
 *   <li>{@code ::}（PostgreSQL 类型转换符）不以占位符处理。</li>
 * </ol>
 *
 * <p>{@code :} 后必须紧跟标识符首字符（字母/下划线/汉字），否则视为普通字符原样保留。
 */
public final class SqlTemplateParser {

    private SqlTemplateParser() {
    }

    /** 解析结果。 */
    public record Parsed(String sql, List<String> paramNames) {
    }

    /**
     * 解析模板。
     *
     * @param template 含 {@code :param} 占位符的 SQL 模板
     * @return 改写后的 SQL（占位符 {@code ?}）与按出现顺序排列的参数名
     * @throws BizException 模板为空 / 只含空白 / 占位符名为空
     */
    public static Parsed parse(String template) {
        if (template == null || template.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SQL 模板不能为空");
        }
        List<String> names = new ArrayList<>();
        StringBuilder out = new StringBuilder(template.length() + 32);
        int n = template.length();
        int i = 0;
        while (i < n) {
            char c = template.charAt(i);
            // 单引号字符串：整段跳过，内部的 : 不当占位符
            if (c == '\'') {
                int end = skipQuoted(template, i, '\'');
                out.append(template, i, Math.min(end + 1, n));
                i = end + 1;
                continue;
            }
            // 双引号标识符（PostgreSQL/标准 SQL）
            if (c == '"') {
                int end = skipQuoted(template, i, '"');
                out.append(template, i, Math.min(end + 1, n));
                i = end + 1;
                continue;
            }
            if (c == '-' && i + 1 < n && template.charAt(i + 1) == '-') {
                int nl = template.indexOf('\n', i);
                int end = nl < 0 ? n : nl;
                out.append(template, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && template.charAt(i + 1) == '*') {
                int end = template.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                out.append(template, i, stop);
                i = stop;
                continue;
            }
            // PostgreSQL 类型转换符 ::（MySQL 兼容无害）：整段原样保留，两个冒号都不当占位符起头
            if (c == ':' && i + 1 < n && template.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
                continue;
            }
            // 命名占位符 :param
            if (c == ':' && i + 1 < n && Character.isLetter(template.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(template.charAt(end))
                        || template.charAt(end) == '_')) {
                    end++;
                }
                String name = template.substring(i + 1, end);
                if (name.isBlank()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "SQL 模板中的占位符名为空");
                }
                names.add(name);
                out.append('?');
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        if (out.length() == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SQL 模板解析后为空");
        }
        return new Parsed(out.toString(), List.copyOf(names));
    }

    /** 只提取参数名（保存模板时用，不改 SQL）。空模板返回空列表而非抛错——保存校验另有分支。 */
    public static List<String> extractParamNames(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        return parse(template).paramNames();
    }

    /** 跳过带引号的片段，返回配对的收尾引号下标（找不到收尾时返回末尾）。 */
    private static int skipQuoted(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && quote == '"') {
                i += 2;
                continue;
            }
            if (c == quote) {
                // '' 是转义的单引号，不算收尾
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return s.length() - 1;
    }
}
