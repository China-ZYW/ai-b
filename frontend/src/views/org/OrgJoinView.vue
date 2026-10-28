<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { publicInvitationApi, orgInvitationApi } from '@/api/orgInvitation'
import type { InvitationPreview, InvitationPreviewStatus } from '@/types'
import { formatDateTime } from '@/utils/format'
import { getToken } from '@/utils/storage'

/**
 * 组织邀请落地页（§9.2 P3，公开访问 /org/join?token=...）。
 *
 * 凭明文令牌匿名预览邀请概要（组织名/角色/留言/有效期/可用性），后端 GET /api/public/invitation/preview 免认证。
 * 接受链路（accept）已开放：已登录用户可点击「接受邀请」调用 POST /api/org/invitations/accept?token=xxx 加入组织。
 * 令牌无效/过期/撤销/席位已满时展示对应状态卡片，绝不泄露组织是否存在（INVALID 只回状态）。
 */
const route = useRoute()
const router = useRouter()
const token = String(route.query.token || '')

const loading = ref(true)
const status = ref<InvitationPreviewStatus | 'ERROR'>('INVALID')
const preview = ref<InvitationPreview | null>(null)
const loggedIn = computed(() => !!getToken())

const accepting = ref(false)
async function accept(): Promise<void> {
  if (!loggedIn.value) {
    goLogin()
    return
  }
  accepting.value = true
  try {
    const res = await orgInvitationApi.accept(token)
    ElMessage.success(res.message || `已加入「${res.tenantName || '组织'}」，正在进入应用…`)
    setTimeout(goApp, 800)
  } catch {
    // 失败由 request 拦截器统一提示（业务码 / 网络异常），此处仅收尾 loading
  } finally {
    accepting.value = false
  }
}

const ROLE_LABELS: Record<string, string> = {
  OWNER: '组织所有者',
  ADMIN: '组织管理员',
  DATA_STEWARD: '数据管理员',
  ANALYST: '分析师',
  VIEWER: '只读成员',
  BILLING: '计费联系人'
}
function roleLabel(code?: string | null): string {
  if (!code) return '成员'
  return ROLE_LABELS[code] || code
}
function channelLabel(c?: string | null): string {
  if (c === 'EMAIL') return '定向邮箱邀请'
  if (c === 'LINK') return '链接邀请'
  return c || '链接邀请'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await publicInvitationApi.preview(token)
    status.value = res.status
    preview.value = res
  } catch {
    status.value = 'ERROR'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!token) {
    status.value = 'INVALID'
    loading.value = false
    return
  }
  void load()
})

function goRegister(): void {
  void router.push({ path: '/register', query: { redirect: route.fullPath, inviteToken: token } })
}
function goLogin(): void {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}
function goApp(): void {
  void router.push('/app/chat')
}
</script>

