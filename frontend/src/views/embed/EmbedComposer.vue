<script setup lang="ts">
/**
 * 嵌入问数页输入区（T7.3 自 EmbedChatView 拆出）：
 * 样例问题浮动条（无对话时展示）+ 底部输入/发送。
 * fragment 双根：二者仍是 .embed-root flex 列的直接子节点，DOM 层级与拆分前一致。
 */
import { useEmbedCtx } from './useEmbedChat'

const ctx = useEmbedCtx()
</script>

<template>
  <!-- 样例问题：浮动在输入框上方排列（无对话时展示，LLM 生成优先，失败回退硬编码） -->
  <div v-if="!ctx.messages.length && ctx.ready && ctx.displayTips.length" class="embed-suggest">
    <button
      v-for="(t, i) in ctx.displayTips"
      :key="i"
      class="suggest-chip"
      type="button"
      @click="ctx.useTip(t)"
    >{{ t }}</button>
  </div>

  <footer class="embed-foot">
    <input
      v-model="ctx.question"
      class="embed-input"
      :placeholder="ctx.placeholder"
      :disabled="ctx.busy || !ctx.ready"
      @keyup.enter="ctx.ask()"
    />
    <button class="embed-send" :disabled="ctx.busy || !ctx.ready || !ctx.question.trim()" @click="ctx.ask()">
      <span v-if="ctx.busy" class="send-spinner"></span>
      {{ ctx.busy ? '查询中' : '发送' }}
    </button>
  </footer>
</template>

<style scoped>
/* ===== 样例问题：浮动在输入框上方 ===== */
.embed-suggest {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 16px;
  background: rgba(255, 255, 255, 0.6);
  border-top: 1px solid #eef1f5;
  backdrop-filter: blur(4px);
}
/* chip 基础样式与气泡内组件各自 scoped 自持（值逐字相同，行为等价） */
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

/* ===== 底部输入 ===== */
.embed-foot {
  display: flex;
  gap: 8px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.85);
  border-top: 1px solid #eef2f8;
  backdrop-filter: blur(8px);
  flex-shrink: 0;
}
.embed-input {
  flex: 1;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  padding: 11px 14px;
  font-size: 13px;
  outline: none;
  background: #fff;
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.03);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.embed-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12);
}
.embed-input:disabled {
  background: #f1f5f9;
  color: #94a3b8;
}
.embed-send {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  border-radius: 14px;
  padding: 0 20px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.28);
  transition: transform 0.15s ease, box-shadow 0.15s ease, opacity 0.15s ease;
}
.embed-send:not(:disabled):hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.34);
}
.embed-send:disabled {
  background: #9ca3af;
  cursor: not-allowed;
  box-shadow: none;
}
.send-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.5);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
