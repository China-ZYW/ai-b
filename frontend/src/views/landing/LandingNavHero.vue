<script setup lang="ts">
/**
 * 落地页顶部导航 + Hero（T7.3 自 LandingView 拆出）。
 * 导航锚点滚动为页内 DOM 行为；试用/登录跳转逻辑自持（未登录跳登录页，已登录直达产品）。
 */
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { navItems } from './landingContent'

const router = useRouter()
const userStore = useUserStore()

function scrollTo(id: string) {
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 试用 / 进入控制台：未登录跳登录页，已登录直达产品。 */
function primaryAction() {
  if (userStore.isLoggedIn) router.push('/app/chat')
  else router.push('/login')
}
function goLogin() {
  router.push('/login')
}
</script>

<template>
  <!-- fragment 多根：nav/hero 仍是 .landing 直接子节点，保持 .nav sticky 的祖先布局与原实现一致 -->
  <header class="nav">
      <div class="nav-inner">
        <div class="logo" @click="scrollTo('top')">
          <span class="logo-mark">AI</span>
          <span class="logo-text">AI 数据助理</span>
        </div>
        <nav class="nav-links">
          <a v-for="n in navItems" :key="n.id" href="javascript:void(0)" @click="scrollTo(n.id)">{{ n.label }}</a>
        </nav>
        <div class="nav-actions">
          <button class="btn-ghost" @click="goLogin">登录</button>
          <button class="btn-primary" @click="primaryAction">免费试用</button>
        </div>
      </div>
    </header>

  <!-- ============ Hero ============ -->
  <section id="top" class="hero">
    <div class="hero-inner">
      <span class="badge">对话式问数 · 让数据触手可及</span>
      <h1 class="hero-title">用一句话，<br />问出你想要的数据</h1>
      <p class="hero-sub">
        AI 数据助理把自然语言变成可信的 SQL 与图表。接入业务库，全员零代码取数，
        从对话问数到智能驾驶舱，一站式完成数据分析闭环。
      </p>
      <div class="hero-cta">
        <button class="btn-primary lg" @click="primaryAction">免费试用</button>
        <button class="btn-outline lg" @click="scrollTo('features')">了解产品</button>
      </div>
      <p class="hero-trust">已服务数据分析、运营、增长与管理者团队</p>
    </div>
  </section>
</template>

<style scoped>
/* ---------- 顶部导航 ---------- */
.nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(15, 24, 48, 0.06);
}
.nav-inner {
  max-width: 1160px;
  margin: 0 auto;
  height: 64px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
.logo-mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #2f6bff, #00164f);
  color: #fff;
  font-weight: 800;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.5px;
}
.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: #0f1830;
  white-space: nowrap;
}
.nav-links {
  display: flex;
  gap: 28px;
}
.nav-links a {
  color: #4a5675;
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: color 0.2s ease;
}
.nav-links a:hover {
  color: #2f6bff;
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ---------- 按钮（导航/Hero 用；其他 section 组件各自自持所需部分） ---------- */
.btn-primary,
.btn-outline,
.btn-ghost {
  font-family: inherit;
  cursor: pointer;
  border-radius: 10px;
  font-weight: 600;
  transition: all 0.2s ease;
}
.btn-primary {
  background: linear-gradient(135deg, #2f6bff, #00164f);
  color: #fff;
  border: none;
  padding: 9px 18px;
  font-size: 14px;
  box-shadow: 0 6px 18px rgba(47, 107, 255, 0.28);
}
.btn-primary:hover {
  filter: brightness(1.08);
  transform: translateY(-1px);
}
.btn-primary.lg {
  padding: 13px 28px;
  font-size: 15px;
}
.btn-outline {
  background: #fff;
  color: #2f6bff;
  border: 1.5px solid #2f6bff;
  padding: 9px 18px;
  font-size: 14px;
}
.btn-outline:hover {
  background: #eef3ff;
}
.btn-outline.lg {
  padding: 13px 28px;
  font-size: 15px;
}
.btn-ghost {
  background: transparent;
  color: #4a5675;
  border: none;
  padding: 9px 12px;
  font-size: 14px;
}
.btn-ghost:hover {
  color: #2f6bff;
}

/* ---------- Hero ---------- */
.hero {
  background: linear-gradient(160deg, #eaf0ff 0%, #f6f8ff 55%, #ffffff 100%);
  padding: 88px 24px 72px;
  text-align: center;
}
.hero-inner {
  max-width: 820px;
  margin: 0 auto;
}
.badge {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(47, 107, 255, 0.1);
  color: #2f6bff;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 22px;
}
.hero-title {
  font-size: 46px;
  line-height: 1.2;
  font-weight: 800;
  color: #0f1830;
  margin: 0 0 20px;
  letter-spacing: 0.5px;
}
.hero-sub {
  font-size: 17px;
  line-height: 1.7;
  color: #54607d;
  margin: 0 auto 32px;
  max-width: 680px;
}
.hero-cta {
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
}
.hero-trust {
  margin-top: 28px;
  font-size: 13px;
  color: #8a93ad;
}

/* ---------- 响应式 ---------- */
@media (max-width: 980px) {
  .hero-title {
    font-size: 36px;
  }
}
@media (max-width: 640px) {
  .nav-links {
    display: none;
  }
  .hero-title {
    font-size: 30px;
  }
}
</style>
