<script setup lang="ts">
/**
 * 嵌入问数页顶栏（T7.3 自 EmbedChatView 拆出）：
 * 品牌标识 / 标题 / 外部用户标识 / 数据源下拉（多于 1 个时）/ 就绪徽标。
 */
import { useEmbedCtx } from './useEmbedChat'

const ctx = useEmbedCtx()
</script>

<template>
  <header class="embed-head">
    <span class="embed-logo">AI</span>
    <span class="embed-title">{{ ctx.title }}</span>
    <span v-if="ctx.extUser" class="embed-user-chip" :title="`外部用户标识：${ctx.extUser}`">体验账号 {{ ctx.extUser }}</span>
    <select v-if="ctx.dsOptions.length > 1" v-model.number="ctx.selectedDs" class="ds-picker">
      <option v-for="o in ctx.dsOptions" :key="o.id" :value="o.id">{{ o.name }}</option>
    </select>
    <span v-else-if="ctx.dsOptions.length === 1" class="embed-ds-name">{{ ctx.dsOptions[0].name }}</span>
    <span v-if="ctx.ready" class="embed-badge">智能问数</span>
  </header>
</template>

<style scoped>
/* ===== 顶栏：品牌渐变 ===== */
.embed-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: #fff;
  box-shadow: 0 2px 14px rgba(37, 99, 235, 0.22);
  flex-shrink: 0;
}
.embed-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.2);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.5px;
  box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.25);
}
.embed-title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.embed-ds-name {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.88);
  background: rgba(255, 255, 255, 0.16);
  border-radius: 10px;
  padding: 3px 10px;
  max-width: 160px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ds-picker {
  margin-left: auto;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 9px;
  padding: 4px 8px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  outline: none;
  max-width: 180px;
}
.ds-picker option {
  color: #1f2937;
}
.embed-badge {
  font-size: 12px;
  color: #1d4ed8;
  background: #fff;
  border-radius: 10px;
  padding: 2px 9px;
  font-weight: 600;
  margin-left: auto;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.12);
}
.ds-picker + .embed-badge {
  margin-left: 8px;
}

/* ===== 外部用户标识（?user=） ===== */
.embed-user-chip {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.9);
  background: rgba(255, 255, 255, 0.16);
  border-radius: 10px;
  padding: 2px 10px;
  max-width: 160px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
