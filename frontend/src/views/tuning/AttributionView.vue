<script setup lang="ts">
import AttributionPanel from '@/components/chat/AttributionPanel.vue'

/**
 * 问数调优「维度归因」tab（需求 §3.1 收口）：把此前只在对话结果下方出现的归因模块
 * 提为常驻入口，便于反复试跑不同维度 / 基准，而不必每次先问一句波动类问题。
 *
 * <p>本视图只是薄壳：参数区、趋势 / 量价因子 / 贡献瀑布、三套审计 SQL 全部复用
 * {@link AttributionPanel}（embedded 模式：默认展开、无折叠头），
 * 保证调优页与对话页看到的是同一套实现与同一套口径，不存在两份逻辑漂移。
 *
 * <p>为什么放在调优页而不是新开一个一级菜单：归因结果的可用性直接取决于「指标口径是否沉淀」
 * 与「语义层是否标记维度」，这两件事正是本页其余 tab 的职责 —— 归因跑不出结果时，
 * 用户在同一页就能切到「语义管理 / 指标库」补配，形成治理闭环。
 */
defineProps<{ datasourceId: number | null }>()
</script>

<template>
  <div class="attribution-view">
    <div class="av-head">
      <div>
        <h3 class="av-title">维度波动归因</h3>
        <p class="av-desc">
          回答「指标为什么涨 / 跌」：先选<b>已沉淀口径</b>的指标，再选归因维度与对比基准，执行后给出
          趋势、量价因子、维度贡献瀑布与三套可复算的审计 SQL。置信度低于 30% 会被阻止并提示原因 ——
          多数情况是「指标未沉淀口径」或「语义层未标记维度」，可切到左侧对应 tab 补配后重试。
        </p>
      </div>
      <div class="av-note">
        <span class="muted small">归因结论由结构化结果确定性拼装，非 AI 生成，数值可全部复算</span>
      </div>
    </div>

    <AttributionPanel :datasource-id="datasourceId" embedded />
  </div>
</template>

<style scoped>
.attribution-view {
  min-height: 200px;
}
.av-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.av-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
}
.av-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 760px;
  line-height: 1.6;
}
.av-note {
  padding-top: 2px;
}
.muted {
  color: var(--text-secondary);
}
.small {
  font-size: 12px;
}
</style>
