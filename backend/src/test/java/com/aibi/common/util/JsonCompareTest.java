package com.aibi.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 语义等价的判定口径（v3.21 / §38）。
 *
 * <p>这个类看着小，但它决定的是"这次提交算不算一次真实变更"。判错的方向有两个，
 * 而只有一个方向是可接受的：把"等价"误判成"不等"只是多写一次库；把"不等"误判成"等价"
 * 会<b>静默丢掉用户的修改</b>（页面提示"已保存"，值却没落库）。所以本测试把每个
 * "算/不算变化"的边界都钉住。
 */
class JsonCompareTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("逐字符相同：等价（快速路径）")
    void identicalTextIsSame() {
        assertTrue(JsonCompare.same(mapper, "{\"a\":1}", "{\"a\":1}"));
    }

    @Test
    @DisplayName("仅空白与缩进不同：等价——前端编辑框会把 JSON 重新格式化后再提交")
    void whitespaceOnlyDiffIsSame() {
        assertTrue(JsonCompare.same(mapper, "{\"a\":1,\"b\":[1,2]}", "{\n  \"a\": 1,\n  \"b\": [1, 2]\n}"));
    }

    @Test
    @DisplayName("对象键序不同：等价——键序是序列化实现细节，不是用户的改动")
    void keyOrderDiffIsSame() {
        assertTrue(JsonCompare.same(mapper, "{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}"));
    }

    @Test
    @DisplayName("数组顺序不同：不等价——数组是值序列，顺序是语义的一部分")
    void arrayOrderDiffIsNotSame() {
        assertFalse(JsonCompare.same(mapper, "[1,2,3]", "[3,2,1]"));
    }

    @Test
    @DisplayName("字符串 \"8\" 与数字 8：不等价——类型写错会让下游枚举/数值比对失配，必须当成真实变更")
    void stringAndNumberAreNotSame() {
        assertFalse(JsonCompare.same(mapper, "\"8\"", "8"));
    }

    @Test
    @DisplayName("数字 1 与 1.0：不等价——不做数值归一化，避免掩盖类型差异")
    void integerAndDecimalAreNotSame() {
        assertFalse(JsonCompare.same(mapper, "1", "1.0"));
    }

    @Test
    @DisplayName("值真的变了：不等价")
    void changedValueIsNotSame() {
        assertFalse(JsonCompare.same(mapper, "true", "false"));
    }

    @Test
    @DisplayName("两侧都为 null：等价；只有一侧为 null：不等价（清空值是一次真实变更）")
    void nullSemantics() {
        assertTrue(JsonCompare.same(mapper, null, null));
        assertFalse(JsonCompare.same(mapper, "true", null));
        assertFalse(JsonCompare.same(mapper, null, "true"));
    }

    @Test
    @DisplayName("逐字符相同的非法 JSON：仍算等价（快速路径不解析；同一段文本就是同一段文本）")
    void identicalInvalidJsonIsSame() {
        assertTrue(JsonCompare.same(mapper, "{不是JSON}", "{不是JSON}"));
    }

    @Test
    @DisplayName("不同且任一侧非法 JSON：判为不等价（宁可多写一次，也不能误判无变化而丢用户输入）")
    void invalidJsonIsNeverSame() {
        assertFalse(JsonCompare.same(mapper, "{\"a\":1}", "{\"a\":"));
        assertFalse(JsonCompare.same(mapper, "{不是JSON}", "{\"a\":1}"));
    }

    @Test
    @DisplayName("嵌套对象的键序差异同样被忽略")
    void nestedKeyOrderDiffIsSame() {
        assertTrue(JsonCompare.same(mapper,
                "{\"x\":{\"p\":1,\"q\":2},\"y\":[{\"m\":1,\"n\":2}]}",
                "{\"y\":[{\"n\":2,\"m\":1}],\"x\":{\"q\":2,\"p\":1}}"));
    }
}
