package com.aibi.common.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 播种器共享小工具（T8.1 拆分自 {@link DataSeeder} 的私有静态方法）。
 */
final class SeederSupport {

    private SeederSupport() {
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 读取 JSON 节点文本字段（缺失/空返回 null）。 */
    static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** 读取 JSON 节点文本字段（缺失/空返回默认值）。 */
    static String text(JsonNode node, String field, String def) {
        String v = text(node, field);
        return v != null ? v : def;
    }

    /** 构造 JSON 字符串数组（转义内部引号/反斜杠）。 */
    static String jsonArray(String... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(items[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]").toString();
    }
}
