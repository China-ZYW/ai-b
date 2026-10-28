<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { planLabel } from '@/utils/format'
import { usePerm } from '@/composables/usePerm'
import { useActionGuard } from '@/utils/actionGuard'
import { UI_THEME_OPTIONS, type UiThemeMode } from '@/theme/appTheme'

const userStore = useUserStore()
const { can } = usePerm()

const profileRef = ref<FormInstance>()
const { busy: saving, run: runSave } = useActionGuard()
const form = reactive<{ nickname: string; email: string }>({ nickname: '', email: '' })

const rules = reactive<FormRules>({
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  nickname: [{ max: 32, message: '昵称最长 32 字', trigger: 'blur' }]
})

onMounted(() => {
  void userStore.loadMe().catch(() => undefined)
  void userStore.refreshQuota()
  syncForm()
})

function syncForm(): void {
  form.nickname = userStore.profile?.nickname || ''
  form.email = userStore.profile?.email || ''
}

/**
 * 主题偏好即时生效，不走「保存修改」按钮。
 *
 * <p>理由：主题切换必须有即时反馈，而资料表单是"填完一起提交"的模型——若把主题塞进表单，
 * 用户选了深色却看不到变化（要等点保存），会以为坏了。故这里单独走 {@code setUiTheme}，
 * 与 nickname/email 的提交式编辑分开。
 */
async function onPickTheme(m: UiThemeMode): Promise<void> {
  await userStore.setUiTheme(m)
}

const plan = computed(() => userStore.plan)
const quota = computed(() => userStore.quota)
const unlimited = computed(() => !!quota.value && quota.value.limit < 0)
/** 配额使用百分比（不限量时为 0）。 */
const usedPercent = computed(() => {
  const q = quota.value
  if (!q || q.limit < 0) return 0
  return Math.min(100, Math.round((q.used / q.limit) * 100))
})

async function onSave(): Promise<void> {
  if (!profileRef.value) return
  const valid = await profileRef.value.validate().catch(() => false)
  if (!valid) return
  await runSave(async () => {
    try {

      await userStore.updateProfile({
        nickname: form.nickname.trim() || undefined,
        email: form.email.trim() || undefined
      })
      ElMessage.success('资料已更新')
    } catch {
      // 拦截器已提示
    }
  })
}
</script>

