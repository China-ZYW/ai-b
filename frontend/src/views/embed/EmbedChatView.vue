<script setup lang="ts">
import { onMounted } from 'vue'
import { useEmbedChat } from './useEmbedChat'
import EmbedHead from './EmbedHead.vue'
import EmbedMessages from './EmbedMessages.vue'
import EmbedComposer from './EmbedComposer.vue'
import EmbedHistory from './EmbedHistory.vue'

/**
 * 对话问数嵌入页（第三方页面以 iframe 免登录嵌入）。
 *
 * 凭 URL 中的 token 匿名调用 /api/public/chat-embed/**，复用后端问数主链路（含只读校验、
 * 字段脱敏与质量校验）。页面刻意不带侧边栏/顶栏等外围框架，撑满 iframe 便于被嵌入。
 *
 * T7.3 拆分后本文件只留编排：状态机在 ./embed/useEmbedChat（内部完成 provide），
 * 顶栏/主体/输入区/历史面板各自成件；致命错误分支留在本页。
 */
const ctx = useEmbedChat()

onMounted(ctx.init)
</script>

<template>
  <div class="embed-root">
    <EmbedHead />

    <!-- 链接无效 / 已过期 / 站点未授权 -->
    <div v-if="ctx.fatal" class="embed-fatal">
      <div class="fatal-icon">!</div>
      <div class="fatal-text">{{ ctx.fatal }}</div>
    </div>

    <template v-else>
      <EmbedMessages />
      <EmbedComposer />
    </template>

    <!-- 右侧悬浮：该外部用户历史（仅嵌入方传入 ?user= 时展示） -->
    <EmbedHistory />
  </div>
</template>

<style scoped>
.embed-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  /* 右侧悬浮历史按钮/面板为 absolute 定位，此处建立包含块，避免相对整屏定位而跑出 iframe */
  position: relative;
  background:
    radial-gradient(1100px 460px at 50% -12%, #e9f0ff 0%, #f5f8fd 48%, #eef2f8 100%);
  color: #1f2937;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow: hidden;
}

/* ===== 致命错误 ===== */
.embed-fatal {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #6b7280;
}
.fatal-icon {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #fee2e2;
  color: #dc2626;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
</style>
