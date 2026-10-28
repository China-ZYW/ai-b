package com.aibi.module.datasource.model;

import com.aibi.module.datasource.DataSourceType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据源连接配置（凭证已解密后的运行时形态）。
 *
 * <p>由 {@code DataSourceService} 从元数据库读取并用 {@code CredentialCipher} 解密密码后构建。
 * <b>password 为明文，仅存在于内存，禁止写入日志或返回给前端。</b>
 *
 * @param id         数据源 id
 * @param tenantId   所属租户（隔离键）
 * @param name       展示名
 * @param type       类型
 * @param host       主机（文件类可空）
 * @param port       端口
 * @param database   库名（SQLite 为文件路径）
 * @param username   账号
 * @param password   明文密码（内存态）
 * @param filePath   文件类数据源路径
 * @param params     额外 JDBC 参数（如 useSSL、serverTimezone）
 * @param sheetNames 文件类数据源选中的工作表名（空/null = 全部）；按名而非下标存，改表顺序不会错选
 * @param sheetMerge 是否将<b>列结构完全一致</b>的工作表合并为一张表（多个月度 sheet 同构时常用）
 * @param extraCatalogs 除主库（{@code database}）外还要一并扫表的额外库/模式名（空/null = 只扫主库）；
 *                   仅数据库类有意义，这些库的表名会带库前缀以支持跨库 join
 */
public record DataSourceConfig(
        Long id,
        Long tenantId,
        String name,
        DataSourceType type,
        String host,
        int port,
        String database,
        String username,
        String password,
        String filePath,
        Map<String, String> params,
        List<String> sheetNames,
        boolean sheetMerge,
        List<String> extraCatalogs
) {

    /** 是否配了跨库联合分析（额外库/模式非空）。 */
    public boolean multiCatalog() {
        return extraCatalogs != null && !extraCatalogs.isEmpty();
    }

    /** 构建 JDBC URL（文件类不走此方法）。 */
    public String buildJdbcUrl() {
        if (type == DataSourceType.SQLITE) {
            return "jdbc:sqlite:" + (database != null ? database : filePath);
        }
        String url = String.format(type.urlTemplate(), host, port, database);
        if (params != null && !params.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            sb.append(url.contains("?") ? "&" : "?");
            // 参数 k/v 必须 URL 编码：值里含 &、=、#、空格等字符会直接破坏 JDBC URL（问题#5）
            params.forEach((k, v) -> {
                String ek = URLEncoder.encode(k, StandardCharsets.UTF_8);
                String ev = URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
                sb.append(ek).append("=").append(ev).append("&");
            });
            sb.setLength(sb.length() - 1); // 去掉末尾多余的 &
            return sb.toString();
        }
        return url;
    }

    /** 供日志安全输出的脱敏标识（不含任何凭证）。 */
    public String safeDescriptor() {
        return type + "://" + host + ":" + port + "/" + database + " (tenant=" + tenantId + ", ds=" + id + ")";
    }
}
