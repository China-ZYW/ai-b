package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 短信通知渠道（{@code SMS}）：规则命中时给 {@code alert_rule.notify_target} 里的手机号发短信。
 *
 * <p><b>双服务商</b>：{@code provider=ALIYUN}（Dysmsapi RPC 风格，HMAC-SHA1 签名）或
 * {@code provider=TENCENT}（短信 API 2021-01-11，TC3-HMAC-SHA256 签名）。两者报文/签名差异较大，
 * 故各自实现、共用同一套"取配置 + 组装模板变量 + 异常契约"。
 *
 * <p><b>模板变量只传一个</b>：短信模板由服务商侧审核，变量个数由模板决定；本项目统一把
 * "简短告警文本"（级别 + 规则名 + 当前值）作为<b>唯一变量</b>——阿里云写入 {@code TemplateParam}
 * 的 {@code templateParamKey}（默认 {@code name}）字段，腾讯云作为 {@code TemplateParamSet} 的单个元素。
 *
 * <p><b>异常契约</b>（与 {@link AlertNotifier} 一致）：未启用/缺配置 → {@link #available()} 返回
 * {@code false}（路由回落兜底渠道）；收件人为空或服务商返回错误 → 抛 {@link AlertNotifyException}，
 * 引擎会把 {@code alert_record.notified} 记成 false，让"没发出去"看得见。
 */
@Service
public class SmsAlertNotifier implements AlertNotifier {

    /** 渠道码。 */
    public static final String CHANNEL = "SMS";

    private static final Logger log = LoggerFactory.getLogger(SmsAlertNotifier.class);

    private static final String ALIYUN_ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final String TENCENT_ENDPOINT = "https://sms.tencentcloudapi.com/";
    private static final String TENCENT_HOST = "sms.tencentcloudapi.com";
    private static final String TENCENT_SERVICE = "sms";
    private static final String TENCENT_VERSION = "2021-01-11";

    private final NotifyConfigService notifyConfig;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public SmsAlertNotifier(NotifyConfigService notifyConfig) {
        this.notifyConfig = notifyConfig;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean available() {
        Map<String, Object> cfg = cfg();
        if (!NotifyConfigService.boolVal(cfg, "enabled", false)) {
            return false;
        }
        if (baseKeys(cfg).isEmpty()) {
            return false;
        }
        return "TENCENT".equals(provider(cfg)) ? !tencentKeys(cfg).isEmpty() : !aliyunKeys(cfg).isEmpty();
    }

    @Override
    public void notify(AlertRule rule, AlertRecord record) {
        if (!available()) {
            throw new AlertNotifyException("短信渠道不可用（未启用或缺少服务商配置）");
        }
        Map<String, Object> cfg = cfg();
        List<String> phones = phones(rule.getNotifyTarget());
        if (phones.isEmpty()) {
            throw new AlertNotifyException("规则未配置手机号（notify_target 为空）");
        }
        String text = shortText(rule, record);
        if ("TENCENT".equals(provider(cfg))) {
            sendByTencent(cfg, phones, text);
        } else {
            sendByAliyun(cfg, phones, text);
        }
        log.info("预警短信已发送 rule={}({}) 收件人={} 服务商={}",
                rule.getId(), rule.getName(), phones, provider(cfg));
    }

    // ==================== 阿里云 Dysmsapi（RPC + HMAC-SHA1） ====================

    private void sendByAliyun(Map<String, Object> cfg, List<String> phones, String text) {
        String keyId = NotifyConfigService.str(cfg, "accessKeyId", "");
        String keySecret = NotifyConfigService.str(cfg, "accessKeySecret", "");
        String region = NotifyConfigService.str(cfg, "region", "cn-hangzhou");
        String paramKey = NotifyConfigService.str(cfg, "templateParamKey", "name");
        int timeoutMs = NotifyConfigService.intVal(cfg, "timeoutMs", 8000);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("AccessKeyId", keyId);
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", String.join(",", phones));
        params.put("RegionId", region);
        params.put("SignName", NotifyConfigService.str(cfg, "signName", ""));
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", NotifyConfigService.str(cfg, "templateCode", ""));
        params.put("TemplateParam", "{\"" + paramKey + "\":\"" + text + "\"}");
        params.put("Timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now()));
        params.put("Version", "2017-05-25");

        String canonical = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> aliyunEncode(e.getKey()) + "=" + aliyunEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String stringToSign = "POST&" + aliyunEncode("/") + "&" + aliyunEncode(canonical);
        String signature = base64(hmac("HmacSHA1", keySecret + "&", stringToSign));

        String body = canonical + "&Signature=" + aliyunEncode(signature);
        String resp = post(ALIYUN_ENDPOINT, "application/x-www-form-urlencoded; charset=UTF-8", body, timeoutMs);
        String compact = resp == null ? "" : resp.replaceAll("\\s+", "");
        if (compact.contains("\"Code\":\"OK\"")) {
            return;
        }
        throw new AlertNotifyException("阿里云短信返回错误：" + brief(resp));
    }

    // ==================== 腾讯云短信（TC3-HMAC-SHA256） ====================

    private void sendByTencent(Map<String, Object> cfg, List<String> phones, String text) {
        String secretId = NotifyConfigService.str(cfg, "accessKeyId", "");
        String secretKey = NotifyConfigService.str(cfg, "accessKeySecret", "");
        String region = NotifyConfigService.str(cfg, "region", "ap-guangzhou");
        int timeoutMs = NotifyConfigService.intVal(cfg, "timeoutMs", 8000);

        String phoneSet = phones.stream().map(SmsAlertNotifier::withCountryCode)
                .map(p -> "\"" + p + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        String body = "{\"PhoneNumberSet\":[" + phoneSet + "]"
                + ",\"SmsSdkAppId\":\"" + NotifyConfigService.str(cfg, "sdkAppId", "") + "\""
                + ",\"Sign\":\"" + NotifyConfigService.str(cfg, "signName", "") + "\""
                + ",\"TemplateId\":\"" + NotifyConfigService.str(cfg, "templateId", "") + "\""
                + ",\"TemplateParamSet\":[\"" + text + "\"]}";

        long ts = Instant.now().getEpochSecond();
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.now());
        String contentType = "application/json; charset=utf-8";
        String canonicalHeaders = "content-type:" + contentType + "\nhost:" + TENCENT_HOST + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256Hex(body);
        String credentialScope = date + "/" + TENCENT_SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + ts + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

        byte[] kDate = hmac("HmacSHA256", "TC3" + secretKey, date);
        byte[] kService = hmacBytes("HmacSHA256", kDate, TENCENT_SERVICE);
        byte[] kSigning = hmacBytes("HmacSHA256", kService, "tc3_request");
        String signature = hex(hmacBytes("HmacSHA256", kSigning, stringToSign));
        String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(URI.create(TENCENT_ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", authorization)
                .header("Content-Type", contentType)
                .header("Host", TENCENT_HOST)
                .header("X-TC-Action", "SendSms")
                .header("X-TC-Timestamp", String.valueOf(ts))
                .header("X-TC-Version", TENCENT_VERSION)
                .header("X-TC-Region", region)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        String resp;
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                throw new AlertNotifyException("腾讯云短信 HTTP " + r.statusCode() + "：" + brief(r.body()));
            }
            resp = r.body();
        } catch (AlertNotifyException e) {
            throw e;
        } catch (Exception e) {
            throw new AlertNotifyException("腾讯云短信发送失败: " + e.getMessage(), e);
        }
        String compact = resp == null ? "" : resp.replaceAll("\\s+", "");
        if (compact.contains("\"Error\"")) {
            throw new AlertNotifyException("腾讯云短信返回错误：" + brief(resp));
        }
        // SendStatusSet 里逐条带 Code=Ok；出现非 Ok 视为部分/全部失败
        if (compact.contains("\"Code\":\"") && !compact.contains("\"Code\":\"Ok\"")) {
            throw new AlertNotifyException("腾讯云短信发送未成功：" + brief(resp));
        }
    }

    // ==================== 通用 ====================

    private Map<String, Object> cfg() {
        return notifyConfig.channel(CHANNEL);
    }

    private static String provider(Map<String, Object> cfg) {
        return NotifyConfigService.str(cfg, "provider", "ALIYUN").toUpperCase(Locale.ROOT);
    }

    /** 服务商无关的基础密钥（缺则渠道不可用）。 */
    private static List<String> baseKeys(Map<String, Object> cfg) {
        List<String> missing = new ArrayList<>();
        if (NotifyConfigService.str(cfg, "accessKeyId", "").isBlank()) {
            missing.add("accessKeyId");
        }
        if (NotifyConfigService.str(cfg, "accessKeySecret", "").isBlank()) {
            missing.add("accessKeySecret");
        }
        if (NotifyConfigService.str(cfg, "signName", "").isBlank()) {
            missing.add("signName");
        }
        return missing;
    }

    private static List<String> aliyunKeys(Map<String, Object> cfg) {
        List<String> missing = new ArrayList<>();
        if (NotifyConfigService.str(cfg, "templateCode", "").isBlank()) {
            missing.add("templateCode");
        }
        return missing;
    }

    private static List<String> tencentKeys(Map<String, Object> cfg) {
        List<String> missing = new ArrayList<>();
        if (NotifyConfigService.str(cfg, "sdkAppId", "").isBlank()) {
            missing.add("sdkAppId");
        }
        if (NotifyConfigService.str(cfg, "templateId", "").isBlank()) {
            missing.add("templateId");
        }
        return missing;
    }

    /** 手机号解析：兼容中英文逗号/分号/空白分隔。 */
    List<String> phones(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : target.split("[,，;；\\s]+")) {
            String p = raw.replaceAll("[^0-9+]", "");
            if (p.length() >= 7 && !out.contains(p)) {
                out.add(p);
            } else if (!p.isEmpty()) {
                log.warn("忽略疑似非法的手机号: {}", raw);
            }
        }
        return out;
    }

    /** 腾讯云要求 E.164（+86 前缀）。 */
    private static String withCountryCode(String p) {
        if (p.startsWith("+")) {
            return p;
        }
        return p.length() == 11 ? "+86" + p : "+" + p;
    }

    /** 短信唯一模板变量：级别 + 规则名 + 当前值（长度按服务商常见模板变量限制收紧）。 */
    private static String shortText(AlertRule rule, AlertRecord record) {
        String name = rule.getName() == null ? "预警" : rule.getName();
        String v = record.getValue() == null ? "-" : String.valueOf(record.getValue());
        String s = record.getSeverity() + " " + name + " 当前值" + v;
        return s.length() <= 60 ? s : s.substring(0, 60);
    }

    private String post(String url, String contentType, String body, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                throw new AlertNotifyException("短信网关 HTTP " + r.statusCode() + "：" + brief(r.body()));
            }
            return r.body();
        } catch (AlertNotifyException e) {
            throw e;
        } catch (Exception e) {
            throw new AlertNotifyException("短信发送失败: " + e.getMessage(), e);
        }
    }

    /** 阿里云要求的 percentEncode：URLEncoder 后再把 + → %20、* → %2A、%7E → ~。 */
    private static String aliyunEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static byte[] hmac(String alg, String key, String data) {
        return hmacBytes(alg, key.getBytes(StandardCharsets.UTF_8), data);
    }

    private static byte[] hmacBytes(String alg, byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(key, alg));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA256 计算失败: " + e.getMessage(), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
