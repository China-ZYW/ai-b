package com.aibi.module.query.completion;

import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置结构补全源（P2）：基于数据源物理 schema + 语义层中文别名，为 SQL 编辑器提供表名/列名/关键字补全。
 *
 * <p><b>三条来源合并</b>（顺序即优先级）：① 物理列名（编辑器必须写出引擎认得的名字）；
 * ② 语义层中文别名（用户看到"下单时间"，插入的仍是 {@code create_time}，绝不 inserting 一个引擎不认的串）；
 * ③ SQL 关键字。
 *
 * <p><b>尊重 ACL</b>：表清单先经 {@link DataMaskGate#filterTables} 过滤，被隐藏的表与其列不会出现。
 */
@Component
public class SchemaCompletionProvider implements CompletionProvider {

    /** SQL 关键字表：补全框里最常见的输入，命中即返回。 */
    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT",
            "JOIN", "LEFT JOIN", "INNER JOIN", "ON", "AS", "AND", "OR", "NOT",
            "IN", "BETWEEN", "LIKE", "IS NULL", "IS NOT NULL", "DISTINCT",
            "COUNT(*)", "SUM", "AVG", "MIN", "MAX", "CASE", "WHEN", "THEN", "ELSE", "END",
            "UNION ALL", "WITH"
    };

    private final DataSourceService dataSourceService;
    private final DataMaskGate dataMaskGate;
    private final SemanticMetaMapper semanticMetaMapper;

    public SchemaCompletionProvider(DataSourceService dataSourceService,
                                    DataMaskGate dataMaskGate,
                                    SemanticMetaMapper semanticMetaMapper) {
        this.dataSourceService = dataSourceService;
        this.dataMaskGate = dataMaskGate;
        this.semanticMetaMapper = semanticMetaMapper;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean supports(CompletionRequest request) {
        return request != null && request.datasourceId() != null;
    }

    @Override
    public List<CompletionItem> complete(CompletionRequest request) {
        List<TableMeta> tables = visibleTables(request.datasourceId());
        String prefix = request.prefix().trim().toLowerCase(Locale.ROOT);
        boolean emptyPrefix = prefix.isEmpty();

        List<CompletionItem> out = new ArrayList<>();
        // ① 指定表 → 优先给该表的列（用户多半已经写好了 FROM 子句）
        String scope = request.tableName();
        if (scope != null && !scope.isBlank()) {
            appendColumns(out, tables, scope.trim(), prefix, request.limit());
        } else {
            // ② 未指定表 → 给表名
            appendTables(out, tables, prefix, request.limit());
        }
        // ③ 语义层中文别名（与上面的物理名互补，不冲突时一并给出）
        appendAliases(out, request, scope, prefix);
        // ④ 关键字放最后（常见前缀下它最没信息量）
        if (emptyPrefix || matches(prefix, "s") || matches(prefix, "fr") || matches(prefix, "w")) {
            for (String kw : KEYWORDS) {
                if (emptyPrefix || matches(prefix, kw.toLowerCase(Locale.ROOT))) {
                    out.add(new CompletionItem(kw, kw, "关键字", "KEYWORD"));
                }
            }
        }
        return out;
    }

    private void appendTables(List<CompletionItem> out, List<TableMeta> tables,
                              String prefix, int limit) {
        for (TableMeta t : tables) {
            if (out.size() >= limit) {
                return;
            }
            if (!prefix.isEmpty() && !t.name().toLowerCase(Locale.ROOT).contains(prefix)) {
                continue;
            }
            out.add(new CompletionItem(t.name(), t.name(),
                    "表" + (t.comment() == null || t.comment().isBlank() ? "" : " · " + t.comment()), "TABLE"));
        }
    }

    private void appendColumns(List<CompletionItem> out, List<TableMeta> tables,
                               String tableName, String prefix, int limit) {
        for (TableMeta t : tables) {
            if (!sameTable(t.name(), tableName)) {
                continue;
            }
            for (TableMeta.ColumnMeta c : t.columns()) {
                if (out.size() >= limit) {
                    return;
                }
                if (!prefix.isEmpty() && !c.name().toLowerCase(Locale.ROOT).contains(prefix)) {
                    continue;
                }
                out.add(new CompletionItem(c.name(), c.name(),
                        c.type() + (c.comment() == null || c.comment().isBlank() ? "" : " · " + c.comment()),
                        "COLUMN"));
            }
            return;
        }
    }

    /**
     * 语义层别名：用户看到中文，插入原列名。
     *
     * <p>刻意<b>只匹配中文别名</b>，不反查"输入中文"——补全框的语义是"接着已写的字符往前补"，
     * 用户刚敲下"下"时通常还想写中文注释而非列名；且中文匹配后插入英文列名会造成视觉跳变。
     * 中文命中后返回的是<b>物理列名</b>，保证写进编辑器的语句引擎认得。
     */
    private void appendAliases(List<CompletionItem> out, CompletionRequest request,
                               String scope, String prefix) {
        if (request.datasourceId() == null) {
            return;
        }
        Set<String> texts = new LinkedHashSet<>();
        List<SemanticMeta> rows;
        try {
            rows = semanticMetaMapper.selectList(
                    new LambdaQueryWrapper<SemanticMeta>()
                            .eq(SemanticMeta::getDatasourceId, request.datasourceId())
                            .eq(SemanticMeta::getEnabled, 1));
        } catch (RuntimeException e) {
            // 语义层不可用时静默跳过：补全不该因为一张表读失败就整体失效
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (SemanticMeta m : rows) {
            String cn = m.getAliasCn();
            if (cn == null || cn.isBlank() || !hasCn(cn)) {
                continue;
            }
            if (scope != null && !scope.isBlank() && !sameTable(m.getTableName(), scope.trim())) {
                continue;
            }
            // 中文前缀匹配；物理列名也可能正好以该前缀开头（此时物理列已在 ①/② 里，靠去重消除）
            if (!prefix.isEmpty() && !cn.toLowerCase(Locale.ROOT).contains(prefix)) {
                continue;
            }
            if (m.getColumnName() == null || m.getColumnName().isBlank()) {
                continue;
            }
            boolean exist = texts.stream().anyMatch(t -> t.equalsIgnoreCase(m.getColumnName()));
            if (exist) {
                continue;
            }
            texts.add(m.getColumnName());
            if (out.size() >= request.limit()) {
                return;
            }
            out.add(new CompletionItem(m.getColumnName(), cn, "语义别名", "COLUMN"));
        }
    }

    private List<TableMeta> visibleTables(Long datasourceId) {
        List<TableMeta> all = dataSourceService.getSchema(datasourceId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<TableMeta> visible = dataMaskGate.filterTables(datasourceId, all);
        return visible == null ? List.of() : visible;
    }

    /** 表名比较：忽略大小写与库前缀（{@code mydb.orders} 与 {@code orders} 视为同一张表）。 */
    private boolean sameTable(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
        String y = b.contains(".") ? b.substring(b.lastIndexOf('.') + 1) : b;
        return x.equalsIgnoreCase(y);
    }

    private boolean matches(String prefix, String target) {
        return prefix.isEmpty() || target.startsWith(prefix);
    }

    private boolean hasCn(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }
}
