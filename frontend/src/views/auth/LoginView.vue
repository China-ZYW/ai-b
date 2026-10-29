<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import type { LoginRequest } from '@/types'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive<LoginRequest>({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    // 显式 redirect 优先；否则按角色落地（超管→/admin，普通→/chat）
    const redirect = (route.query.redirect as string) || userStore.homePath
    await router.push(redirect)
  } catch {
    // 密码错误等场景：axios 拦截器已弹出具体提示（如「用户名或密码错误」），此处不再二次提示
  } finally {
    loading.value = false
  }
}

function fillDemo(): void {
  // 开源仓不预置任何硬编码口令；仅预填演示用户名，口令需从后端首次启动日志 WARN 行取。
  form.username = 'admin'
}

/** 生成第 i 滴雨的确定性"伪随机"参数：分布均匀、每次渲染一致（用负 delay 让开场就在半空落雨）。 */
function dropStyle(i: number): Record<string, string> {
  const left = (i * 137.5) % 100
  const duration = 0.9 + ((i * 53) % 70) / 100
  const delay = ((i * 29) % 200) / 100
  const opacity = 0.22 + ((i * 17) % 40) / 100
  const height = 56 + ((i * 31) % 60)
  return {
    left: `${left.toFixed(2)}%`,
    animationDuration: `${duration.toFixed(2)}s`,
    animationDelay: `-${delay.toFixed(2)}s`,
    opacity: opacity.toFixed(2),
    height: `${height}px`
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-glow glow-a" />
    <div class="auth-glow glow-b" />

    <!-- 雨滴动画层（不挡交互） -->
    <div class="auth-rain" aria-hidden="true">
      <span v-for="i in 32" :key="i" class="drop" :style="dropStyle(i)" />
    </div>

    <!-- 湖面波纹动画层（置于卡片之后，不挡交互） -->
    <div class="auth-lake" aria-hidden="true">
      <div class="lake-surface" />
      <span class="ripple r1" />
      <span class="ripple r2" />
      <span class="ripple r3" />
      <span class="ripple r4" />
      <span class="ripple r5" />
    </div>

    <div class="auth-card">
      <!-- 左侧品牌区 -->
      <div class="auth-brand">
        <div class="brand-badge">
          <el-icon :size="26"><DataAnalysis /></el-icon>
        </div>
        <h2 class="brand-title">AI 数据助理</h2>
        <p class="brand-desc">用一句话，问出你想要的数据</p>
        <ul class="brand-points">
          <li><el-icon><MagicStick /></el-icon> 自然语言转 SQL，智能图表推荐</li>
          <li><el-icon><ChatDotRound /></el-icon> 多轮对话，理解你的追问</li>
          <li><el-icon><Lock /></el-icon> 多租户隔离，只读安全护栏</li>
        </ul>
      </div>

      <!-- 右侧表单区 -->
      <div class="auth-form">
        <h3 class="form-title">欢迎回来</h3>
        <p class="form-sub">登录以开始你的对话式问数</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="'User'" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="'Lock'"
              show-password
            />
          </el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" @click="onSubmit">
            登 录
          </el-button>
        </el-form>

        <div class="form-footer">
          <span class="muted">还没有账号？</span>
          <router-link to="/register" class="link">立即注册</router-link>
        </div>
        <div class="demo-hint" @click="fillDemo">
          <el-icon><InfoFilled /></el-icon> 演示账号：admin（口令请取后端首次启动日志 WARN 行随机值，或预先在 backend/local.env 写入 AIBI_DEMO_ADMIN_PASSWORD）
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
  left: -80px;
}
.glow-b {
  width: 520px;
  height: 520px;
  background: #1e5ae8;
  bottom: -160px;
  right: -100px;
}

/* 雨滴（斜落细光线，速度/疏密/透明度逐滴伪随机） */
.auth-rain {
  position: absolute;
  inset: 0;
  z-index: 1;
  overflow: hidden;
  pointer-events: none;
}
.drop {
  position: absolute;
  top: -14%;
  width: 1.5px;
  border-radius: 2px;
  background: linear-gradient(
    180deg,
    rgba(190, 228, 255, 0) 0%,
    rgba(170, 220, 255, 0.85) 65%,
    rgba(190, 228, 255, 0) 100%
  );
  transform: rotate(9deg);
  animation: rainfall linear infinite;
}
@keyframes rainfall {
  from { transform: rotate(9deg) translateY(0); }
  to { transform: rotate(9deg) translateY(135vh); }
}

/* 湖面波纹 */
.auth-lake {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 40%;
  z-index: 1;
  overflow: hidden;
  pointer-events: none;
}
.lake-surface {
  position: absolute;
  left: -12%;
  right: -12%;
  bottom: 0;
  height: 62%;
  background: linear-gradient(180deg, rgba(0, 194, 255, 0) 0%, rgba(0, 120, 255, 0.16) 45%, rgba(0, 80, 200, 0.32) 100%);
  border-radius: 46% 54% 0 0 / 20% 22% 0 0;
  animation: lakeSway 9s ease-in-out infinite alternate;
}
.ripple {
  position: absolute;
  bottom: 10%;
  left: 50%;
  width: 46px;
  height: 46px;
  margin-left: -23px;
  border: 1.5px solid rgba(140, 205, 255, 0.55);
  border-radius: 50%;
  transform: scale(0.2);
  opacity: 0;
  animation: ripple 7s linear infinite;
}
.r1 { left: 50%; }
.r2 { left: 28%; animation-delay: 1.4s; }
.r3 { left: 72%; animation-delay: 2.8s; }
.r4 { left: 40%; bottom: 4%; animation-delay: 4.2s; }
.r5 { left: 64%; bottom: 2%; animation-delay: 5.6s; }
@keyframes ripple {
  0% { transform: scale(0.2); opacity: 0.7; }
  100% { transform: scale(10); opacity: 0; }
}
@keyframes lakeSway {
  0% { transform: translateX(-3%) scaleY(1); }
  100% { transform: translateX(3%) scaleY(1.07); }
}
@media (prefers-reduced-motion: reduce) {
  .drop, .ripple, .lake-surface { animation: none; }
}

.auth-card {
  position: relative;
  z-index: 2;
  display: flex;
  width: 880px;
  max-width: calc(100vw - 40px);
  min-height: 520px;
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: 0 30px 80px rgba(0, 12, 48, 0.45);
}

/* 左侧品牌 */
.auth-brand {
  flex: 1;
  padding: 46px 40px;
  background: linear-gradient(160deg, rgba(0, 31, 112, 0.92), rgba(0, 47, 167, 0.82));
  backdrop-filter: blur(6px);
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
  font-size: 30px;
  font-weight: 800;
  margin: 0 0 8px;
  letter-spacing: 1px;
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

/* 右侧表单 */
.auth-form {
  width: 400px;
  flex-shrink: 0;
  padding: 52px 42px;
  background: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.form-title {
  font-size: 24px;
  font-weight: 800;
  margin: 0 0 6px;
  color: var(--text-primary);
}
.form-sub {
  font-size: 13.5px;
  color: var(--text-secondary);
  margin: 0 0 28px;
}
.submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 46px;
  font-size: 15px;
  letter-spacing: 4px;
}
.form-footer {
  margin-top: 20px;
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
.demo-hint {
  margin-top: 22px;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  background: var(--ikb-ghost);
  color: var(--ikb);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: background 0.2s ease;
}
.demo-hint:hover {
  background: var(--ikb-pale);
}

@media (max-width: 760px) {
  .auth-brand {
    display: none;
  }
  .auth-card {
    width: 400px;
  }
}
</style>
