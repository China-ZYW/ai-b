package com.aibi.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 值的<b>语义等价</b>判定（全项目唯一实现）。
 *
 * <p><b>为什么需要它</b>：管理端保存的入口不止一个（配置中心、平台默认配置、指标口径……），
 * 值往往以 JSON 文本形态提交。用户把编辑框里的内容原样再存一次、或只是被前端 pretty 重排过
 * （键序变了、缩进变了）时，<b>逐字符比较必然判定为"有变化"</b>——于是每次空提交都被当成一次
 * 真实修改，把"这行配置被改过几次"这类版本号事实记录顶上去，污染审计判断。
 *
 * <p><b>判定口径</b>：解析成 {@link JsonNode} 后比较。因此
 * <ul>
 *   <li>仅空白、缩进、换行不同 → <b>等价</b>；</li>
 *   <li>对象键顺序不同 → <b>等价</b>（{@link JsonNode#equals} 对 ObjectNode 按键值比较，与顺序无关）；</li>
 *   <li>数字 {@code 1} 与 {@code 1.0} → <b>不等价</b>（Jackson 按数值类型比较，避免把类型差异当无变化）；</li>
 *   <li>字符串 {@code "1"} 与数字 {@code 1} → <b>不等价</b>（这正是本类存在的意义之一：
 *       前端把枚举值写成裸 {@code 8} 还是 {@code "8"} 会让下游枚举比对失配，必须当成一次真实变更）。</li>
 * </ul>
 *
 * <p><b>失败时的取舍：宁可"不等"</b>。快速路径之外的解析失败（非法 JSON）返回 {@code false}，
 * 让调用方照常写入——多写一次只是浪费一次 update，而误判为"无变化"会
 * <b>静默丢掉用户真实输入</b>，后者不可接受。仅当两侧都为 {@code null} 时才认为等价。
 *
 * <p><b>使用边界（重要）</b>：本类只适用于"<b>内容版本号</b>"这类事实记录。
 * {@code app_user.token_version}、{@code tenant.perm_version} 是<b>安全计数器</b>——
 * 它们表达的是"令牌/权限需要重新签发"，必须每次操作都递增，<b>禁止</b>用本类做幂等跳过，
 * 否则会导致改密码不踢下线、改权限不刷新会话这类安全问题。
 */
public final class JsonCompare {

    private JsonCompare() {
    }

    /**
     * 两个 JSON 文本是否语义等价。
     *
     * @param mapper Jackson 映射器（复用调用方已注入的实例，避免各处 new）
     * @param a      旧值（可为 null）
     * @param b      新值（可为 null）
     * @return true = 语义等价（调用方可跳过写入）；不同且无法解析时返回 false
     */
    public static boolean same(ObjectMapper mapper, String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        // 快速路径：逐字符相同就不必解析
        if (a.equals(b)) {
            return true;
        }
        try {
            return mapper.readTree(a).equals(mapper.readTree(b));
        } catch (Exception e) {
            // 解析失败按"不等"处理：多写一次无害，误判无变化会丢用户输入
            return false;
        }
    }
}
