package com.aibi.module.query.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.nl2sql.JoinGraph;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.chat.nl2sql.TableRelation;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.query.builder.SqlBuilder;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.semantic.service.TableRelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化查询构造器服务（P1 收尾）：把"勾选出来的查询"翻译成确定性的 SQL，并走与编辑器同一条执行链。
 *
 * <p><b>与手写 SQL 编辑器的关系</b>：构造器不是第二条通道，而是<b>同一个内核的另一种输入方式</b>——
 * 生成的 SQL 同样要经过 {@code SqlValidator}（只读单条）、表级 ACL 拦截、系统库拦截，
 * 执行与脱敏也走 {@link QueryService} 的同一段代码。差别只在"标识符是谁选的"：
 * 编辑器里是用户手打，构造器里是<b>用户从服务端清单里挑</b>，因此生成器不需要信任任何文本。
 *
 * <p><b>关联推荐的口径</b>与问数链路完全一致：{@link JoinGraph} 把人工登记（{@code table_relation}）
 * 与库中外键、命名推断三路合并，同一对连接键只保留最可信的一条；前端拿到的清单里，
 * 连接列与方向都由服务端定，<b>只能选、不能改</b>。
 */
@Service
public class QueryBuilderService {

    private static final Logger log = LoggerFactory.getLogger(QueryBuilderService.class);

    private final DataSourceService dataSourceService;
    private final TableRelationService tableRelationService;
    private final SqlValidator sqlValidator;
    private final QueryService queryService;

    public QueryBuilderService(DataSourceService dataSourceService,
                               TableRelationService tableRelationService,
                               SqlValidator sqlValidator,
                               QueryService queryService) {
        this.dataSourceService = dataSourceService;
        this.tableRelationService = tableRelationService;
        this.sqlValidator = sqlValidator;
        this.queryService = queryService;
    }

    // ==================== 关联推荐 ====================

    /**
     * 某张表的可用关联（供构造器"添加维表"下拉）。
     *
     * @param table 主表名（可带「库.」前缀，匹配对限定前缀与大小写不敏感）
     * @return 该表参与的关联；顺序沿用 {@link JoinGraph} 的可信度顺序（人工登记 → 外键 → 推断）
     */
    public List<QueryDtos.BuilderRelationVO> relationsOf(Long datasourceId, String table) {
        if (datasourceId == null || !hasText(table)) {
            return List.of();
        }
        List<TableMeta> tables = schema(datasourceId);
        if (tables.size() < 2) {
            return List.of();
        }
        String key = shortName(table);
        List<TableRelation> all = JoinGraph.relations(tables, true, tableRelationService.relationsOf(datasourceId));
        List<QueryDtos.BuilderRelationVO> out = new ArrayList<>();
        long id = 1;
        for (TableRelation r : all) {
            if (r == null || out.size() >= 50) {
                continue;
            }
            String lt = shortName(r.leftTable());
            String rt = shortName(r.rightTable());
            if (!lt.equalsIgnoreCase(key) && !rt.equalsIgnoreCase(key)) {
                continue;   // 与本表无关
            }
            out.add(new QueryDtos.BuilderRelationVO(id++, r.pairKey(),
                    lt, r.leftColumn(), rt, r.rightColumn(),
                    r.source() == null ? null : r.source().name(),
                    r.safeCardinality() == null ? null : r.safeCardinality().name()));
        }
        return out;
    }

    // ==================== 生成 ====================

    /** 只生成 SQL、不执行（构造器"预览"用，便于用户确认再跑）。 */
    public QueryDtos.BuilderSqlVO preview(Long datasourceId, QueryDtos.BuilderSpec spec) {
        SqlBuilder.Built built = build(datasourceId, spec);
        return new QueryDtos.BuilderSqlVO(built.sql(), built.params().size());
    }

    /** 生成并执行（走 {@link QueryService#runPrepared}，与编辑器同源）。 */
    public QueryDtos.QueryRunVO run(Long datasourceId, QueryDtos.BuilderSpec spec,
                                     Long maxRows, String chartType) {
        SqlBuilder.Built built = build(datasourceId, spec);
        log.info("构造器执行：ds={} sql={} params={}", datasourceId, built.sql(), built.params().size());
        String specJson = null;
        try {
            specJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(spec);
        } catch (Exception e) {
            log.warn("构造器规格序列化失败（历史将不记录 spec）：{}", e.getMessage());
        }
        return queryService.runPrepared(datasourceId, built.sql(), built.params(), maxRows, chartType, specJson);
    }

    // ==================== 内部 ====================

    /** 生成 + 过只读校验（校验放在生成之后，与手工 SQL 的顺序一致：先生成、再拒绝）。 */
    private SqlBuilder.Built build(Long datasourceId, QueryDtos.BuilderSpec spec) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先选择数据源");
        }
        // 数据源归属按"不存在"处理，不泄露其他租户数据源是否存在
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        Long current = TenantContext.tenantId();
        if (current != null && config.tenantId() != null && !current.equals(config.tenantId())) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源不存在或无权访问");
        }
        List<TableMeta> tables = schema(datasourceId);
        List<TableRelation> relations = JoinGraph.relations(tables, true,
                tableRelationService.relationsOf(datasourceId));
        SqlBuilder.Built built = SqlBuilder.build(spec, tables, relations);
        // 第二道闸：生成器产出的也必须是单条只读
        sqlValidator.validate(built.sql());
        return built;
    }

    private List<TableMeta> schema(Long datasourceId) {
        List<TableMeta> tables = dataSourceService.getSchema(datasourceId);
        return tables == null ? List.of() : tables;
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
