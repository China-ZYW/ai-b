package com.aibi.module.chat.lineage;

import com.aibi.common.sql.SqlTableExtractor;
import com.aibi.module.chat.dto.Lineage;
import com.aibi.module.chat.dto.LineageTable;
import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 结果溯源装配器（T10-3）：从实际执行的 SQL 确定性抽出「来源表清单」，再用语义层缓存视图把物理表名
 * 富化成业务名/描述，产出一枚 {@link Lineage} 随 {@code ResultMeta.lineage} 回传前端结果面板。
 *
 * <p><b>纯确定性、零 LLM、零新增 DB 查询</b>：表名抽取复用 {@link SqlTableExtractor}（与表级授权同一份
 * 逻辑，杜绝两处漂移）；语义富化读 {@link SemanticService#view} 的<b>按数据源缓存视图</b>（TTL 与 schema 缓存同）。
 *
 * <p><b>fail-soft 纪律</b>（对齐 ClarifyGate / CostGuard）：本组件运行在问数<b>成功返回之前</b>，
 * 任何异常（语义视图不可用、解析怪异）一律记 warn 后返回 {@code null}，绝不因溯源拖垮主结果返回；
 * SQL 为空或抽不出任何表也返回 {@code null}——前端据 {@code lineage==null} 整体隐藏「数据来源」块。
 * 开关关闭时的短路（零计算）由调用方 {@code ChatService} 依 {@code ASK/lineage-in-result} 提前判定。
 */
@Component
public class LineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(LineageExtractor.class);

    private final SemanticService semanticService;

    public LineageExtractor(SemanticService semanticService) {
        this.semanticService = semanticService;
    }

    /**
     * 抽取并富化来源表。
     *
     * @param datasourceId 数据源 id（用于取该源的语义视图；为空则不富化，只给物理表名）
     * @param sql          实际执行 SQL（{@code result.sql}）
     * @return 溯源信息；SQL 空 / 未解析出任何表 / 内部异常时返回 {@code null}
     */
    public Lineage extract(Long datasourceId, String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            Map<String, SemanticMeta> tableIndex = tableIndex(datasourceId);
            // 按裸表名去重并排序（大小写不敏感、稳定呈现）；同裸名保留限定得更全的那个物理名
            Map<String, LineageTable> dedup = new TreeMap<>();
            for (String raw : SqlTableExtractor.extractTables(sql)) {
                String bare = SqlTableExtractor.bareName(raw);
                if (bare.isEmpty()) {
                    continue;
                }
                LineageTable existed = dedup.get(bare);
                if (existed != null && qualifiedSegments(existed.table()) >= qualifiedSegments(raw)) {
                    continue;   // 已保留的限定名不比新的短，维持不变
                }
                SemanticMeta meta = tableIndex.get(bare);
                String aliasCn = meta == null ? null : trimToNull(meta.getAliasCn());
                String description = meta == null ? null : trimToNull(meta.getDescription());
                dedup.put(bare, new LineageTable(stripQuotes(raw), aliasCn, description));
            }
            if (dedup.isEmpty()) {
                return null;
            }
            return new Lineage(new ArrayList<>(dedup.values()), sql);
        } catch (RuntimeException e) {
            log.warn("结果溯源装配失败（省略 lineage，不影响返回）：{}", e.getMessage());
            return null;
        }
    }

    /** 语义视图的表级索引：裸表名 → 表级语义行（view 只含启用行；无数据源 / 视图空时返回空索引）。 */
    private Map<String, SemanticMeta> tableIndex(Long datasourceId) {
        Map<String, SemanticMeta> index = new HashMap<>();
        if (datasourceId == null) {
            return index;
        }
        SemanticView view = semanticService.view(datasourceId);
        if (view == null || view.tables().isEmpty()) {
            return index;
        }
        for (Map.Entry<String, SemanticMeta> e : view.tables().entrySet()) {
            index.putIfAbsent(SqlTableExtractor.bareName(e.getKey()), e.getValue());
        }
        return index;
    }

    /** 限定名段数（点数 + 1），用于同裸名时优先保留更完整（带库/模式前缀）的物理名。 */
    private static int qualifiedSegments(String name) {
        if (name == null) {
            return 0;
        }
        int dots = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '.') {
                dots++;
            }
        }
        return dots + 1;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 展示用物理名：逐段去掉反引号/双引号/方括号（如 {@code `db`.`sales`} → {@code db.sales}），
     * 保留库/模式点分段——抽取原文常带 LLM 加的标识符引号，直接展示给用户不美观。
     */
    private static String stripQuotes(String name) {
        if (name == null) {
            return null;
        }
        return name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }
}
