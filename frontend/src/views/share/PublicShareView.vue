<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { publicShareApi } from '@/api/share'
import ResultPanel from '@/components/chat/ResultPanel.vue'
import { fetchThemes } from '@/theme/dashboardTheme'
import type { ShareAccessStatus, SharedView } from '@/types'
import { formatDateTime } from '@/utils/format'
import { useActionGuard } from '@/utils/actionGuard'

const route = useRoute()
const token = String(route.params.token || '')

const loading = ref(true)
const status = ref<ShareAccessStatus | 'ERROR'>('NOT_FOUND')
const view = ref<SharedView | null>(null)
const password = ref('')
const { busy: submitting, run: runSubmit } = useActionGuard()
const wrongTip = ref(false)

async function access(pwd?: string): Promise<void> {
  loading.value = true
  wrongTip.value = false
  try {
    const res = await publicShareApi.access(token, pwd)
    status.value = res.status
    view.value = res.view ?? null
    if (res.status === 'PASSWORD_WRONG') wrongTip.value = true
  } catch {
    status.value = 'ERROR'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void fetchThemes(true) // 公开页免认证拉取全局内置主题（图表/结果样式配置化）
  if (!token) {
    status.value = 'NOT_FOUND'
    loading.value = false
    return
  }
  void access()
})

async function submitPassword(): Promise<void> {
  if (!password.value.trim()) return
  await runSubmit(async () => {
    await access(password.value.trim())
  })
}
</script>

<template>
  <div class="public-share">
    <div class="ps-glow glow-a" />
    <div class="ps-glow glow-b" />

    <header class="ps-header">
      <div class="brand">
        <div class="brand-logo"><el-icon :size="20"><DataAnalysis /></el-icon></div>
        <div class="brand-text">
          <span class="brand-name">AI 数据助理</span>
          <span class="brand-sub">查询结果分享</span>
        </div>
      </div>
    </header>

    <main class="ps-body">
      <!-- 加载中 -->
      <div v-if="loading" class="state-card card">
        <el-skeleton :rows="4" animated />
      </div>

      <!-- 正常展示 -->
      <div v-else-if="status === 'OK' && view" class="result-wrap">
        <div class="q-card card hairline-top">
          <h1 class="q-title">{{ view.question }}</h1>
          <div class="q-meta">
            <span class="meta-item"><el-icon><Calendar /></el-icon>分享于 {{ formatDateTime(view.createdAt) }}</span>
            <span class="meta-item">
              <el-icon><Clock /></el-icon>{{ view.expireAt ? `有效期至 ${formatDateTime(view.expireAt)}` : '永久有效' }}
            </span>
          </div>
        </div>

        <ResultPanel
          :result="view.result"
          :chart="view.chart ?? null"
          :watermark="view.watermark"
          readonly
        />

        <p class="ps-foot">此页面为只读快照，由 AI 数据助理生成 · 数据以分享时刻为准</p>
      </div>

      <!-- 需要密码 -->
      <div v-else-if="status === 'PASSWORD_REQUIRED' || status === 'PASSWORD_WRONG'" class="state-card card">
        <div class="state-icon lock"><el-icon :size="30"><Lock /></el-icon></div>
        <h2 class="state-title">该分享受密码保护</h2>
        <p class="state-desc">请输入访问密码以查看查询结果</p>
        <el-input
          v-model="password"
          type="password"
          size="large"
          placeholder="访问密码"
          show-password
          class="pwd-input"
          @keyup.enter="submitPassword"
        />
        <p v-if="wrongTip" class="wrong-tip">密码不正确，请重试</p>
        <el-button type="primary" size="large" class="pwd-btn" :loading="submitting" @click="submitPassword">
          查看结果
        </el-button>
      </div>

      <!-- 已过期 -->
      <div v-else-if="status === 'EXPIRED'" class="state-card card">
        <div class="state-icon expired"><el-icon :size="30"><Timer /></el-icon></div>
        <h2 class="state-title">分享链接已过期</h2>
        <p class="state-desc">该分享已超过有效期，请联系分享者重新生成链接。</p>
      </div>

      <!-- 不存在 -->
      <div v-else-if="status === 'NOT_FOUND'" class="state-card card">
        <div class="state-icon notfound"><el-icon :size="30"><WarningFilled /></el-icon></div>
        <h2 class="state-title">分享不存在</h2>
        <p class="state-desc">链接无效或已被删除。</p>
      </div>

      <!-- 访问异常 -->
      <div v-else class="state-card card">
        <div class="state-icon notfound"><el-icon :size="30"><CircleCloseFilled /></el-icon></div>
        <h2 class="state-title">访问失败</h2>
        <p class="state-desc">网络异常或服务暂不可用，请稍后重试。</p>
        <el-button type="primary" plain @click="access()">重新加载</el-button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.public-share {
  position: relative;
  min-height: 100vh;
  background: var(--grad-auth-bg);
  overflow-x: hidden;
}
.ps-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.4;
  pointer-events: none;
}
.glow-a {
  width: 460px;
  height: 460px;
  background: #00c2ff;
  top: -140px;
  right: -80px;
}
.glow-b {
  width: 520px;
  height: 520px;
  background: #1e5ae8;
  bottom: -180px;
  left: -120px;
}
.ps-header {
  position: relative;
  z-index: 2;
  padding: 22px 32px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-logo {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #fff, #cfe0ff);
  color: var(--ikb);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.brand-name {
  color: #fff;
  font-size: 17px;
  font-weight: 800;
}
.brand-sub {
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
}
.ps-body {
  position: relative;
  z-index: 2;
  max-width: 920px;
  margin: 0 auto;
  padding: 8px 24px 56px;
}

.result-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.q-card {
  padding: 24px 26px;
}
.q-title {
  font-size: 22px;
  font-weight: 800;
  margin: 0 0 12px;
  color: var(--text-primary);
  line-height: 1.4;
}
.q-meta {
  display: flex;
  gap: 22px;
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.meta-item .el-icon {
  color: var(--ikb-lighter);
}
.ps-foot {
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  margin: 4px 0 0;
}

.state-card {
  max-width: 440px;
  margin: 40px auto;
  padding: 40px 34px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.state-icon {
  width: 66px;
  height: 66px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
  color: #fff;
}
.state-icon.lock {
  background: var(--grad-brand);
  box-shadow: var(--shadow-brand);
}
.state-icon.expired {
  background: linear-gradient(135deg, #ff9f43, #ff6b6b);
}
.state-icon.notfound {
  background: linear-gradient(135deg, #8a94a6, #6b7488);
}
.state-title {
  font-size: 19px;
  font-weight: 800;
  margin: 0 0 8px;
}
.state-desc {
  font-size: 13.5px;
  color: var(--text-secondary);
  margin: 0 0 22px;
  line-height: 1.6;
}
.pwd-input {
  width: 100%;
  margin-bottom: 6px;
}
.wrong-tip {
  align-self: flex-start;
  color: var(--danger);
  font-size: 12.5px;
  margin: 4px 0 6px;
}
.pwd-btn {
  width: 100%;
  margin-top: 12px;
  height: 44px;
  font-weight: 600;
  background: var(--grad-btn);
  border: none;
}
</style>