<template>
  <div class="org-join">
    <div class="oj-glow glow-a" />
    <div class="oj-glow glow-b" />

    <header class="oj-header">
      <div class="brand">
        <div class="brand-logo"><el-icon :size="20"><DataAnalysis /></el-icon></div>
        <div class="brand-text">
          <span class="brand-name">AI 数据助理</span>
          <span class="brand-sub">组织邀请</span>
        </div>
      </div>
    </header>

    <main class="oj-body">
      <!-- 加载中 -->
      <div v-if="loading" class="state-card card">
        <el-skeleton :rows="4" animated />
      </div>

      <!-- 邀请有效 -->
      <div v-else-if="status === 'OK' && preview" class="invite-card card">
        <div class="invite-icon"><el-icon :size="30"><Promotion /></el-icon></div>
        <h1 class="invite-title">你被邀请加入</h1>
        <p class="org-name">{{ preview.tenantName || '一个组织' }}</p>

        <div class="invite-meta">
          <div class="meta-row">
            <span class="meta-label"><el-icon><UserFilled /></el-icon>加入角色</span>
            <el-tag type="primary" effect="light" round>{{ roleLabel(preview.roleCode) }}</el-tag>
          </div>
          <div class="meta-row">
            <span class="meta-label"><el-icon><Connection /></el-icon>邀请方式</span>
            <span class="meta-value">{{ channelLabel(preview.channel) }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label"><el-icon><Clock /></el-icon>有效期至</span>
            <span class="meta-value">
              {{ preview.expireAt ? formatDateTime(preview.expireAt) : '长期有效' }}
            </span>
          </div>
          <div v-if="preview.requireApproval === 1" class="meta-row">
            <span class="meta-label"><el-icon><Stamp /></el-icon>审批</span>
            <span class="meta-value">接受后需管理员审批</span>
          </div>
        </div>

        <p v-if="preview.message" class="invite-message">
          <el-icon><ChatLineSquare /></el-icon>
          <span>{{ preview.message }}</span>
        </p>

        <el-alert
          type="success"
          :closable="false"
          show-icon
          class="invite-tip"
          title="可在线接受邀请"
          description="点击「接受邀请」即可加入该组织并直接获得对应角色权限；若邀请需管理员审批，接受后将显示为「待审批」状态。"
        />

        <div class="invite-actions">
          <template v-if="loggedIn">
            <el-button type="primary" size="large" class="oj-btn" :loading="accepting" @click="accept">接受邀请</el-button>
            <el-button size="large" plain @click="goApp">进入应用</el-button>
          </template>
          <template v-else>
            <el-button type="primary" size="large" class="oj-btn" @click="goRegister">注册账号</el-button>
            <el-button size="large" plain @click="goLogin">已有账号，登录</el-button>
          </template>
        </div>
      </div>

      <!-- 已过期 -->
      <div v-else-if="status === 'EXPIRED'" class="state-card card">
        <div class="state-icon expired"><el-icon :size="30"><Timer /></el-icon></div>
        <h2 class="state-title">邀请链接已过期</h2>
        <p class="state-desc">
          该邀请已超过有效期<template v-if="preview?.tenantName">（{{ preview.tenantName }}）</template
          >，请联系邀请人重新发起。
        </p>
        <el-button v-if="!loggedIn" type="primary" plain @click="goLogin">去登录</el-button>
      </div>

      <!-- 已撤销 -->
      <div v-else-if="status === 'REVOKED'" class="state-card card">
        <div class="state-icon notfound"><el-icon :size="30"><CircleCloseFilled /></el-icon></div>
        <h2 class="state-title">邀请已被撤销</h2>
        <p class="state-desc">
          组织管理员已撤销该邀请<template v-if="preview?.tenantName">（{{ preview.tenantName }}）</template
          >，链接不再有效。
        </p>
        <el-button v-if="!loggedIn" type="primary" plain @click="goLogin">去登录</el-button>
      </div>

      <!-- 席位已满 -->
      <div v-else-if="status === 'EXHAUSTED'" class="state-card card">
        <div class="state-icon expired"><el-icon :size="30"><WarningFilled /></el-icon></div>
        <h2 class="state-title">邀请名额已用完</h2>
        <p class="state-desc">
          该邀请的可用席位已被占满<template v-if="preview?.tenantName">（{{ preview.tenantName }}）</template
          >，请联系邀请人获取新的链接。
        </p>
        <el-button v-if="!loggedIn" type="primary" plain @click="goLogin">去登录</el-button>
      </div>

      <!-- 令牌无效 -->
      <div v-else-if="status === 'INVALID'" class="state-card card">
        <div class="state-icon notfound"><el-icon :size="30"><WarningFilled /></el-icon></div>
        <h2 class="state-title">邀请链接无效</h2>
        <p class="state-desc">链接无效或已失效，请向邀请人确认最新的邀请链接。</p>
        <el-button v-if="!loggedIn" type="primary" plain @click="goLogin">去登录</el-button>
      </div>

      <!-- 访问异常 -->
      <div v-else class="state-card card">
        <div class="state-icon notfound"><el-icon :size="30"><CircleCloseFilled /></el-icon></div>
        <h2 class="state-title">访问失败</h2>
        <p class="state-desc">网络异常或服务暂不可用，请稍后重试。</p>
        <el-button type="primary" plain @click="load">重新加载</el-button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.org-join {
  position: relative;
  min-height: 100vh;
  background: var(--grad-auth-bg);
  overflow-x: hidden;
}
.oj-glow {
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
.oj-header {
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
.oj-body {
  position: relative;
  z-index: 2;
  max-width: 520px;
  margin: 0 auto;
  padding: 8px 24px 56px;
}

.invite-card {
  padding: 34px 32px;
  text-align: center;
}
.invite-icon {
  width: 66px;
  height: 66px;
  border-radius: 50%;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--grad-brand);
  box-shadow: var(--shadow-brand);
}
.invite-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-secondary);
  margin: 0 0 6px;
}
.org-name {
  font-size: 24px;
  font-weight: 800;
  color: var(--text-primary);
  margin: 0 0 22px;
  word-break: break-all;
}
.invite-meta {
  display: flex;
  flex-direction: column;
  gap: 12px;
  text-align: left;
  padding: 16px 18px;
  border-radius: 12px;
  background: var(--fill-soft, rgba(30, 90, 232, 0.05));
  margin-bottom: 18px;
}
.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.meta-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.meta-label .el-icon {
  color: var(--ikb-lighter);
}
.meta-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.invite-message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  text-align: left;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  padding: 12px 14px;
  border-left: 3px solid var(--ikb-lighter);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
  margin: 0 0 18px;
}
.invite-message .el-icon {
  margin-top: 2px;
  color: var(--ikb-lighter);
}
.invite-tip {
  text-align: left;
  margin-bottom: 22px;
}
.invite-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}
.oj-btn {
  min-width: 132px;
  height: 44px;
  font-weight: 600;
  background: var(--grad-btn);
  border: none;
}

.state-card {
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
</style>
