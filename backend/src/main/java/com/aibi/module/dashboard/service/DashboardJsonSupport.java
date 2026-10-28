package com.aibi.module.dashboard.service;

import com.aibi.module.dashboard.dto.DashboardFilter;
import com.aibi.module.dashboard.dto.WidgetLayout;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 驾驶舱模块 JSON 编解码支持：在“透传给前端的 JSON 对象”与“落库的字符串”之间安全转换。
 *
 * <p>{@code chart_spec}/{@code layout} 列以字符串存储，但对前端始终以 JSON 对象/数组透传（非转义字符串）。
 * 全部方法对 null/空白/解析失败做兜底（返回 null 或空列表），避免脏数据导致接口整体失败。
 */
@Component
public class DashboardJsonSupport {

    private static final Logger log = LoggerFactory.getLogger(DashboardJsonSupport.class);
    private static final TypeReference<List<WidgetLayout>> LAYOUT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> LABELS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> FILTER_MAP_TYPE = new TypeReference<>() {
    };

    /** {@code metric.display_config} 为 VARCHAR(1024)：写入前留余量校验，超限则丢弃而非报错。 */
    private static final int DISPLAY_CONFIG_MAX = 1000;

    private final ObjectMapper objectMapper;

    public DashboardJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 对象 → JSON 字符串（null 安全，返回 null 表示不写）。 */
    public String write(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，按 null 处理：{}", e.getMessage());
            return null;
        }
    }

    /** JSON 字符串 → 对象（Map/List），供 VO 透传；null/空白/失败返回 null。 */
    public Object read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败，按 null 处理：{}", e.getMessage());
            return null;
        }
    }

    /** 布局 JSON 字符串 → {@link WidgetLayout} 列表；null/空白/失败返回空列表。 */
    public List<WidgetLayout> readLayout(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LAYOUT_TYPE);
        } catch (Exception e) {
            log.warn("布局解析失败，按空布局处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** {@link WidgetLayout} 列表 → 布局 JSON 字符串。 */
    public String writeLayout(List<WidgetLayout> layouts) {
        try {
            return objectMapper.writeValueAsString(layouts == null ? List.of() : layouts);
        } catch (Exception e) {
            log.warn("布局序列化失败，按空数组处理：{}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 下钻/联动筛选条件 JSON（{@code {"dim":"region","value":"华东"}}）→ {@link DashboardFilter}。
     *
     * <p>解析失败/缺 dim/空 JSON 一律返回 null（等同"不过滤"），不让单条非法筛选拖垮整屏取数。
     * {@code value} 原样保留为 Object（字符串或数字），由 JDBC 参数化绑定。
     */
    public DashboardFilter readFilter(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json, FILTER_MAP_TYPE);
            if (m == null) {
                return null;
            }
            Object dim = m.get("dim");
            if (dim == null || dim.toString().isBlank()) {
                return null;
            }
            return new DashboardFilter(dim.toString(), m.get("value"));
        } catch (Exception e) {
            log.warn("下钻筛选解析失败，按不过滤处理：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 指标级列展示名 → {@code display_config} JSON（形如 {@code {"columnLabels":{"cnt_rows":"订单数"}}}）。
     *
     * <p>空映射返回 null（不写该列，与“无自定义”语义一致）；超过列宽则告警并返回 null——
     * 展示名属于呈现细节，不能因为它让指标保存/更新整体失败。
     */
    public String writeColumnLabels(Map<String, String> columnLabels) {
        if (columnLabels == null || columnLabels.isEmpty()) {
            return null;
        }
        String json = write(Map.of("columnLabels", columnLabels));
        if (json != null && json.length() > DISPLAY_CONFIG_MAX) {
            log.warn("指标列展示名配置超长（{} 字符 > {}），已丢弃以避免落库失败", json.length(), DISPLAY_CONFIG_MAX);
            return null;
        }
        return json;
    }

    /** {@code display_config} JSON → 指标级列展示名映射；null/空白/解析失败/结构不符均返回空映射。 */
    public Map<String, String> readColumnLabels(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json).get("columnLabels");
            return node == null || !node.isObject() ? Map.of() : objectMapper.convertValue(node, LABELS_TYPE);
        } catch (Exception e) {
            log.warn("展示配置解析失败，按无自定义处理：{}", e.getMessage());
            return Map.of();
        }
    }
}