<template>
  <div class="profile-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">个人中心</h2>
        <p class="ph-desc">管理你的账号资料、套餐与今日问数配额。</p>
      </div>
    </div>

    <div class="profile-grid">
      <!-- 账号概览 -->
      <div class="overview card hairline-top">
        <div class="ov-avatar">{{ userStore.displayName.charAt(0).toUpperCase() }}</div>
        <h3 class="ov-name">{{ userStore.displayName }}</h3>
        <p class="ov-username">@{{ userStore.profile?.username || '—' }}</p>
        <el-tag class="ov-plan" type="primary" effect="dark" round>
          <el-icon><Medal /></el-icon> {{ planLabel(plan) }}
        </el-tag>

        <div class="ov-stats">
          <div class="stat">
            <span class="stat-label">租户 ID</span>
            <span class="stat-value">{{ userStore.profile?.tenantId ?? '—' }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">用户 ID</span>
            <span class="stat-value">{{ userStore.profile?.id ?? '—' }}</span>
          </div>
        </div>
      </div>

      <!-- 配额 -->
      <div class="quota-card card">
        <div class="qc-head">
          <span class="qc-title"><el-icon><Odometer /></el-icon> 今日问数配额</span>
          <el-button size="small" text @click="userStore.refreshQuota()">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>

        <template v-if="quota">
          <div v-if="unlimited" class="unlimited">
            <el-icon :size="30"><CircleCheckFilled /></el-icon>
            <div>
              <p class="ul-title">不限量问数</p>
              <p class="ul-desc">当前套餐 {{ planLabel(quota.plan) }} 不限每日次数</p>
            </div>
          </div>
          <template v-else>
            <div class="quota-numbers">
              <span class="qn-used">{{ quota.used }}</span>
              <span class="qn-sep">/</span>
              <span class="qn-limit">{{ quota.limit }}</span>
              <span class="qn-unit">次</span>
            </div>
            <el-progress
              :percentage="usedPercent"
              :stroke-width="12"
              :color="usedPercent >= 90 ? '#EA5455' : '#002FA7'"
              striped
            />
            <p class="quota-remaining">今日还可问 {{ quota.remaining }} 次</p>
          </template>
        </template>
        <el-skeleton v-else :rows="2" animated />
      </div>

      <!-- 资料编辑 -->
      <div class="edit-card card">
        <h3 class="ec-title">账号资料</h3>
        <el-form ref="profileRef" :model="form" :rules="rules" label-width="72px">
          <el-form-item label="用户名">
            <el-input :model-value="userStore.profile?.username || ''" disabled />
          </el-form-item>
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" placeholder="展示名称（可选）" maxlength="32" clearable />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="用于接收通知（可选）" clearable />
          </el-form-item>
          <el-form-item>
            <el-button @click="syncForm">重置</el-button>
            <el-button v-if="can('profile:self:write')" type="primary" :loading="saving" @click="onSave">保存修改</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 界面主题：账号级持久化（存在后端 app_user.ui_theme，换设备/浏览器后仍生效） -->
      <div class="theme-card card">
        <h3 class="ec-title">
          <el-icon><Moon /></el-icon> 界面主题
        </h3>
        <p class="tc-desc">
          控制产品外壳（侧栏 / 顶栏 / 卡片 / 表格）的明暗，随账号保存。「跟随系统」会实时跟随操作系统的深浅设置。
        </p>
        <el-radio-group :model-value="userStore.uiTheme" @change="(v: UiThemeMode) => onPickTheme(v)">
          <el-radio-button v-for="o in UI_THEME_OPTIONS" :key="o.value" :value="o.value">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>
        <p class="tc-note muted">
          注：大屏 / 驾驶舱画布的配色由各自的「主题」设置决定（深色外壳下也可以放浅色大屏），与本项互不影响。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-view {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 32px;
}
.page-head {
  margin-bottom: 20px;
}
.ph-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0 0 4px;
}
.ph-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.profile-grid {
  display: grid;
  grid-template-columns: 300px 1fr;
  grid-template-rows: auto auto;
  gap: 18px;
}
.overview {
  grid-row: span 2;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.ov-avatar {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: var(--grad-brand);
  color: #fff;
  font-size: 32px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
  margin-bottom: 14px;
}
.ov-name {
  font-size: 19px;
  font-weight: 800;
  margin: 0 0 2px;
}
.ov-username {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 12px;
}
.ov-plan {
  margin-bottom: 22px;
}
.ov-stats {
  width: 100%;
  display: flex;
  border-top: 1px solid var(--border-light);
  padding-top: 16px;
}
.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label {
  font-size: 11.5px;
  color: var(--text-placeholder);
}
.stat-value {
  font-size: 16px;
  font-weight: 700;
  color: var(--ikb);
}

.quota-card,
.edit-card {
  padding: 20px 22px;
}
.qc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.qc-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 15px;
  font-weight: 700;
}
.qc-title .el-icon {
  color: var(--ikb);
}
.unlimited {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  color: var(--success);
}
.ul-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}
.ul-desc {
  margin: 3px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.quota-numbers {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 12px;
}
.qn-used {
  font-size: 34px;
  font-weight: 800;
  color: var(--ikb);
  line-height: 1;
}
.qn-sep {
  font-size: 22px;
  color: var(--text-placeholder);
}
.qn-limit {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-regular);
}
.qn-unit {
  font-size: 13px;
  color: var(--text-secondary);
  margin-left: 4px;
}
.quota-remaining {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.ec-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 18px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.ec-title .el-icon {
  color: var(--ikb);
}
.theme-card {
  padding: 20px 22px;
}
.tc-desc {
  margin: -8px 0 16px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.tc-note {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
@media (max-width: 900px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
  .overview {
    grid-row: auto;
  }
}
</style>
