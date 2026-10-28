package com.aibi.module.datasource.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.datasource.dto.DataSourceRequest;
import com.aibi.module.datasource.dto.DataSourceVO;
import com.aibi.module.datasource.dto.FileUploadResult;
import com.aibi.module.datasource.dto.TestConnectionResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.datasource.service.FileStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据源管理接口（T2.1/T2.2/T2.3）：CRUD + 连通性测试 + 表结构查看。
 *
 * <p>全部操作以当前 {@link TenantContext} 的租户为边界；跨租户访问在 {@link DataSourceService} 内被拦截。
 * 凭证只写不读（响应仅返回 {@code hasPassword} 布尔），密码 AES-GCM 加密落库。
 */
@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final FileStorageService fileStorageService;

    public DataSourceController(DataSourceService dataSourceService, FileStorageService fileStorageService) {
        this.dataSourceService = dataSourceService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    @RequiresPermission("datasource:config:read")
    public R<List<DataSourceVO>> list() {
        return R.ok(dataSourceService.listViewos());
    }

    @PostMapping
    @RequiresPermission("datasource:config:write")
    public R<DataSourceVO> create(@RequestBody @Valid DataSourceRequest request) {
        return R.ok(dataSourceService.createDataSource(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission("datasource:config:write")
    public R<DataSourceVO> update(@PathVariable Long id, @RequestBody @Valid DataSourceRequest request) {
        return R.ok(dataSourceService.updateDataSource(id, request));
    }

    @DeleteMapping("/{id}")
    @CheckResourceAccess(type = "DATASOURCE", idParam = "id", permission = "WRITE")
    @RequiresPermission("datasource:config:delete")
    public R<Void> delete(@PathVariable Long id) {
        dataSourceService.deleteDataSource(id);
        return R.ok();
    }

    /** 测试尚未保存的配置（新建向导里"测试连接"）。 */
    @PostMapping("/test")
    @RequiresPermission("datasource:config:test")
    public R<TestConnectionResult> test(@RequestBody @Valid DataSourceRequest request) {
        return R.ok(dataSourceService.testConnection(request));
    }

    /** 测试已保存的数据源。 */
    @PostMapping("/{id}/test")
    @RequiresPermission("datasource:config:test")
    public R<TestConnectionResult> testById(@PathVariable Long id) {
        return R.ok(dataSourceService.testConnectionById(id));
    }

    /** 查看数据源表结构（供前端展示与"常见问题推荐"）。 */
    @GetMapping("/{id}/schema")
    @RequiresPermission("datasource:schema:read")
    public R<List<TableMeta>> schema(@PathVariable Long id) {
        return R.ok(dataSourceService.getSchema(id));
    }

    /**
     * 手动触发表结构同步：失效缓存 → 重扫 JDBC 元数据 → 落盘 {@code lastSchemaSyncAt}。
     * 修复"数据源新增表后系统未同步"：用户在管理页点「同步」后立即拿到最新表清单。
     */
    @PostMapping("/{id}/sync")
    @RequiresPermission("datasource:config:write")
    public R<List<TableMeta>> sync(@PathVariable Long id) {
        return R.ok(dataSourceService.syncSchema(id));
    }

    /**
     * 本地上传文件（问题3）：校验类型/大小 → 存入 {@code {uploadDir}/{租户}/{uuid}.{ext}} → 返回落盘路径与前 N 行预览。
     * 前端拿到 {@code filePath} 后回填到"新建/编辑文件数据源"表单，预览确认无误再保存入库。
     */
    @PostMapping("/upload")
    @RequiresPermission("datasource:file:upload")
    public R<FileUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(fileStorageService.store(file));
    }
}
