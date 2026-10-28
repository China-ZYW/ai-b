<script setup lang="ts">
/**
 * 嵌入问数页 AI 气泡内容（T7.3 自 EmbedChatView 拆出）：
 * AI 标识 + 错误 / AI 解读（结论+要点+异常）+ 结果表格 + 质量分元信息 + 导出 + 推荐追问。
 * 气泡外壳（.bubble.bot-bubble 与 is-loading 态）由 EmbedMessages 持有，此处只渲染其内容。
 */
import { useEmbedCtx } from './useEmbedChat'
import type { Msg } from './embedTypes'

const ctx = useEmbedCtx()
defineProps<{ msg: Msg; idx: number }>()
</script>

<template>
  <!-- fragment 多根：与拆分前一致，气泡内容不引入额外包裹层 -->
  <div class="bot-tag"><span class="bot-tag-dot"></span>AI 数据助理</div>
  <p v-if="msg.error" class="err">{{ msg.error }}</p>
  <template v-else>
    <!-- AI 解读：结论 + 要点 + 异常（与主站结果面板同口径） -->
    <div
      v-if="msg.summary || (msg.keyPoints && msg.keyPoints.length) || (msg.anomalies && msg.anomalies.length)"
      class="ai-block"
    >
      <div class="ai-block-head"><span class="ai-dot"></span>AI 解读</div>
      <p v-if="msg.summary" class="ai-summary">{{ msg.summary }}</p>
      <ul v-if="msg.keyPoints && msg.keyPoints.length" class="ai-list">
        <li v-for="(p, i) in msg.keyPoints" :key="'kp' + i">{{ p }}</li>
      </ul>
      <div v-if="msg.anomalies && msg.anomalies.length" class="ai-anomalies">
        <p v-for="(a, i) in msg.anomalies" :key="'an' + i">{{ a }}</p>
      </div>
    </div>
    <div v-if="msg.columns.length" class="table-wrap">
      <table class="mini-table">
        <thead>
          <tr>
            <th v-for="c in msg.columns" :key="c">{{ c }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, ri) in msg.rows" :key="ri">
            <td v-for="(cell, ci) in r" :key="ci">{{ ctx.fmt(cell) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="msg.more" class="more">仅显示前 {{ msg.rows.length }} 行，共 {{ msg.rowCount }} 行</p>
    </div>
    <p v-if="msg.meta" class="meta-line">{{ msg.meta }}</p>
    <div v-if="msg.columns.length" class="msg-actions">
      <button class="export-btn" type="button" @click="ctx.exportMsg(msg, idx)">导出 Excel</button>
    </div>
    <!-- 答案下方的推荐问题：优先本轮上下文追问（无/失败回落全局样例），点击即追问 -->
    <div v-if="!msg.loading && (msg.followups?.length || ctx.displayTips.length)" class="msg-suggest">
      <span class="msg-suggest-label">你可能还想问</span>
      <button
        v-for="(t, ti) in (msg.followups?.length ? msg.followups : ctx.displayTips)"
        :key="'sg' + ti"
        class="suggest-chip sm"
        type="button"
        @click="ctx.useTip(t)"
      >{{ t }}</button>
    </div>
  </template>
</template>

<style scoped>
/* AI 标识：气泡内顶部小标签，增强"这是 AI 回答"的辨识度 */
.bot-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #1d4ed8;
  margin-bottom: 8px;
}
.bot-tag-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.15);
}
/* ===== AI 解读：结论 + 要点 + 异常 ===== */
.ai-block {
  margin: 0 0 10px;
  padding: 9px 11px;
  border-radius: 12px;
  background: linear-gradient(180deg, #f2f7ff 0%, #eaf2ff 100%);
  border: 1px solid #d9e6fb;
}
.ai-block-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
  margin-bottom: 5px;
}
.ai-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2563eb;
}
.ai-summary {
  margin: 0;
  color: #1f2937;
}
.ai-list {
  margin: 6px 0 0;
  padding-left: 16px;
  color: #374151;
}
.ai-list li {
  margin: 2px 0;
}
.ai-anomalies {
  margin-top: 6px;
  color: #b45309;
}
.ai-anomalies p {
  margin: 3px 0;
  display: flex;
  gap: 5px;
  align-items: flex-start;
}
.ai-anomalies p::before {
  content: '!';
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  margin-top: 2px;
  border-radius: 50%;
  background: #fef3c7;
  color: #b45309;
  font-size: 10px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.table-wrap {
  overflow-x: auto;
}
.mini-table {
  border-collapse: separate;
  border-spacing: 0;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #eef1f5;
  font-size: 12px;
  width: 100%;
}
.mini-table th,
.mini-table td {
  border-bottom: 1px solid #eef1f5;
  border-right: 1px solid #eef1f5;
  padding: 6px 10px;
  text-align: left;
  white-space: nowrap;
}
.mini-table th:last-child,
.mini-table td:last-child {
  border-right: none;
}
.mini-table tr:last-child td {
  border-bottom: none;
}
.mini-table th {
  background: #f4f7fb;
  color: #334155;
  font-weight: 600;
}
.more {
  margin: 6px 0 0;
  font-size: 11px;
  color: #9ca3af;
}
.err {
  color: #dc2626;
  margin: 0;
}
.meta-line {
  margin: 8px 0 0;
  font-size: 11px;
  color: #9ca3af;
}
.msg-actions {
  margin-top: 8px;
}
.export-btn {
  border: 1px solid #dbe4f0;
  background: #fff;
  color: #2563eb;
  border-radius: 9px;
  padding: 5px 11px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.export-btn:hover {
  border-color: #2563eb;
  background: #eff5ff;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.14);
}

/* ===== 答案下方的推荐问题 ===== */
.msg-suggest {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e6eaf2;
}
.msg-suggest-label {
  font-size: 11px;
  color: #94a3b8;
  margin-right: 2px;
}
/* chip 基础样式与输入区组件各自 scoped 自持（值逐字相同，行为等价） */
.suggest-chip {
  border: 1px solid #dbe4f0;
  background: #fff;
  color: #334155;
  border-radius: 18px;
  padding: 6px 13px;
  font-size: 12.5px;
  line-height: 1.4;
  cursor: pointer;
  transition: all 0.18s ease;
  text-align: left;
  max-width: 100%;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.suggest-chip:hover {
  border-color: #2563eb;
  color: #2563eb;
  background: #eff5ff;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.16);
}
/* 答案下方的 chip 更轻更小，避免抢主体答案的视觉权重 */
.suggest-chip.sm {
  padding: 4px 9px;
  font-size: 11.5px;
  border-radius: 999px;
  background: #f7f9fc;
  border: 1px solid #e6eaf2;
  color: #475569;
}
</style>
