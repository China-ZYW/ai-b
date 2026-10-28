package com.aibi.module.semantic.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI 全库别名补全 Skill（语义管理）：扫描某数据源的全部表/列，用大模型为其推断<b>中文业务别名</b>，
 * 写回语义层 {@code semantic_meta.alias_cn}（并置 {@code source=AI}）。
 *
 * <p><b>只补不覆盖</b>：仅当现有别名<b>为空或不含汉字</b>时才写入；已经有人工维护的中文别名一律保留。
 * 由此可反复执行、天然幂等——补过的行下轮即被跳过，也不会与「指标自动分析」（只在别名为空时填注释）互相踩踏。
 *
 * <p><b>异步</b>：{@link #completeAsync(Long)} 提交即返回，实际推理在 {@code analysisExecutor} 后台线程执行，
 * 用 {@link TenantContext#wrap} 把租户上下文传播进异步线程（裸 {@code @Async} 会丢失线程局部上下文）。
 * 单次推理按<b>表</b>逐张调用大模型（一表一次），大库耗时较长但不阻塞请求线程。
 *
 * <p><b>刻意无长事务</b>：大模型调用是秒级慢操作，放进数据库事务会长时间占用连接，故逐行独立写入；
 * 中途失败时已写入的行保留，重跑从缺处继续。
 */
@Service
public class AliasCompletionService {

    private static final Logger log = LoggerFactory.getLogger(AliasCompletionService.class);

    static final String TYPE_TABLE = "TABLE";
    static final String TYPE_COLUMN = "COLUMN";
    static final String ROLE_AUTO = "AUTO";
    static final String SRC_AI = "AI";

    /** 单次全库推理最多处理的表数（护栏：避免超大库把大模型调用打爆）。 */
    private static final int MAX_TABLES = 200;
    /** 单表一次提示词里最多列出的字段数（超出部分留待下次执行，保证提示词长度可控）。 */
    private static final int MAX_COLS_PER_TABLE = 80;
    /** 是否已含汉字：含则视为"已有中文别名"，不再覆盖。 */
    private static final Pattern HAS_CN = Pattern.compile("[\\u4e00-\\u9fff]");

    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SemanticMetaMapper semanticMetaMapper;
    private final LlmRouter llmRouter;
    private final ObjectMapper objectMapper;
    /** 与「指标自动分析」共用的 AI 分析线程池（按 bean 名 analysisExecutor 注入）。 */
    private final ThreadPoolTaskExecutor analysisExecutor;

    public AliasCompletionService(DataSourceService dataSourceService,
                                  DataSourceAdapterRegistry adapterRegistry,
                                  SemanticMetaMapper semanticMetaMapper,
                                  LlmRouter llmRouter,
                                  ObjectMapper objectMapper,
                                  ThreadPoolTaskExecutor analysisExecutor) {
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.semanticMetaMapper = semanticMetaMapper;
        this.llmRouter = llmRouter;
        this.objectMapper = objectMapper;
        this.analysisExecutor = analysisExecutor;
    }

    /**
     * 后台异步跑一次全库别名补全：立即返回"已提交"，实际推理在 {@code analysisExecutor} 线程执行。
     * 异常只在异步线程内记录，绝不反压调用方（与「指标自动分析」异步版同范式）。
     */
    public AliasResult completeAsync(Long datasourceId) {
        analysisExecutor.submit(TenantContext.wrap(() -> {
            try {
                doComplete(datasourceId);
            } catch (Exception e) {
                log.error("AI 全库别名补全失败 ds={}: {}", datasourceId, e.getMessage(), e);
            }
        }));
        return new AliasResult(datasourceId, 0, 0, 0, 0, 0, true);
    }

    /** 同步跑一次（供测试/脚本复用），返回补全摘要。 */
    public AliasResult complete(Long datasourceId) {
        return doComplete(datasourceId);
    }

    private AliasResult doComplete(Long datasourceId) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源 id 不能为空");
        }
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> tables = adapter.scanSchema(config);

        // 现有语义行索引：key = 表名 + \u0001 + 列名（表级行的列名为空）
        Map<String, SemanticMeta> index = new HashMap<>();
        for (SemanticMeta r : semanticMetaMapper.selectList(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId))) {
            index.put(key(r.getTableName(), r.getColumnName()), r);
        }

        int tablesSeen = 0;
        int columnsSeen = 0;
        int filled = 0;
        int skipped = 0;
        int tablesFailed = 0;

        for (TableMeta t : tables) {
            if (tablesSeen >= MAX_TABLES) {
                log.warn("全库别名补全达到表数上限 {}，其余表本次跳过 ds={}", MAX_TABLES, datasourceId);
                break;
            }
            tablesSeen++;

            SemanticMeta tableRow = index.get(key(t.name(), null));
            boolean todoTable = needsAlias(tableRow == null ? null : tableRow.getAliasCn());

            // 逐列判定：需要补的收集起来送模型；已有中文别名的直接跳过
            List<TableMeta.ColumnMeta> todoCols = new ArrayList<>();
            for (TableMeta.ColumnMeta c : t.columns()) {
                columnsSeen++;
                SemanticMeta row = index.get(key(t.name(), c.name()));
                if (needsAlias(row == null ? null : row.getAliasCn())) {
                    todoCols.add(c);
                } else {
                    skipped++;
                }
            }
            if (!todoTable && todoCols.isEmpty()) {
                continue;   // 该表无须补全，省掉一次大模型调用
            }
            if (todoCols.size() > MAX_COLS_PER_TABLE) {
                todoCols = todoCols.subList(0, MAX_COLS_PER_TABLE);
            }

            String answer;
            try {
                // 后台异步任务：不受交互延迟约束，走「长思考」模式（见 LlmRouter#chatLongThinking）
                answer = llmRouter.chatLongThinking(buildPrompt(config.name(), t, todoCols));
            } catch (RuntimeException e) {
                tablesFailed++;
                log.warn("别名补全：表 [{}] 大模型调用失败，跳过该表：{}", t.name(), e.getMessage());
                continue;
            }

            JsonNode node;
            try {
                node = objectMapper.readTree(answer);
            } catch (Exception e) {
                tablesFailed++;
                log.warn("别名补全：表 [{}] 返回非 JSON，跳过。原文：{}", t.name(), brief(answer));
                continue;
            }

            if (todoTable) {
                String cn = text(node.path("table"));
                if (cn != null) {
                    fillAlias(index, datasourceId, t.name(), null, TYPE_TABLE, cn);
                    filled++;
                }
            }
            JsonNode cols = node.path("columns");
            for (TableMeta.ColumnMeta c : todoCols) {
                String cn = text(cols.path(c.name()));
                if (cn != null) {
                    fillAlias(index, datasourceId, t.name(), c.name(), TYPE_COLUMN, cn);
                    filled++;
                }
            }
        }

        log.info("AI 全库别名补全完成: ds={} 表={} 列={} 补全={} 跳过(已有中文别名)={} 失败表={}",
                datasourceId, tablesSeen, columnsSeen, filled, skipped, tablesFailed);
        return new AliasResult(datasourceId, tablesSeen, columnsSeen, filled, skipped, tablesFailed, false);
    }

    /**
     * 是否需要补别名：为空 / 空白 / <b>不含汉字</b>（即还不是中文别名）→ 需要；
     * 已有中文别名 → 不需要（绝不覆盖人工维护的别名）。
     */
    private static boolean needsAlias(String alias) {
        return alias == null || alias.isBlank() || !HAS_CN.matcher(alias).find();
    }

    /** 写入别名：行不存在则补建语义行；已存在则只更新 alias_cn/source（用 UpdateWrapper 避免回写旧时间戳）。 */
    private void fillAlias(Map<String, SemanticMeta> index, Long datasourceId,
                           String table, String column, String objectType, String aliasCn) {
        SemanticMeta row = index.get(key(table, column));
        if (row == null) {
            SemanticMeta created = new SemanticMeta();
            created.setDatasourceId(datasourceId);
            created.setObjectType(objectType);
            created.setTableName(table);
            created.setColumnName(column);
            created.setColumnRole(ROLE_AUTO);
            created.setEnabled(1);
            created.setAliasCn(aliasCn);
            created.setSource(SRC_AI);
            semanticMetaMapper.insert(created);
            index.put(key(table, column), created);
            return;
        }
        // 只 SET 需要变的列：updateById 会把读出来的旧 updated_at 一并写回，导致库表 ON UPDATE 不触发
        semanticMetaMapper.update(null, new LambdaUpdateWrapper<SemanticMeta>()
                .eq(SemanticMeta::getId, row.getId())
                .set(SemanticMeta::getAliasCn, aliasCn)
                .set(SemanticMeta::getSource, SRC_AI));
        row.setAliasCn(aliasCn);
        row.setSource(SRC_AI);
    }

    /** 组提示词：给表与（待补的）字段，要求只回 JSON。 */
    private String buildPrompt(String dsName, TableMeta t, List<TableMeta.ColumnMeta> cols) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("你是数据语义建模专家。请为下面的数据库表与字段推断简洁、准确的【中文业务别名】。\n\n");
        sb.append("硬性要求：\n");
        sb.append("1) 只输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码围栏；\n");
        sb.append("2) 别名用简体中文，2~8 个字，不要带「表」「字段」等后缀，不要照抄英文原名；\n");
        sb.append("3) 只能为下面列出的名称生成别名，不得新增或遗漏字段；\n");
        sb.append("4) JSON 结构固定为：{\"table\":\"表的中文别名\",\"columns\":{\"字段名\":\"中文别名\"}}。\n\n");
        sb.append("数据源：").append(dsName == null || dsName.isBlank() ? "未知" : dsName).append("\n");
        sb.append("表：").append(t.name());
        if (t.comment() != null && !t.comment().isBlank()) {
            sb.append("（备注：").append(t.comment()).append("）");
        }
        sb.append("\n");
        if (!cols.isEmpty()) {
            sb.append("待补全的字段：\n");
            for (TableMeta.ColumnMeta c : cols) {
                sb.append("- ").append(c.name()).append(" (").append(c.type()).append(")");
                if (c.comment() != null && !c.comment().isBlank()) {
                    sb.append(" 备注：").append(c.comment());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** 取模型返回的文本值：空 / 缺失返回 null，并去掉模型偶尔包裹的引号与首尾空白。 */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        s = s.replaceAll("^[\"'「『\\s]+", "").replaceAll("[\"'」』\\s]+$", "");
        return s.isEmpty() ? null : s;
    }

    private static String key(String table, String column) {
        return (table == null ? "" : table) + "\u0001" + (column == null ? "" : column);
    }

    /** 日志用的一小段原文（避免把整段模型输出打进日志）。 */
    private static String brief(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    /**
     * 补全结果摘要。{@code submitted=true} 表示已提交后台异步执行，此时各计数为 0。
     *
     * @param tables       扫描的表数
     * @param columns      扫描的列数
     * @param filled       实际写入别名的条数（表级 + 列级）
     * @param skipped      因"已有中文别名"而跳过的列数
     * @param tablesFailed 大模型调用失败或返回非 JSON 的表数
     */
    public record AliasResult(Long datasourceId, int tables, int columns, int filled, int skipped,
                              int tablesFailed, boolean submitted) {
        public AliasResult(Long datasourceId, int tables, int columns, int filled, int skipped, int tablesFailed) {
            this(datasourceId, tables, columns, filled, skipped, tablesFailed, false);
        }
    }
}
