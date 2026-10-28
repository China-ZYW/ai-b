<script setup lang="ts">
/**
 * 产品落地页（对外公开，免登录）。
 *
 * <p><b>T7.3 拆分（2026-10）</b>：原 1377 行按 section 自持拆至 ./landing/——
 * 文案常量在 landingContent.ts，7 个 section 组件各自携带模板与样式
 * （NavHero/Features/Architecture/Shots 轮播状态机自持/Mcp/TryStats/ContactFooter）。
 * 本视图只保留 .landing 根容器与页面级样式。行为与拆分前一致，逻辑逐字搬移。
 */
import LandingNavHero from './landing/LandingNavHero.vue'
import LandingFeatures from './landing/LandingFeatures.vue'
import LandingArchitecture from './landing/LandingArchitecture.vue'
import LandingShots from './landing/LandingShots.vue'
import LandingMcp from './landing/LandingMcp.vue'
import LandingTryStats from './landing/LandingTryStats.vue'
import LandingContactFooter from './landing/LandingContactFooter.vue'
</script>

<template>
  <div class="landing">
    <LandingNavHero />
    <LandingFeatures />
    <LandingArchitecture />
    <LandingShots />
    <LandingMcp />
    <LandingTryStats />
    <LandingContactFooter />
  </div>
</template>

<style scoped>
.landing {
  min-height: 100vh;
  /* 让出全局固定备案条（34px） */
  padding-bottom: 48px;
  background: #f6f8ff;
  color: #0f1830;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
</style>
