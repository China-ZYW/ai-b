<script setup lang="ts">
/**
 * 落地页项目架构（T7.3 自 LandingView 拆出）：分层架构 + 技术栈 + 安全说明。
 * 纯静态渲染，数据来自 ./landingContent（来源 docs/02-architecture）。
 */
import { archLayers, archStack } from './landingContent'
</script>

<template>
  <!-- ============ 项目架构 ============ -->
  <section id="arch" class="section section-alt arch-section">
    <div class="section-head">
      <h2>项目架构</h2>
      <p>模块化单体 + 轻量中间件，开箱即用、可私有化、可平滑演进</p>
    </div>

    <!-- 分层架构 -->
    <div class="arch-layers">
      <div v-for="(layer, li) in archLayers" :key="layer.title" class="arch-layer">
        <div class="arch-layer-head">
          <span class="arch-layer-no">L{{ li + 1 }}</span>
          <span class="arch-layer-title">{{ layer.title }}</span>
        </div>
        <div class="arch-layer-body">
          <span v-for="it in layer.items" :key="it" class="arch-chip">{{ it }}</span>
        </div>
      </div>
    </div>

    <!-- 技术栈 -->
    <div class="arch-stack">
      <div v-for="col in archStack" :key="col.group" class="arch-stack-col">
        <h4 class="arch-stack-title">{{ col.group }}</h4>
        <ul class="arch-stack-list">
          <li v-for="it in col.items" :key="it">{{ it }}</li>
        </ul>
      </div>
    </div>

    <p class="arch-note">
      数据不出用户环境：仅以<strong>只读账号</strong>访问业务库，SQL 白名单校验、结果不落地；单台 4C4G 即可稳定运行，支持私有化部署。
    </p>
  </section>
</template>

<style scoped>
/* ---------- 通用 section（各 section 组件自持，值逐字相同） ---------- */
.section {
  max-width: 1160px;
  margin: 0 auto;
  padding: 72px 24px;
}
.section-alt {
  background: #fff;
  max-width: none;
}
.section-alt > * {
  max-width: 1160px;
  margin-left: auto;
  margin-right: auto;
}
.section-head {
  text-align: center;
  margin-bottom: 44px;
}
.section-head h2 {
  font-size: 30px;
  font-weight: 800;
  color: #0f1830;
  margin: 0 0 10px;
}
.section-head p {
  font-size: 15px;
  color: #6b7493;
  margin: 0;
}

/* ---------- 项目架构 ---------- */
.arch-section {
  background: #fff;
}
.arch-layers {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 40px;
}
.arch-layer {
  background: linear-gradient(180deg, #f7faff, #ffffff);
  border: 1px solid rgba(15, 24, 48, 0.08);
  border-radius: 16px;
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
}
.arch-layer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.arch-layer-no {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2f6bff, #00164f);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.arch-layer-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f1830;
}
.arch-layer-body {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.arch-chip {
  font-size: 12px;
  color: #33406a;
  background: #eef3ff;
  border: 1px solid #dbe6ff;
  border-radius: 999px;
  padding: 5px 11px;
  line-height: 1.4;
}
.arch-stack {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}
.arch-stack-col {
  background: #f8faff;
  border: 1px solid rgba(15, 24, 48, 0.06);
  border-radius: 14px;
  padding: 20px 18px;
}
.arch-stack-title {
  font-size: 14px;
  font-weight: 700;
  color: #2f6bff;
  margin: 0 0 14px;
}
.arch-stack-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.arch-stack-list li {
  font-size: 13px;
  color: #54607d;
  padding: 7px 0 7px 18px;
  position: relative;
  border-bottom: 1px dashed rgba(15, 24, 48, 0.05);
}
.arch-stack-list li:last-child {
  border-bottom: none;
}
.arch-stack-list li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 15px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2f6bff;
}
.arch-note {
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7493;
  max-width: 880px;
  margin: 0 auto;
  padding: 16px 20px;
  background: #f5f8ff;
  border: 1px solid #e3eaf8;
  border-radius: 12px;
}
.arch-note strong {
  color: #2f6bff;
}

/* ---------- 响应式（架构区） ---------- */
@media (max-width: 980px) {
  .arch-layers,
  .arch-stack {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 640px) {
  .arch-layers,
  .arch-stack {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 52px 18px;
  }
}
</style>
