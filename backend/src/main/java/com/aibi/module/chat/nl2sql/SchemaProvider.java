package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.SchemaCache;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.dac.DataMaskGate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表结构提供者：带缓存地扫描数据源元数据（缓存见 {@link SchemaCache}，生产版平替 Redis §3.8）。
 *
 * <p>由数据源适配器扫描指定数据源的表结构（走 JDBC {@code DatabaseMetaData}，跨库通用），返回 {@link TableMeta} 列表。
 * 职责单一：只负责"拿到全量表结构"；"相关表裁剪"交给 {@link SchemaLinker}，"渲染为提示词文本"交给 {@link SchemaSlice}。
 */
@Component
public class SchemaProvider {

    private final SchemaCache schemaCache;
    private final DataMaskGate dataMaskGate;

    public SchemaProvider(SchemaCache schemaCache, DataMaskGate dataMaskGate) {
        this.schemaCache = schemaCache;
        this.dataMaskGate = dataMaskGate;
    }

    /** 带缓存地获取某数据源的全量表结构（缓存键含租户 id，不跨租户共享）。 */
    public List<TableMeta> getTables(DataSourceAdapter adapter, DataSourceConfig config) {
        List<TableMeta> all = schemaCache.getOrLoad(config.tenantId(), config.id(), () -> adapter.scanSchema(config));
        // 表级授权（v2）：按当前用户 ACL 在 schema 层省略其被隐藏的表，LLM 看不到 → 不会生成指向隐藏表的 SQL
        return dataMaskGate.filterTables(config.id(), all);
    }
}
