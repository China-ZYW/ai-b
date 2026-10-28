package com.aibi.module.dashboard.dto;

import java.util.List;

/**
 * AI 布局与样式顾问请求。
 *
 * <p>关键点：{@code baseline} 是前端用确定性算法（{@code utils/smartLayout.ts}）算出的基线布局。
 * 传给模型是为了让它**在一份合法解的基础上优化**（语义分组、顺序、留白、主次关系），
 * 而不是从零生成——后者极易产出重叠/越界的非法网格。
 *
 * <p>另一层考虑：后端 Java 侧<b>不复制</b>那套算法。布局规则的唯一来源是前端引擎，
 * 后端只负责"给建议 + 严格校验"，避免两处实现各自漂移（本项目已多次踩此坑）。
 *
 * @param cols     网格列数（默认 12）
 * @param isScreen 是否大屏模式（1=大屏，倾向整屏铺满、少留白、更强对比）
 * @param intent   用户的风格指令（如"科技感深色""给领导看的经营看板"），可空
 * @param items    组件特征清单
 * @param baseline 确定性算法给出的基线布局（可空；为空时模型需自行给出完整布局）
 */
public record AiLayoutRequest(
        Integer cols,
        Integer isScreen,
        String intent,
        List<AiLayoutItem> items,
        List<AiLayoutSlot> baseline) {
}
