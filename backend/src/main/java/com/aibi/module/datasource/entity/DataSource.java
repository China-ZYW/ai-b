package com.aibi.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 数据源配置实体（MyBatis-Plus 映射表 {@code data_source}）。
 *
 * <p>{@code passwordCipher} 存 AES-GCM 密文，从不以明文落库；{@code jdbcParams} 为 {@code "k=v&k=v"} 原始串。
 * 运行时形态（凭证已解密、参数已解析）见 {@code DataSourceConfig}，由 {@code DataSourceServiceImpl} 转换。
 *
 * <p>{@code sheetNames} / {@code sheetMerge} 仅文件类数据源使用：前者存用户勾选的工作表名
 * JSON 数组（空=全部），后者控制是否把列结构完全一致的工作表合并为一张表。
 *
 * <p>{@code extraCatalogs} 仅数据库类数据源使用：除主库（{@code dbName}）外还要一并扫表的
 * 额外库/模式名 JSON 数组（空=只扫主库）。同一连接能跨库引用时（MySQL/SQL Server/ClickHouse 等），
 * 这些库的表名会带上库前缀进入提示词，从而支持跨库 join 问数。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入；{@code status} 用 {@link TableLogic} 表达"逻辑删除"
 * （1=启用、0=已删除），MP 会在查询自动追加 {@code status=1}、把删除转为置 0。
 */
@TableName("data_source")
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String type;

    /*
     * 下面标了 ALWAYS 的列都允许被“清空”，必须覆盖 MyBatis-Plus 默认的 NOT_NULL 更新策略：
     * updateDataSource 是“整行按请求覆盖”的语义，而默认策略会跳过值为 null 的字段，
     * 于是“取消全部工作表勾选”“移除全部额外库”“清掉 JDBC 参数”都会静默失效——
     * 界面上看着清了、库里还是旧值，下次问数照旧，而且很难查。
     * passwordCipher 故意不在此列：留空表示保持原凭证，不能被写成 null。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String host;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer port;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dbName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String username;
    private String passwordCipher;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String filePath;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String jdbcParams;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sheetNames;
    private Integer sheetMerge;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String extraCatalogs;

    @TableLogic(value = "1", delval = "0")
    private Integer status;
    private LocalDateTime createdAt;
    /**
     * 最近一次表结构同步时间（手动同步按钮 / 定时同步调度器刷新缓存后写入）。
     * 前端据此提示"表结构可能已过期"，并作为"表增加没同步"的根因修复落点。
     */
    private LocalDateTime lastSchemaSyncAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    public void setPasswordCipher(String passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getJdbcParams() {
        return jdbcParams;
    }

    public void setJdbcParams(String jdbcParams) {
        this.jdbcParams = jdbcParams;
    }

    public String getSheetNames() {
        return sheetNames;
    }

    public void setSheetNames(String sheetNames) {
        this.sheetNames = sheetNames;
    }

    public Integer getSheetMerge() {
        return sheetMerge;
    }

    public void setSheetMerge(Integer sheetMerge) {
        this.sheetMerge = sheetMerge;
    }

    public String getExtraCatalogs() {
        return extraCatalogs;
    }

    public void setExtraCatalogs(String extraCatalogs) {
        this.extraCatalogs = extraCatalogs;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastSchemaSyncAt() {
        return lastSchemaSyncAt;
    }

    public void setLastSchemaSyncAt(LocalDateTime lastSchemaSyncAt) {
        this.lastSchemaSyncAt = lastSchemaSyncAt;
    }
}
