package com.aibi.module.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aibi.module.config.service.ConfigService;

/**
 * 通知渠道运行时配置（平台级）：把 SMTP / 短信 / 钉钉 / 企微 / 飞书 / PushPlus 的渠道参数
 * 收口为<b>一份可在线编辑、改完即生效</b>的配置。
 *
 * <p><b>存哪</b>：复用统一配置表 {@code sys_config} 的 <b>GLOBAL</b> scope 一行
 * （{@code category=NOTIFY, config_key=channels}，值是一段 JSON）。刻意<b>不</b>登记进前端配置中心的
 * {@code CATEGORIES} —— 那里会把值原样回显，而本配置含 SMTP 密码 / webhook 令牌等密钥。
 *
 * <p><b>密钥不回显</b>：读取接口走 {@link #masked()}，敏感字段置空并另附 {@code secretsSet} 标明
 * "是否已配置"；写入时<b>留空 = 保持不变</b>（不是清空），避免"只想改端口却把密码清掉"。
 *
 * <p><b>为什么能"配置即生效"</b>：本项目 {@code ConfigService} 的进程内缓存默认关闭
 * （见 §48），且各通知实现都在<b>发送时</b>通过 {@link #channel(String)} 现读配置，
 * 不再像以前那样在构造期用 {@code @Value} 固化 —— 改完保存，下一条告警即按新配置发送。
 */
@Service
public class NotifyConfigService {

    private static final Logger log = LoggerFactory.getLogger(NotifyConfigService.class);

    /** 配置分类与键（sys_config GLOBAL 行）。 */
    public static final String CATEGORY = "NOTIFY";
    public static final String KEY = "channels";

    // ==================== 渠道码（与 AlertRule.notifyChannel 取值一致） ====================
    public static final String CH_LOG = "LOG";
    public static final String CH_EMAIL = "EMAIL";
    public static final String CH_SMS = "SMS";
    public static final String CH_DINGTALK = "DINGTALK";
    public static final String CH_WECOM = "WECOM";
    public static final String CH_FEISHU = "FEISHU";
    public static final String CH_PUSHPLUS = "PUSHPLUS";

    /** 配置 JSON 里的渠道键（= 渠道码小写）。 */
    public static final List<String> CONFIG_CHANNELS =
            List.of("email", "sms", "dingtalk", "wecom", "feishu", "pushplus");

