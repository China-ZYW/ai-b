<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

interface RegisterForm {
  username: string
  password: string
  confirm: string
  nickname: string
  email: string
}

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<RegisterForm>({
  username: '',
  password: '',
  confirm: '',
  nickname: '',
  email: ''
})

const rules = reactive<FormRules>({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度需 3-32', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度需 6-64', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_r, value: string, cb) => {
        if (value !== form.password) cb(new Error('两次输入的密码不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  email: [
    { required: true, message: '请填写邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
})

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const inviteToken = (route.query.inviteToken as string) || undefined
    await userStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined,
      email: form.email || undefined,
      inviteToken
    })
    // 邀请注册：已加入对方组织，直接进应用；自助注册：进入刚开通的工作区
    ElMessage.success(inviteToken ? '已加入邀请的组织，正在进入应用…' : '注册成功，已为你开通工作区')
    const redirect = inviteToken ? '/app/chat' : ((route.query.redirect as string) || '/app/chat')
    await router.push(redirect)
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-glow glow-a" />
    <div class="auth-glow glow-b" />

    <div class="auth-card">
      <div class="auth-brand">
        <div class="brand-badge">
          <el-icon :size="26"><DataAnalysis /></el-icon>
        </div>
        <h2 class="brand-title">开启你的工作区</h2>
        <p class="brand-desc">一次注册，即拥有独立的租户空间</p>
        <ul class="brand-points">
          <li><el-icon><OfficeBuilding /></el-icon> 自助开通免费套餐 / POOL 隔离</li>
          <li><el-icon><Coin /></el-icon> 接入多种数据源，只读安全访问</li>
          <li><el-icon><TrendCharts /></el-icon> 问数即得图表，一键分享</li>
        </ul>
      </div>

      <div class="auth-form">
        <h3 class="form-title">创建账号</h3>
        <p class="form-sub">填写信息，立即开始对话式问数</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名（3-32 位）" :prefix-icon="'User'" clearable />
          </el-form-item>
          <el-form-item prop="nickname">
            <el-input v-model="form.nickname" placeholder="昵称（可选）" :prefix-icon="'Avatar'" clearable />
          </el-form-item>
          <el-form-item prop="email">
            <el-input v-model="form.email" placeholder="邮箱（用于登录与接收通知）" :prefix-icon="'Message'" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码（6-64 位）" :prefix-icon="'Lock'" show-password />
          </el-form-item>
          <el-form-item prop="confirm">
            <el-input v-model="form.confirm" type="password" placeholder="确认密码" :prefix-icon="'Lock'" show-password />
          </el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" @click="onSubmit">
            注 册
          </el-button>
        </el-form>

        <div class="form-footer">
          <span class="muted">已有账号？</span>
          <router-link to="/login" class="link">返回登录</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  position: relative;
  height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--grad-auth-bg);
  overflow: hidden;
}
.auth-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
  pointer-events: none;
}
.glow-a {
  width: 460px;
  height: 460px;
  background: #00c2ff;
  top: -120px;
  right: -80px;
}
.glow-b {
  width: 520px;
  height: 520px;
  background: #1e5ae8;
  bottom: -160px;
  left: -100px;
}
.auth-card {
  position: relative;
  z-index: 2;
  display: flex;
  width: 900px;
  max-width: calc(100vw - 40px);
  min-height: 580px;
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: 0 30px 80px rgba(0, 12, 48, 0.45);
}
.auth-brand {
  flex: 1;
  padding: 46px 40px;
  background: linear-gradient(160deg, rgba(0, 31, 112, 0.92), rgba(0, 47, 167, 0.82));
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand-badge {
  width: 54px;
  height: 54px;
  border-radius: 16px;
  background: linear-gradient(135deg, #fff, #cfe0ff);
  color: var(--ikb);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 22px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
}
.brand-title {
  font-size: 28px;
  font-weight: 800;
  margin: 0 0 8px;
}
.brand-desc {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.82);
  margin: 0 0 30px;
}
.brand-points {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.9);
}
.brand-points .el-icon {
  color: #7fd4ff;
  font-size: 17px;
}
.auth-form {
  width: 420px;
  flex-shrink: 0;
  padding: 44px 42px;
  background: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.form-title {
  font-size: 24px;
  font-weight: 800;
  margin: 0 0 6px;
}
.form-sub {
  font-size: 13.5px;
  color: var(--text-secondary);
  margin: 0 0 22px;
}
.submit-btn {
  width: 100%;
  margin-top: 4px;
  height: 46px;
  font-size: 15px;
  letter-spacing: 4px;
}
.form-footer {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
}
.link {
  color: var(--ikb);
  font-weight: 600;
  margin-left: 4px;
}
.link:hover {
  text-decoration: underline;
}
@media (max-width: 780px) {
  .auth-brand {
    display: none;
  }
  .auth-card {
    width: 420px;
  }
}
</style>
