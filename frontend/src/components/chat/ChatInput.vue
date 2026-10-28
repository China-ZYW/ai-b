<script setup lang="ts">
import { ref, watch } from 'vue'

/**
 * 问数输入栏：多行输入 + 发送 / 停止按钮。
 * v-model 绑定问题文本；发送后清空输入框。
 * 推荐问法由问数页空态的中央卡片承担，输入栏不再重复展示快捷问法 chips。
 */
const props = defineProps<{
  modelValue: string
  sending?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'send', v: string): void
  (e: 'stop'): void
}>()

const text = ref(props.modelValue)

// 父组件通过 v-model 变更问题文本时（点击空态推荐卡片、收藏页深链 ?q= 预填等）同步到内部输入框；
// 否则 text 只在初始化时读取一次 modelValue，外部更新不会反映到文本域——推荐卡片点击“无效”（问题1）。
watch(() => props.modelValue, (v) => {
  if (v !== text.value) text.value = v
})

function sync(v: string): void {
  text.value = v
  emit('update:modelValue', v)
}

function onInput(e: Event): void {
  sync((e.target as HTMLTextAreaElement).value)
}

function submit(): void {
  const q = text.value.trim()
  if (!q || props.sending || props.disabled) return
  emit('send', q)
  sync('')
}

function onKeydown(e: KeyboardEvent): void {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

</script>

<template>
  <div class="chat-input">
    <div class="input-bar" :class="{ disabled }">
      <el-icon class="leading-icon" :size="18"><ChatDotRound /></el-icon>
      <textarea
        class="ta"
        :value="text"
        rows="1"
        :disabled="disabled"
        placeholder="用一句话描述你想查询的数据，例如：每个地区的已支付订单总金额是多少"
        @input="onInput"
        @keydown="onKeydown"
      />
      <el-button
        v-if="!sending"
        class="send-btn"
        type="primary"
        round
        :disabled="disabled || !text.trim()"
        @click="submit"
      >
        <el-icon><Promotion /></el-icon> 发送
      </el-button>
      <el-button v-else class="send-btn stop" round @click="emit('stop')">
        <el-icon><VideoPause /></el-icon> 停止
      </el-button>
    </div>
    <p class="hint">Enter 发送 · Shift + Enter 换行 · 结果由 AI 生成，请核对关键数据</p>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 10px 20px 16px;
}
.input-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 12px 10px 16px;
  background: var(--bg-card);
  border: 1.5px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.input-bar:focus-within {
  border-color: var(--ikb);
  box-shadow: 0 0 0 3px rgba(0, 47, 167, 0.1);
}
.input-bar.disabled {
  opacity: 0.6;
}
.leading-icon {
  color: var(--ikb);
  margin-bottom: 8px;
}
.ta {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 14.5px;
  line-height: 1.6;
  max-height: 132px;
  min-height: 24px;
  padding: 6px 0;
  font-family: inherit;
  color: var(--text-primary);
  background: transparent;
}
.ta::placeholder {
  color: var(--text-placeholder);
}
.send-btn {
  margin-bottom: 2px;
  height: 38px;
  padding: 0 20px;
  font-weight: 600;
}
.send-btn.stop {
  background: var(--danger);
  border: none;
  color: #fff;
}
.hint {
  margin: 8px 2px 0;
  font-size: 11.5px;
  color: var(--text-placeholder);
  text-align: center;
}
</style>
