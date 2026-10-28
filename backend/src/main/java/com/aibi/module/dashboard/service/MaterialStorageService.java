package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.dashboard.dto.MaterialUploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 大屏背景素材存储（范围 B）：仅接收图片类型，落到 {@code {aibi.materials.dir}/{uuid}.{ext}}（扁平、uuid 不可猜），
 * 返回可经免认证公开端点 {@code /api/public/materials/{file}} 访问的 URL，供大屏背景 {@code url(...)} 引用，
 * 使公开分享页也能加载背景图。
 *
 * <p>护栏：空文件、超 {@code max-size-mb}、非图片白名单一律拦下；落盘路径做 {@code startsWith(materialsDir)} 校验
 * 杜绝路径穿越；公开读取同样按文件名白名单 + 目录前缀双重校验，失败返回 404。
 */
@Service
public class MaterialStorageService {

    private static final Logger log = LoggerFactory.getLogger(MaterialStorageService.class);

    private static final List<String> IMAGE_EXT = List.of("png", "jpg", "jpeg", "webp", "gif", "svg");
    // UUID 生成的文件名含连字符（如 a14506ab-53a0-...jpg），白名单须允许 '-'，否则 serve 整批 404 → 大屏背景白屏
    private static final Pattern SAFE_FILE = Pattern.compile("(?i)^[A-Za-z0-9-]+\\.(png|jpe?g|webp|gif|svg)$");
    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "webp", MediaType.valueOf("image/webp"),
            "gif", MediaType.IMAGE_GIF,
            "svg", MediaType.valueOf("image/svg+xml"));

    private final Path materialsDir;
    private final long maxSizeBytes;

    public MaterialStorageService(@Value("${aibi.materials.dir:./data/materials}") String dir,
                                  @Value("${aibi.materials.max-size-mb:5}") long maxSizeMb) {
        this.materialsDir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
    }

    /** 校验并落盘背景图，返回含公开访问 URL 的结果。 */
    public MaterialUploadResult store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "上传文件为空");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "图片大小超过限制（最大 " + (maxSizeBytes / 1024 / 1024) + "MB）");
        }
        TenantContext.requireTenantId();   // 仅登录租户可上传

        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!IMAGE_EXT.contains(ext)) {
            throw new BizException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "仅支持图片类型: " + String.join("/", IMAGE_EXT));
        }
        try {
            Files.createDirectories(materialsDir);
            String filename = java.util.UUID.randomUUID() + "." + ext;
            Path target = materialsDir.resolve(filename).normalize();
            if (!target.startsWith(materialsDir)) {   // 双保险：杜绝路径穿越
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "非法的存储路径");
            }
            file.transferTo(target.toFile());
            log.info("背景素材上传成功: {} ({} bytes)", filename, file.getSize());
            return new MaterialUploadResult("/api/public/materials/" + filename);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("背景素材上传失败: {}", original, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "背景素材上传失败: " + e.getMessage());
        }
    }

    /** 免认证读取背景图：文件名白名单 + 目录前缀双重校验，失败返回 404。 */
    public ResponseEntity<Resource> serve(String file) {
        if (file == null || !SAFE_FILE.matcher(file).matches()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path target = materialsDir.resolve(file).normalize();
        if (!target.startsWith(materialsDir) || !Files.exists(target) || !Files.isRegularFile(target)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            Resource resource = new FileSystemResource(target);
            String ext = extensionOf(file);
            MediaType type = CONTENT_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, type.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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
}
