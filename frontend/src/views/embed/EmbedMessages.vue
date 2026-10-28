<script setup lang="ts">
/**
 * 嵌入问数页主体滚动区（T7.3 自 EmbedChatView 拆出）：
 * 空态引导插画 + 消息行（头像/气泡外壳）；气泡内容分别委托 EmbedBotLoading / EmbedBotBubble。
 * scroller 引用经回调 :ref 写回共享 ctx，供状态机 scrollBottom() 使用（行为与拆分前一致）。
 */
import { useEmbedCtx } from './useEmbedChat'
import EmbedBotBubble from './EmbedBotBubble.vue'
import EmbedBotLoading from './EmbedBotLoading.vue'

const ctx = useEmbedCtx()
</script>

<template>
  <div :ref="(el: unknown) => (ctx.scroller = el as HTMLElement | null)" class="embed-body">
    <!-- 中间空白：居中引导 + 插画 -->
    <div v-if="!ctx.messages.length" class="embed-empty">
      <div class="empty-art">
        <div class="empty-glow"></div>
        <div class="empty-orb"></div>
        <div class="empty-grid"></div>
      </div>
      <p class="empty-title">用一句话，就能问出数据</p>
      <p class="empty-sub">免登录直接对话，支持聚合、分组、排序、同比环比；结果自动以表格呈现</p>
    </div>

    <div v-for="(m, i) in ctx.messages" :key="i" class="msg-row" :class="m.role">
      <div class="msg-avatar" :class="m.role">
        <span v-if="m.role === 'bot'">AI</span>
        <span v-else>我</span>
      </div>
      <div v-if="m.role === 'user'" class="bubble user-bubble">{{ m.text }}</div>
      <div v-else class="bubble bot-bubble" :class="{ 'is-loading': m.loading }">
        <EmbedBotLoading v-if="m.loading" />
        <EmbedBotBubble v-else :msg="m" :idx="i" />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 主体 ===== */
.embed-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px 10px;
  display: flex;
  flex-direction: column;
}

/* 中间空白：居中引导 + 插画 */
.embed-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #6b7280;
  padding: 24px 12px;
}
.empty-art {
  position: relative;
  width: 132px;
  height: 120px;
  margin-bottom: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-glow {
  position: absolute;
  inset: -28px;
  background: radial-gradient(circle, rgba(37, 99, 235, 0.2), transparent 68%);
  filter: blur(8px);
}
.empty-orb {
  position: relative;
  width: 74px;
  height: 74px;
  border-radius: 24px;
  background: linear-gradient(135deg, #60a5fa 0%, #2563eb 100%);
  box-shadow: 0 14px 32px rgba(37, 99, 235, 0.36), inset 0 2px 6px rgba(255, 255, 255, 0.42);
  animation: float 3.4s ease-in-out infinite;
}
/* 数据卡意象：右下角叠放的小白卡 */
.empty-orb::after {
  content: '';
  position: absolute;
  right: 9px;
  bottom: 9px;
  width: 20px;
  height: 20px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: -22px -2px 0 -2px rgba(255, 255, 255, 0.55), -9px -18px 0 -4px rgba(255, 255, 255, 0.42);
}
.empty-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(37, 99, 235, 0.18) 1px, transparent 1px);
  background-size: 12px 12px;
  -webkit-mask: radial-gradient(circle, #000 28%, transparent 72%);
  mask: radial-gradient(circle, #000 28%, transparent 72%);
  opacity: 0.55;
}
@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}
.empty-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 7px;
  background: linear-gradient(135deg, #1d4ed8, #0ea5e3);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.empty-sub {
  font-size: 12.5px;
  color: #94a3b8;
  margin: 0;
  max-width: 290px;
  line-height: 1.65;
}

/* ===== 消息行：头像 + 气泡对话布局 ===== */
.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 16px;
}
.msg-row.user {
  flex-direction: row-reverse;
}
.msg-avatar {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.14);
}
.msg-avatar.bot {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
}
.msg-avatar.user {
  background: linear-gradient(135deg, #64748b, #475569);
}
.bubble {
  max-width: calc(100% - 36px);
  padding: 11px 14px;
  border-radius: 16px;
  font-size: 13.5px;
  line-height: 1.65;
  word-break: break-word;
}
.user-bubble {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  border-bottom-right-radius: 5px;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.22);
}
.bot-bubble {
  background: #fff;
  border: 1px solid #eef2f8;
  border-bottom-left-radius: 5px;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.05);
}
/* 加载中：气泡壳透明化，由跑马灯边框接管视觉（loading 内容组件在上层渲染） */
.bot-bubble.is-loading {
  background: transparent;
  border-color: transparent;
  box-shadow: none;
  padding: 0;
}
</style>
