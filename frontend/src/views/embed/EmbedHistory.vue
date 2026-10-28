<script setup lang="ts">
/**
 * 嵌入问数页右侧悬浮历史（T7.3 自 EmbedChatView 拆出）：
 * 历史 FAB 按钮 + 滑入面板（会话列表，点击续接）。仅嵌入方传入 ?user= 时展示。
 * fragment 多根：fab/panel 仍是 .embed-root（position:relative 包含块）的直接子节点，
 * absolute 定位基准与拆分前一致。
 */
import { useEmbedCtx } from './useEmbedChat'

const ctx = useEmbedCtx()
</script>

<template>
  <div
    v-if="ctx.extUser"
    class="hist-fab"
    :class="{ active: ctx.showHistory }"
    :title="ctx.showHistory ? '收起历史' : '查看我的历史'"
    @click="ctx.showHistory = !ctx.showHistory"
  >
    <span class="hist-fab-icon">🕘</span>
    <span v-if="ctx.conversations.length && !ctx.showHistory" class="hist-fab-badge">{{ ctx.conversations.length }}</span>
  </div>
  <transition name="slide">
    <aside v-if="ctx.extUser && ctx.showHistory" class="hist-panel">
      <div class="hist-head">
        <span class="hist-head-title">我的历史</span>
        <span class="hist-head-sub">点击可续接该对话</span>
        <button
          class="hist-refresh"
          type="button"
          :disabled="ctx.historyLoading"
          :title="ctx.historyLoading ? '刷新中…' : '刷新历史'"
          @click="ctx.loadHistory(true)"
        >
          <span class="hist-refresh-icon" :class="{ spinning: ctx.historyLoading }">⟳</span>
        </button>
        <button class="hist-close" type="button" @click="ctx.showHistory = false">×</button>
      </div>
      <div class="hist-body">
        <div v-if="ctx.historyLoading" class="hist-empty">加载中…</div>
        <div v-else-if="!ctx.conversations.length" class="hist-empty">暂无历史对话</div>
        <div
          v-for="c in ctx.conversations"
          :key="c.conversationId"
          class="conv-item"
          :class="{ current: c.conversationId === ctx.conversationId }"
          @click="ctx.openConversation(c.conversationId)"
        >
          <div class="conv-avatar">💬</div>
          <div class="conv-main">
            <div class="conv-top">
              <span class="conv-title">{{ c.title }}</span>
              <span class="conv-time">{{ ctx.relTime(c.lastAt) }}</span>
            </div>
            <div class="conv-sub">
              <span class="conv-preview">{{ c.preview }}</span>
              <span class="conv-count">{{ c.count }} 条</span>
            </div>
          </div>
        </div>
      </div>
    </aside>
  </transition>
</template>

<style scoped>
/* ===== 右侧悬浮历史 ===== */
.hist-fab {
  position: absolute;
  top: 50%;
  right: 14px;
  transform: translateY(-50%);
  z-index: 20;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(37, 99, 235, 0.32);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.hist-fab:hover {
  transform: translateY(-50%) scale(1.06);
  box-shadow: 0 8px 22px rgba(37, 99, 235, 0.4);
}
.hist-fab.active {
  right: 314px;
}
.hist-fab-icon {
  font-size: 18px;
  line-height: 1;
}
.hist-fab-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
}

.hist-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 300px;
  z-index: 19;
  background: #fff;
  border-left: 1px solid #e9eef5;
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.08);
  display: flex;
  flex-direction: column;
}
.hist-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid #eef1f5;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
  flex-shrink: 0;
}
.hist-head-title {
  font-size: 14px;
  font-weight: 600;
}
.hist-head-sub {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.8);
  flex: 1;
}
.hist-close {
  border: none;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}
.hist-refresh {
  border: none;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  line-height: 1;
  transition: background 0.15s ease;
}
.hist-refresh:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.32);
}
.hist-refresh:disabled {
  opacity: 0.65;
  cursor: default;
}
.hist-refresh-icon {
  display: inline-block;
}
.hist-refresh-icon.spinning {
  animation: hist-spin 0.8s linear infinite;
}
@keyframes hist-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .hist-refresh-icon.spinning {
    animation: none;
  }
}
.hist-body {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px;
}
.hist-empty {
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  padding: 24px 0;
}
/* —— 会话列表（聊天记录形态）：一项 = 一个会话 —— */
.conv-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 11px;
  border-radius: 12px;
  margin-bottom: 8px;
  cursor: pointer;
  background: #f7f9fc;
  border: 1px solid transparent;
  transition: all 0.15s ease;
}
.conv-item:hover {
  background: #f5f8ff;
  border-color: #2563eb;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.12);
}
.conv-item.current {
  background: #eef4ff;
  border-color: #2563eb;
}
.conv-avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  background: linear-gradient(135deg, #e8efff, #f3f6ff);
}
.conv-main {
  flex: 1;
  min-width: 0;
}
.conv-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.conv-title {
  font-size: 12.8px;
  font-weight: 700;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-time {
  flex: none;
  font-size: 10.5px;
  color: #9ca3af;
}
.conv-sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 3px;
}
.conv-preview {
  font-size: 12px;
  color: #64748b;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-count {
  flex: none;
  font-size: 10px;
  color: #64748b;
  background: #e8edf7;
  border-radius: 999px;
  padding: 1px 7px;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.22s ease, opacity 0.22s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(20px);
  opacity: 0;
}
</style>
