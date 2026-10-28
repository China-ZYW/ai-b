<script setup lang="ts">
/**
 * 落地页产品介绍（T7.3 自 LandingView 拆出）：产品功能 / 三步上手 / 典型场景 三个 section。
 * 纯静态渲染，数据来自 ./landingContent。
 */
import { features, steps, scenarios } from './landingContent'
</script>

<template>
  <!-- ============ 产品功能 ============ -->
  <section id="features" class="section">
    <div class="section-head">
      <h2>产品功能</h2>
      <p>从提问到洞察，覆盖企业数据分析的全链路</p>
    </div>
    <div class="feature-grid">
      <div v-for="f in features" :key="f.title" class="feature-card">
        <div class="feature-icon">{{ f.icon }}</div>
        <h3>{{ f.title }}</h3>
        <p>{{ f.desc }}</p>
      </div>
    </div>
  </section>

  <!-- ============ 三步上手 ============ -->
  <section class="section">
    <div class="section-head">
      <h2>三步上手</h2>
      <p>从接入到洞察，十几分钟跑通数据分析闭环</p>
    </div>
    <div class="steps">
      <div v-for="(s, i) in steps" :key="s.no" class="step">
        <div class="step-no">{{ s.no }}</div>
        <h3>{{ s.title }}</h3>
        <p>{{ s.desc }}</p>
        <div v-if="i < steps.length - 1" class="step-arrow">→</div>
      </div>
    </div>
  </section>

  <!-- ============ 典型场景 ============ -->
  <section class="section section-alt">
    <div class="section-head">
      <h2>典型场景</h2>
      <p>一个平台，覆盖各团队的数据分析诉求</p>
    </div>
    <div class="scenario-grid">
      <div v-for="c in scenarios" :key="c.title" class="scenario-card">
        <div class="scenario-icon">{{ c.icon }}</div>
        <h3>{{ c.title }}</h3>
        <p>{{ c.desc }}</p>
      </div>
    </div>
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

/* ---------- 功能卡片 ---------- */
.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}
.feature-card {
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.06);
  border-radius: 16px;
  padding: 26px 22px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.feature-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 16px 36px rgba(15, 24, 48, 0.1);
}
.feature-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: linear-gradient(135deg, #eaf0ff, #dbe6ff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  margin-bottom: 16px;
}
.feature-card h3 {
  font-size: 16px;
  font-weight: 700;
  color: #0f1830;
  margin: 0 0 8px;
}
.feature-card p {
  font-size: 13px;
  line-height: 1.65;
  color: #6b7493;
  margin: 0;
}

/* ---------- 三步上手 ---------- */
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 22px;
  max-width: 980px;
  margin: 0 auto;
}
.step {
  position: relative;
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.07);
  border-radius: 16px;
  padding: 28px 24px;
}
.step-no {
  font-size: 30px;
  font-weight: 800;
  color: #2f6bff;
  opacity: 0.85;
  letter-spacing: 1px;
}
.step h3 {
  font-size: 17px;
  font-weight: 700;
  color: #0f1830;
  margin: 10px 0 8px;
}
.step p {
  font-size: 13.5px;
  line-height: 1.7;
  color: #6b7493;
  margin: 0;
}
.step-arrow {
  position: absolute;
  right: -16px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 22px;
  color: #c2cce6;
}

/* ---------- 典型场景 ---------- */
.scenario-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.scenario-card {
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.07);
  border-radius: 16px;
  padding: 26px 22px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.scenario-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 16px 36px rgba(15, 24, 48, 0.1);
}
.scenario-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: linear-gradient(135deg, #eaf0ff, #dbe6ff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  margin-bottom: 14px;
}
.scenario-card h3 {
  font-size: 16px;
  font-weight: 700;
  color: #0f1830;
  margin: 0 0 8px;
}
.scenario-card p {
  font-size: 13px;
  line-height: 1.65;
  color: #6b7493;
  margin: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 980px) {
  .feature-grid,
  .scenario-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .steps {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .step-arrow {
    display: none;
  }
}
@media (max-width: 640px) {
  .feature-grid,
  .scenario-grid {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 52px 18px;
  }
}
</style>
