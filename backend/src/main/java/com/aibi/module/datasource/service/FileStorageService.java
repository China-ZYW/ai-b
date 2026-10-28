package com.aibi.module.datasource.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.adapter.FileTableReader;
import com.aibi.module.datasource.dto.FileSheetPreview;
import com.aibi.module.datasource.dto.FileUploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件类数据源本地存储服务（问题3）。
 *
 * <p>把上传文件落到 {@code {aibi.upload.dir}/{租户}/{uuid}.{ext}}：按租户分子目录隔离、uuid 重命名杜绝覆盖，
 * 并对最终路径做 {@code startsWith(uploadDir)} 校验以杜绝路径穿越；落盘后立即用 {@link FileTableReader}
 * 读出每张工作表的前 N 行做预览，连同最终路径与工作表清单一并返回，供前端勾选导入范围后再入库。
 *
 * <p>护栏：空文件、超 {@code max-size-mb}、非 {@code allowed-extensions} 白名单一律拦下；预览解析失败、
 * 或整份工作簿找不到一个带表头的工作表时，都会清掉刚落盘的孤儿文件。支持的类型：CSV、Excel(.xlsx/.xls)、
 * Parquet(.parquet)——后两者分别映射为 {@link DataSourceType#CSV}/{@link DataSourceType#EXCEL}/
 * {@link DataSourceType#PARQUET}，由 {@link FileTableReader} 按扩展名选择解析器。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 按扩展名映射数据源类型；不在已知集合里的扩展名一律归为 Excel（兼容旧逻辑）。 */
    private static final Set<String> CSV_EXT = Set.of("csv");
    private static final Set<String> PARQUET_EXT = Set.of("parquet");

    private final FileTableReader reader;
    private final Path uploadDir;
    private final long maxSizeBytes;
    private final int previewRows;
    private final List<String> allowedExtensions;

    public FileStorageService(FileTableReader reader,
                              @Value("${aibi.upload.dir:./data/uploads}") String dir,
                              @Value("${aibi.upload.max-size-mb:20}") long maxSizeMb,
                              @Value("${aibi.upload.preview-rows:20}") int previewRows,
                              @Value("${aibi.upload.allowed-extensions:csv,xlsx,xls,parquet}") String allowedExtensions) {
        this.reader = reader;
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
        this.previewRows = previewRows;
        this.allowedExtensions = Arrays.stream(allowedExtensions.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 校验并落盘上传文件，返回落盘路径 + 预览。 */
    public FileUploadResult store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "上传文件为空");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文件大小超过限制（最大 " + (maxSizeBytes / 1024 / 1024) + "MB）");
        }
        Long tenantId = TenantContext.requireTenantId();

        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!allowedExtensions.contains(ext)) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型，仅允许: " + String.join("/", allowedExtensions));
        }
        DataSourceType type = CSV_EXT.contains(ext) ? DataSourceType.CSV
                : PARQUET_EXT.contains(ext) ? DataSourceType.PARQUET : DataSourceType.EXCEL;

        try {
            Path tenantDir = uploadDir.resolve(String.valueOf(tenantId)).normalize();
            if (!tenantDir.startsWith(uploadDir)) {   // 双保险：杜绝路径穿越
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "非法的存储路径");
            }
            Files.createDirectories(tenantDir);
            Path target = tenantDir.resolve(UUID.randomUUID() + "." + ext).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "非法的存储路径");
            }
            file.transferTo(target.toFile());

            List<FileTableReader.SheetPreview> previews;
            try {
                previews = reader.previewAll(target, type, previewRows);
            } catch (RuntimeException e) {
                deleteQuietly(target);   // 解析失败：清掉刚落盘的孤儿文件
                throw e;
            }
            List<FileSheetPreview> sheets = previews.stream()
                    .map(p -> new FileSheetPreview(p.name(), p.index(), p.columns(),
                            p.rows(), p.truncated(), p.importable()))
                    .toList();
            // 顶层预览取首个可导入的工作表：CSV / 单 sheet Excel / Parquet 的调用方读的就是这三个字段，保留兼容入口
            FileTableReader.SheetPreview first = previews.stream()
                    .filter(FileTableReader.SheetPreview::importable)
                    .findFirst()
                    .orElse(null);
            if (first == null) {
                deleteQuietly(target);   // 一张能导入的表都没有：落盘的文件没有保留价值
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED,
                        "文件中没有可导入的工作表，请确认首行是列名（封面页/说明页会被自动跳过）");
            }
            log.info("文件上传成功: tenant={}, {} → {} ({} 张工作表, 首表 {} 列/预览 {} 行)",
                    tenantId, original, target, sheets.size(), first.columns().size(), first.rows().size());
            return new FileUploadResult(
                    target.toAbsolutePath().toString(),
                    original,
                    file.getSize(),
                    type.name(),
                    first.columns(),
                    first.rows(),
                    first.truncated(),
                    sheets);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("文件上传失败: {}", original, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "文件上传失败: " + e.getMessage());
        }
    }

    /** 取小写扩展名（无扩展名返回空串）。 */
    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            // 清理失败不影响主流程
        }
    }
}
