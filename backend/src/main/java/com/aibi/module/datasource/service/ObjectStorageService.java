package com.aibi.module.datasource.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.model.DataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 对象存储下载服务（S3 / 阿里云 OSS / MinIO 通用，均兼容 S3 协议）。
 *
 * <p>对象存储类数据源（{@link DataSourceType#isObjectStorage()}）不直连查询，而是先把目标对象下载到本地临时文件，
 * 再交给与 CSV/Excel 完全相同的「载入 SQLite → 标准 SQL 问数」链路。字段复用：{@code host}=endpoint、
 * {@code database}=bucket、{@code username}=AccessKey、{@code password}=SecretKey、
 * {@code filePath}=objectKey、{@code params.region}=区域（缺省 us-east-1）。
 *
 * <p>下载结果按「租户 + 数据源」缓存于 {@code java.io.tmpdir}，并用对象 ETag 做一致性校验：ETag 未变则直接复用本地副本，
 * 免去重复下载；数据源被替换/删除时由适配器 {@code evictCache} 清掉副本。不可达 / 无权限 / Key 不存在均抛干净的
 * {@code BizException(DATASOURCE_CONNECT_FAILED)}，不会把底层 SDK 异常漏给前端。
 *
 * <p>统一用 {@code forcePathStyle(true)} + {@code endpointOverride}：所有 S3 兼容服务（含 OSS 的全局域名、
 * MinIO 的 IP:端口）都按 {@code {endpoint}/{bucket}/{key}} 寻址，无需各家单独适配。HTTP 客户端选用
 * {@code url-connection-client}（JDK 内置 {@code HttpURLConnection}），无额外原生依赖、跨平台安全。
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private static final String PREFIX = "aibi-obj-";

    /**
     * 把对象存储类数据源解析为本地可读的文件路径（下载 + ETag 缓存）。非对象存储类直接返回 {@code filePath}。
     */
    public Path resolveLocalPath(DataSourceConfig config) {
        if (!config.type().isObjectStorage()) {
            return Paths.get(config.filePath());
        }
        if (isBlank(config.host()) || isBlank(config.database()) || isBlank(config.filePath())
                || isBlank(config.username()) || isBlank(config.password())) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                    "对象存储数据源需提供 endpoint(host)、bucket(database)、accessKey(username)、"
                            + "secretKey(password) 与 objectKey(filePath)");
        }
        Path cached = cachePath(config.tenantId(), config.id());
        Path etagFile = Paths.get(cached + ".etag");
        try {
            String remoteEtag = safeHeadEtag(config);
            if (remoteEtag != null && Files.exists(cached) && etagMatches(etagFile, remoteEtag)) {
                return cached;
            }
            download(config, cached);
            writeEtag(etagFile, remoteEtag);
            log.info("对象存储下载完成: {} → {}", safeDescriptor(config), cached);
            return cached;
        } catch (BizException e) {
            throw e;
        } catch (S3Exception e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                    "对象存储访问失败(" + e.statusCode() + "): " + rootMessage(e));
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "对象存储下载失败: " + rootMessage(e));
        }
    }

    /** 失效某数据源的本地对象缓存（删除临时副本与 etag 标记）。 */
    public void evict(Long tenantId, Long datasourceId) {
        try {
            Path cached = cachePath(tenantId, datasourceId);
            Files.deleteIfExists(cached);
            Files.deleteIfExists(Paths.get(cached + ".etag"));
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    // ---------- 内部 ----------

    private S3Client client(DataSourceConfig config) {
        String endpoint = config.host();
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }
        String region = config.params() != null ? config.params().getOrDefault("region", "us-east-1") : "us-east-1";
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.username(), config.password())))
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private void download(DataSourceConfig config, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (S3Client s3 = client(config)) {
            s3.getObject(GetObjectRequest.builder().bucket(config.database()).key(config.filePath()).build(),
                    ResponseTransformer.toFile(target));
        }
    }

    /**
     * 取远端对象 ETag 用于缓存校验；部分 S3 兼容服务无 HeadObject 权限时退化为 {@code null}
     * （按「本地无缓存即下载」处理，不阻断问数）。Key 不存在直接抛干净异常。
     */
    private String safeHeadEtag(DataSourceConfig config) {
        try (S3Client s3 = client(config)) {
            HeadObjectResponse resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(config.database()).key(config.filePath()).build());
            String etag = resp.eTag();
            return etag == null ? null : stripQuotes(etag);
        } catch (NoSuchKeyException e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "对象不存在: " + config.filePath());
        } catch (S3Exception e) {
            log.debug("HeadObject 失败，按无缓存处理: {} - {}", safeDescriptor(config), rootMessage(e));
            return null;
        }
    }

    private static boolean etagMatches(Path etagFile, String remoteEtag) {
        try {
            if (!Files.exists(etagFile)) {
                return false;
            }
            String local = Files.readString(etagFile, StandardCharsets.UTF_8).trim();
            return local.equals(remoteEtag);
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeEtag(Path etagFile, String etag) {
        if (etag == null) {
            return;
        }
        try {
            Files.writeString(etagFile, etag, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("写入 etag 标记失败（不影响下载）: {}", e.getMessage());
        }
    }

    /** 去掉部分服务返回的 ETag 两端引号（如 {@code "abc123"} → {@code abc123}）。 */
    private static String stripQuotes(String etag) {
        String s = etag.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private Path cachePath(Long tenantId, Long datasourceId) {
        return Paths.get(System.getProperty("java.io.tmpdir"),
                PREFIX + tenantId + "-" + datasourceId + ".bin");
    }

    private String safeDescriptor(DataSourceConfig config) {
        return config.type() + "://" + config.host() + "/" + config.database() + "/" + config.filePath()
                + " (tenant=" + config.tenantId() + ", ds=" + config.id() + ")";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
