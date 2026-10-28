package com.aibi.module.datasource.service;

import com.aibi.module.datasource.dto.DataSourceRequest;
import com.aibi.module.datasource.dto.DataSourceVO;
import com.aibi.module.datasource.dto.TestConnectionResult;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 数据源服务：租户内数据源 CRUD、连通性测试、schema 查看，以及为问数链路提供已解密的运行时配置。
 * 四层架构之业务层接口，继承 {@link IService} 获得 MyBatis-Plus 通用 CRUD。
 *
 * <p><b>隔离保证</b>：{@code tenant_id} 由租户拦截器自动注入，跨租户访问一律视为"不存在"（抛
 * {@code DATASOURCE_NOT_FOUND}），既不返回也不泄露其他租户数据源是否存在。密码经 {@code CredentialCipher}
 * AES-GCM 加密落库；{@link #loadConfig} 返回的明文密码仅存内存，禁止外泄。
 */
public interface DataSourceService extends IService<DataSource> {

    /** 取当前租户下指定数据源的运行时配置（含解密后的明文密码，仅内存态）。 */
    DataSourceConfig loadConfig(Long datasourceId);

    /** 列出当前租户的全部数据源（不含凭证）。 */
    List<DataSourceVO> listViewos();

    /** 新建数据源，返回其视图对象。 */
    DataSourceVO createDataSource(DataSourceRequest request);

    /** 更新数据源（password 留空则保持原凭证）；失效缓存并驱逐连接池。 */
    DataSourceVO updateDataSource(Long id, DataSourceRequest request);

    /** 逻辑删除数据源（status=0）；失效缓存并驱逐连接池。 */
    void deleteDataSource(Long id);

    /** 测试一个"尚未保存"的数据源配置（前端新建时先测通再存）。 */
    TestConnectionResult testConnection(DataSourceRequest request);

    /** 测试一个已保存的数据源。 */
    TestConnectionResult testConnectionById(Long id);

    /** 查看数据源表结构（走缓存）。 */
    List<TableMeta> getSchema(Long id);

    /**
     * 手动触发表结构同步：失效缓存 → 重扫 JDBC 元数据（刷新缓存）→ 落盘 {@code lastSchemaSyncAt}。
     * 修复"数据源新增表后系统未同步"：用户在管理端点「同步」按钮后立即拿到最新表清单。
     */
    List<TableMeta> syncSchema(Long id);

    /**
     * 结构对账专用：直扫数据源当前全量表结构（表+列），<b>不经过 ACL 过滤、不写缓存、不落盘</b>，
     * 用于检测下游元数据（语义层/关联/权限/图表/指标）与实际物理结构之间的漂移。
     * 与 {@link #syncSchema} 的区别：后者会刷新缓存并落盘同步时间，本方法纯只读、对取数链路零副作用。
     */
    List<TableMeta> scanSchemaRaw(Long id);
}