    /** 敏感字段：渠道 → 字段名集合（读取置空、写入留空则保持原值）。 */
    private static final Map<String, Set<String>> SECRETS = Map.of(
            "email", Set.of("password"),
            "sms", Set.of("accessKeySecret"),
            "dingtalk", Set.of("webhook", "secret"),
            "wecom", Set.of("webhook"),
            "feishu", Set.of("webhook"),
            "pushplus", Set.of("token"));

    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public NotifyConfigService(ConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    // ==================== 读取 ====================

    /**
     * 原始配置（含密钥），供通知实现发送时读取；任何解析异常都回落默认值，绝不抛。
     * 返回结构：{@code { fallbackChannel, email:{...}, sms:{...}, dingtalk:{...}, wecom:{...}, feishu:{...}, pushplus:{...} }}
     */
    public Map<String, Object> raw() {
        Map<String, Object> cfg = defaults();
        String json = configService.resolveRaw(null, CATEGORY, KEY);
        if (json == null || json.isBlank()) {
            return cfg;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            merge(cfg, parsed);
        } catch (Exception e) {
            log.warn("通知配置解析失败，已回落默认值：{}", e.getMessage());
        }
        return cfg;
    }

    /** 某个渠道的字段表（含密钥）；未知渠道返回空表。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> channel(String code) {
        String key = configKey(code);
        Object v = raw().get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /**
     * 回显用配置：敏感字段置空，并附 {@code secretsSet}（如 {@code "email.password": true}）标明是否已配置。
     */
    public Map<String, Object> masked() {
        Map<String, Object> src = raw();
        Map<String, Object> out = deepCopy(src);
        Map<String, Object> secretsSet = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : SECRETS.entrySet()) {
            Object ch = out.get(e.getKey());
            Object rawCh = src.get(e.getKey());
            if (!(ch instanceof Map) || !(rawCh instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) ch;
            @SuppressWarnings("unchecked")
            Map<String, Object> rm = (Map<String, Object>) rawCh;
            for (String f : e.getValue()) {
                Object val = rm.get(f);
                boolean set = val != null && !String.valueOf(val).isBlank();
                secretsSet.put(e.getKey() + "." + f, set);
                m.put(f, "");
            }
        }
        out.put("secretsSet", secretsSet);
        return out;
    }

    // ==================== 写入 ====================

    /**
     * 保存配置：与现有配置合并——<b>敏感字段留空表示"保持原值"</b>（不是清空）；
     * 其余字段按传入值覆盖（缺省则沿用原值）。写库后立即生效（ConfigService 无缓存）。
     */
    public Map<String, Object> save(Map<String, Object> incoming) {
        Map<String, Object> current = raw();
        Map<String, Object> merged = deepCopy(current);
        if (incoming != null) {
            // 兜底渠道
            Object fb = incoming.get("fallbackChannel");
            if (fb != null && !String.valueOf(fb).isBlank()) {
                merged.put("fallbackChannel", normalizeChannel(String.valueOf(fb), current));
            }
            for (String chKey : CONFIG_CHANNELS) {
                Object inc = incoming.get(chKey);
                if (!(inc instanceof Map)) {
                    continue;
                }
                Map<String, Object> target = asMap(merged.get(chKey));
                Map<?, ?> src = (Map<?, ?>) inc;
                for (Map.Entry<?, ?> e : src.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    Object val = e.getValue();
                    boolean secret = SECRETS.getOrDefault(chKey, Set.of()).contains(field);
                    if (secret && (val == null || String.valueOf(val).isBlank())) {
                        continue;   // 留空 = 保持原值
                    }
                    target.put(field, val);
                }
                merged.put(chKey, target);
            }
        }
        try {
            String json = objectMapper.writeValueAsString(merged);
            configService.upsert(ConfigService.SCOPE_GLOBAL, null, CATEGORY, KEY, json,
                    "平台通知渠道配置（SMTP/短信/钉钉/企微/飞书/PushPlus）；密钥不回显，留空=保持");
            log.info("通知渠道配置已更新（生效无需重启）");
        } catch (Exception e) {
            throw new IllegalStateException("通知配置保存失败: " + e.getMessage(), e);
        }
        return merged;
    }

    /** 兜底渠道（平台可配）；未配置时回落 yml 的 {@code aibi.notify.sender}。 */
    public String fallbackChannel(String ymlDefault) {
        Object v = raw().get("fallbackChannel");
        String s = v == null ? "" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? ymlDefault : s;
    }

    // ==================== 工具 ====================

    /** 渠道码 → 配置键（EMAIL → email）。 */
    public static String configKey(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    /** 渠道码合法性（LOG 无配置项，恒合法）。 */
    public static boolean isKnownChannel(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return CH_LOG.equals(c) || CONFIG_CHANNELS.contains(c.toLowerCase(Locale.ROOT));
    }

    /** 取字符串字段。 */
    public static String str(Map<String, Object> ch, String key, String def) {
        Object v = ch == null ? null : ch.get(key);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    /** 取布尔字段（兼容 Boolean 与 "true"/"1" 字符串）。 */
    public static boolean boolVal(Map<String, Object> ch, String key, boolean def) {
        Object v = ch == null ? null : ch.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return def;
        }
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    /** 取整数字段。 */
    public static int intVal(Map<String, Object> ch, String key, int def) {
        Object v = ch == null ? null : ch.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String normalizeChannel(String code, Map<String, Object> current) {
        String c = code.trim().toUpperCase(Locale.ROOT);
        return isKnownChannel(c) ? c : String.valueOf(current.getOrDefault("fallbackChannel", CH_LOG));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof Map ? new LinkedHashMap<>((Map<String, Object>) v) : v);
        }
        return out;
    }

    /** 把解析出来的配置合并进默认值（逐渠道、逐字段）。 */
    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            Object inc = e.getValue();
            Object cur = target.get(e.getKey());
            if (cur instanceof Map && inc instanceof Map) {
                ((Map<String, Object>) cur).putAll((Map<String, Object>) inc);
            } else if (inc != null) {
                target.put(e.getKey(), inc);
            }
        }
    }

    /** 默认配置（未配置时的空壳，保证前端表单字段齐全）。 */
    public static Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fallbackChannel", CH_LOG);

        Map<String, Object> email = new LinkedHashMap<>();
        email.put("enabled", false);
        email.put("host", "");
        email.put("port", 465);
        email.put("username", "");
        email.put("password", "");
        email.put("from", "");
        email.put("sslEnabled", true);
        email.put("timeoutMs", 8000);
        m.put("email", email);

        Map<String, Object> sms = new LinkedHashMap<>();
        sms.put("enabled", false);
        sms.put("provider", "ALIYUN");       // ALIYUN / TENCENT
        sms.put("signName", "");
        sms.put("templateCode", "");         // 阿里云模板 CODE（如 SMS_123456）
        sms.put("templateId", "");           // 腾讯云模板 ID
        sms.put("sdkAppId", "");             // 腾讯云短信应用 SdkAppId
        sms.put("region", "cn-hangzhou");    // 阿里云 cn-hangzhou / 腾讯云 ap-guangzhou
        sms.put("accessKeyId", "");
        sms.put("accessKeySecret", "");
        sms.put("timeoutMs", 8000);
        m.put("sms", sms);

        for (String ch : List.of("dingtalk", "wecom", "feishu")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("enabled", false);
            c.put("webhook", "");
            if ("dingtalk".equals(ch)) {
                c.put("secret", "");
            }
            c.put("timeoutMs", 8000);
            m.put(ch, c);
        }

        Map<String, Object> pushplus = new LinkedHashMap<>();
        pushplus.put("enabled", false);
        pushplus.put("token", "");
        pushplus.put("timeoutMs", 8000);
        m.put("pushplus", pushplus);
        return m;
    }

    /** 供前端渲染"有哪些渠道可配"（顺序固定）。 */
    public static List<String> configuredChannelKeys() {
        return new ArrayList<>(new LinkedHashSet<>(CONFIG_CHANNELS));
    }
}
