package com.aibi.module.datasource.service.impl;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.SchemaCache;
import com.aibi.module.datasource.TenantDataSourceManager;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.dto.DataSourceRequest;
import com.aibi.module.datasource.dto.DataSourceVO;
import com.aibi.module.datasource.dto.TestConnectionResult;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.mapper.DataSourceMapper;
import com.aibi.module.acl.entity.ResourceAcl;
import com.aibi.module.acl.service.ResourceAclService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.security.CredentialCipher;
import com.aibi.module.datasource.service.DataSourceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入、{@code status} 由 {@code @TableLogic} 自动过滤，故业务方法
 * 不再手写这两处条件；非本租户/已删除的数据源在 {@code getById} 处即返回 {@code null} → 统一抛"不存在或无权访问"。
 * 更新/删除会失效 {@link SchemaCache} 并驱逐 {@link TenantDataSourceManager} 中对应连接池，避免用到旧配置。
 *
 * <p>文件类数据源的工作表选择（{@code sheetNames}/{@code sheetMerge}）与数据库类的跨库配置
 * （{@code extraCatalogs}）均在本类做 JSON 编解码：存库为字符串，运行时（{@code DataSourceConfig}）
 * 与对外（{@code DataSourceVO}）均为已解析的列表。
 */
@Service
public class DataSourceServiceImpl extends ServiceImpl<DataSourceMapper, DataSource>
        implements DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceServiceImpl.class);

    /** {@code sheet_names} / {@code extra_catalogs} 列宽（VARCHAR(1024)）下的写入余量，预留字符集差异。 */
    private static final int NAME_LIST_MAX = 1000;

    private final CredentialCipher cipher;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaCache schemaCache;
    private final TenantDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;
    private final ResourceAclService resourceAclService;
    private final TenantMapper tenantMapper;
    private final DataMaskGate dataMaskGate;

    public DataSourceServiceImpl(CredentialCipher cipher,
                                 DataSourceAdapterRegistry adapterRegistry,
                                 SchemaCache schemaCache,
                                 TenantDataSourceManager dataSourceManager,
                                 ObjectMapper objectMapper,
                                 ResourceAclService resourceAclService,
                                 TenantMapper tenantMapper,
                                 DataMaskGate dataMaskGate) {
        this.cipher = cipher;
        this.adapterRegistry = adapterRegistry;
        this.schemaCache = schemaCache;
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.resourceAclService = resourceAclService;
        this.tenantMapper = tenantMapper;
        this.dataMaskGate = dataMaskGate;
    }

    // ==================== 问数链路用：取已解密的运行时配置 ====================

    @Override
    public DataSourceConfig loadConfig(Long datasourceId) {
        return toConfig(requireEntity(datasourceId));
    }

    // ==================== 管理端：CRUD ====================

    @Override
    public List<DataSourceVO> listViewos() {
        return lambdaQuery().orderByAsc(DataSource::getId).list().stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public DataSourceVO createDataSource(DataSourceRequest request) {
        DataSourceType type = parseType(request.type());
        validateByType(type, request);

        DataSource entity = new DataSource();
        entity.setName(request.name());
        entity.setType(type.name());
        entity.setHost(request.host());
        entity.setPort(resolvePort(type, request));
        entity.setDbName(request.database());
        entity.setUsername(request.username());
        entity.setPasswordCipher(isBlank(request.password()) ? null : cipher.encrypt(request.password()));
        entity.setFilePath(request.filePath());
        entity.setJdbcParams(request.jdbcParams());
        entity.setSheetNames(writeSheetNames(request.sheetNames()));
        entity.setSheetMerge(mergeFlag(request.sheetMerge()));
        entity.setExtraCatalogs(writeExtraCatalogs(request.extraCatalogs()));
        entity.setStatus(1);

        // 数据源数量配额卡控（根除「无限数据源」超卖，见《套餐配额分析》#4）：
        // FREE=1 / PRO=5 / ENTERPRISE=-1(不限)，判定读租户库内 datasource_limit（运营可单独调整）。
        Integer dsLimit = resolveDatasourceLimit();
        if (dsLimit != null && dsLimit >= 0) {
            long used = lambdaQuery().count();   // 自动带 tenant_id + status=1
            if (used >= dsLimit) {
                throw new BizException(ErrorCode.DATASOURCE_LIMIT_EXCEEDED,
                        "数据源数量已达上限（" + dsLimit + " 个）。请删除多余数据源或升级套餐以接入更多数据。");
            }
        }

        save(entity);   // 租户拦截器自动补 tenant_id；IdType.AUTO 回填自增主键

        // 登记资源归属（行级 ACL）：创建者为 owner，组织内全员可见（PRIVATE/TEAM 资源由调用方另行设定）
        // 注意：租户拦截器只改 INSERT SQL、不回写内存实体，故优先用 AccessContext 的当前租户，
        // 避免 entity.tenantId 为 null 导致归属行 tenant_id 空、canAccess 对新建资源一律拒绝。
        Long creator = null;
        Long aclTenantId = entity.getTenantId();
        com.aibi.common.security.AccessContext ac = AccessContextHolder.get();
        if (ac != null) {
            creator = ac.uid();
            if (aclTenantId == null) {
                aclTenantId = ac.tenantId();
            }
        }
        resourceAclService.grant(aclTenantId, "DATASOURCE", entity.getId(),
                creator, null, ResourceAcl.VISIBILITY_ORG);

        log.info("创建数据源: id={}, type={}", entity.getId(), type);
        return toVO(requireEntity(entity.getId()));
    }

    /**
     * 取当前租户的数据源数量上限（库内值）。
     * 租户上下文异常时返回 {@code null}（按"不限"处理，避免误拦正常创建）。
     */
    private Integer resolveDatasourceLimit() {
        try {
            Long tid = TenantContext.requireTenantId();
            Tenant t = tenantMapper.selectById(tid);
            return t != null ? t.getDatasourceLimit() : null;
        } catch (Exception e) {
            log.warn("取数据源配额失败，按不限处理: {}", e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public DataSourceVO updateDataSource(Long id, DataSourceRequest request) {
        DataSource entity = requireEntity(id);   // 存在性 + 租户归属校验（不存在会抛异常）
        DataSourceType type = parseType(request.type());
        validateByType(type, request);

        entity.setName(request.name());
        entity.setType(type.name());
        entity.setHost(request.host());
        entity.setPort(resolvePort(type, request));
        entity.setDbName(request.database());
        entity.setUsername(request.username());
        entity.setFilePath(request.filePath());
        entity.setJdbcParams(request.jdbcParams());
        entity.setSheetNames(writeSheetNames(request.sheetNames()));
        entity.setSheetMerge(mergeFlag(request.sheetMerge()));
        entity.setExtraCatalogs(writeExtraCatalogs(request.extraCatalogs()));
        if (!isBlank(request.password())) {
            entity.setPasswordCipher(cipher.encrypt(request.password()));   // 留空则保持原凭证
        }
        updateById(entity);

        schemaCache.invalidate(entity.getTenantId(), id);
        dataSourceManager.evict(entity.getTenantId(), id);
        evictAdapterCache(type, entity.getTenantId(), id);   // 文件类：删旧 SQLite 临时库，令下次重载最新文件
        log.info("更新数据源: id={}", id);
        return toVO(requireEntity(id));
    }

    @Override
    @Transactional
    public void deleteDataSource(Long id) {
        DataSource entity = requireEntity(id);
        removeById(id);   // @TableLogic：转为 UPDATE status=0（自动带 tenant_id）
        resourceAclService.deleteByResource("DATASOURCE", id);   // 清理资源归属行
        schemaCache.invalidate(entity.getTenantId(), id);
        dataSourceManager.evict(entity.getTenantId(), id);
        evictAdapterCache(parseType(entity.getType()), entity.getTenantId(), id);
        log.info("删除数据源: id={}", id);
    }

    /** 文件类数据源替换/删除后失效适配器缓存（SQLite 临时库），避免下次查询用到旧文件内容（问题3）。 */
    private void evictAdapterCache(DataSourceType type, Long tenantId, Long datasourceId) {
        if (type != null && type.isFile()) {
            try {
                adapterRegistry.get(type).evictCache(tenantId, datasourceId);
            } catch (Exception e) {
                log.warn("失效文件数据源缓存失败: tenant={}, ds={} - {}", tenantId, datasourceId, e.getMessage());
            }
        }
    }

    // ==================== 连通性测试 & schema ====================

    @Override
    public TestConnectionResult testConnection(DataSourceRequest request) {
        DataSourceType type = parseType(request.type());
        int port = request.port() != null ? request.port() : type.defaultPort();
        DataSourceConfig config = new DataSourceConfig(null, TenantContext.requireTenantId(), request.name(), type,
                request.host(), port, request.database(), request.username(), request.password(),
                request.filePath(), parseParams(request.jdbcParams()),
                request.sheetNames(), request.sheetMerge() != null && request.sheetMerge(),
                request.extraCatalogs());
        return doTest(config);
    }

    @Override
    public TestConnectionResult testConnectionById(Long id) {
        return doTest(loadConfig(id));
    }

    @Override
    public List<TableMeta> getSchema(Long id) {
        DataSourceConfig config = loadConfig(id);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> all = schemaCache.getOrLoad(config.tenantId(), id, () -> adapter.scanSchema(config));
        // 表级授权（v2）：schema 面板按当前用户 ACL 省略其被隐藏的表，与问数链路一致
        return dataMaskGate.filterTables(id, all);
    }

    /**
     * 手动触发表结构同步：失效缓存后重扫 JDBC 元数据并回填缓存，再落盘 {@code lastSchemaSyncAt}。
     * 关键：重扫用 {@code adapter.scanSchema} 真实拿到最新表清单，而非返回可能过期的旧缓存。
     */
    @Override
    public List<TableMeta> syncSchema(Long id) {
        DataSource entity = requireEntity(id);   // 存在性 + 租户归属校验
        Long tenantId = entity.getTenantId();
        DataSourceConfig config = toConfig(entity);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        // ① 失效旧缓存 → ② 真实重扫（拿到最新表结构）→ ③ 回填缓存，避免下次问数再扫一次
        schemaCache.invalidate(tenantId, id);
        List<TableMeta> tables = adapter.scanSchema(config);
        schemaCache.getOrLoad(tenantId, id, () -> tables);
        // ④ 落盘同步时间（只更新这一列，防并发把其他字段冲掉）
        entity.setLastSchemaSyncAt(LocalDateTime.now());
        lambdaUpdate().eq(DataSource::getId, id).set(DataSource::getLastSchemaSyncAt, entity.getLastSchemaSyncAt()).update();
        log.info("手动同步数据源表结构: id={}, 表数={}", id, tables.size());
        return tables;
    }

    /**
     * 结构对账专用：直扫当前全量 schema，不过 ACL 过滤、不写缓存、不落盘。
     * 供 {@code SchemaDriftService} 比对下游元数据与实际物理结构，对取数链路零副作用。
     */
    @Override
    public List<TableMeta> scanSchemaRaw(Long id) {
        DataSource entity = requireEntity(id);   // 存在性 + 租户归属校验
        DataSourceConfig config = toConfig(entity);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        return adapter.scanSchema(config);   // 全量直扫，不走 schemaCache、不过 dataMaskGate
    }

    private TestConnectionResult doTest(DataSourceConfig config) {
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        long start = System.currentTimeMillis();
        try {
            adapter.testConnection(config);
            long elapsed = System.currentTimeMillis() - start;
            // 连通后顺手取回可选库清单，供界面做“额外库/模式”候选：跨库联合分析要求库名精确，
            // 让用户自己去找确切库名门槛太高（文件类适配器走默认空实现，无额外开销）
            return new TestConnectionResult(true, "连接成功", elapsed, adapter.listCatalogs(config));
        } catch (BizException e) {
            return new TestConnectionResult(false, e.getMessage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new TestConnectionResult(false, "连接失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    // ==================== 内部工具 ====================

    /** 按 id 取实体（已自动带 tenant_id + status=1），不存在则抛"不存在或无权访问"。 */
    private DataSource requireEntity(Long id) {
        DataSource entity = getById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源不存在或无权访问");
        }
        return entity;
    }

    private Integer resolvePort(DataSourceType type, DataSourceRequest request) {
        if (type.isFile()) {
            return null;
        }
        return request.port() != null ? request.port() : type.defaultPort();
    }

    /** 实体 → 运行时配置（解密密码、解析 JDBC 参数）。 */
    private DataSourceConfig toConfig(DataSource e) {
        return new DataSourceConfig(
                e.getId(),
                e.getTenantId(),
                e.getName(),
                parseType(e.getType()),
                e.getHost(),
                e.getPort() == null ? 0 : e.getPort(),
                e.getDbName(),
                e.getUsername(),
                cipher.decrypt(e.getPasswordCipher()),
                e.getFilePath(),
                parseParams(e.getJdbcParams()),
                parseNameList(e.getSheetNames()),
                e.getSheetMerge() != null && e.getSheetMerge() == 1,
                parseNameList(e.getExtraCatalogs()));
    }

    /** 实体 → 对外视图（一律不含密码或密文，仅暴露 hasPassword 布尔）。 */
    private DataSourceVO toVO(DataSource e) {
        return new DataSourceVO(
                e.getId(),
                e.getName(),
                e.getType(),
                e.getHost(),
                e.getPort(),
                e.getDbName(),
                e.getUsername(),
                e.getFilePath(),
                e.getPasswordCipher() != null,
                parseNameList(e.getSheetNames()),
                e.getSheetMerge() != null && e.getSheetMerge() == 1,
                parseNameList(e.getExtraCatalogs()),
                e.getJdbcParams(),
                e.getLastSchemaSyncAt());
    }

    private DataSourceType parseType(String raw) {
        if (isBlank(raw)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源类型不能为空");
        }
        try {
            return DataSourceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的数据源类型: " + raw);
        }
    }

    private void validateByType(DataSourceType type, DataSourceRequest request) {
        if (type.isObjectStorage()) {
            // 对象存储复用 host=endpoint / database=bucket / username=accessKey / filePath=objectKey
            if (isBlank(request.filePath())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "对象存储数据源需提供 objectKey（filePath）");
            }
            if (isBlank(request.host())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "对象存储数据源需提供 endpoint（host）");
            }
            if (isBlank(request.database())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "对象存储数据源需提供 bucket（database）");
            }
            if (isBlank(request.username())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "对象存储数据源需提供 accessKey（username）");
            }
            if (isBlank(request.password())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "对象存储数据源需提供 secretKey（password）");
            }
        } else if (type.isFile()) {
            if (isBlank(request.filePath())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "文件类数据源需提供 filePath");
            }
        } else if (isBlank(request.host()) || isBlank(request.database())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据库类数据源需提供 host 与 database");
        }
    }

    /** 解析 {@code "k=v&k=v"} 形式的 JDBC 参数。 */
    private Map<String, String> parseParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (isBlank(raw)) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
        return params;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== 名字列表字段的编解码（工作表选择 / 跨库配置） ====================

    /** {@code sheet_merge} 列值：请求未传则归为 0（不合并），与老数据默认一致。 */
    private Integer mergeFlag(Boolean requested) {
        return requested != null && requested ? 1 : 0;
    }

    /** 勾选的工作表名 → JSON 数组串；空/null 存 {@code null} 表示"导入全部"（与老数据同义，不写 {@code []}）。 */
    private String writeSheetNames(List<String> names) {
        return writeNameList(names, "工作表名", "勾选的工作表过多");
    }

    /** 额外库/模式名 → JSON 数组串；空/null 存 {@code null} 表示"只扫主库"。 */
    private String writeExtraCatalogs(List<String> catalogs) {
        return writeNameList(catalogs, "库名", "额外库过多");
    }

    /**
     * 名字列表 → JSON 数组串；空/null 一律存 {@code null}（“空”的具体语义由各字段的读取方解释），
     * 不写 {@code []}；同时去空白、去重，避免同一张表/库被扫两遍。
     *
     * <p>列宽 VARCHAR(1024)：超限时 MySQL 严格模式直接报错、非严格模式静默截断，
     * 后者会让读取时解析失败——故在此先行拦下，给出一句能看懂的提示而不是建源后问数才报错。
     *
     * @param what    名字种类（仅用于序列化失败的提示）
     * @param tooMany 超列宽时的提示前半句
     */
    private String writeNameList(List<String> names, String what, String tooMany) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> cleaned = names.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, what + "无法序列化: " + e.getOriginalMessage());
        }
        if (json.length() > NAME_LIST_MAX) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    tooMany + "（" + cleaned.size() + " 个，名称总长超列宽），请减少数量");
        }
        return json;
    }

    /** JSON 数组串 → 名字列表；空/脏数据一律返回空列表（退化为该字段的“空”语义），不因历史脏数据卡住问数。 */
    private List<String> parseNameList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            List<String> names = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return names == null ? List.of() : names;
        } catch (Exception e) {
            log.warn("名字列表解析失败，按空处理: {} - {}", json, e.getMessage());
            return List.of();
        }
    }
}
