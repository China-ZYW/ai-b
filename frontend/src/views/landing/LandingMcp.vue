<script setup lang="ts">
/**
 * 落地页 MCP 开放生态（T7.3 自 LandingView 拆出）：
 * 连接器实测截图卡片 / 三步接入 / 能力点标签。纯静态渲染，数据来自 ./landingContent。
 */
import { mcpConnectors, mcpSteps, mcpCaps } from './landingContent'
</script>

<template>
  <!-- ============ MCP 开放生态 ============ -->
  <section id="mcp" class="section section-alt mcp-section">
    <div class="section-head">
      <h2>MCP 开放生态</h2>
      <p>豆包、WorkBuddy、Cursor 等 AI 客户端直连你的业务库，对话即问数</p>
    </div>

    <!-- 连接器实测截图 -->
    <div class="mcp-grid">
      <div v-for="c in mcpConnectors" :key="c.src" class="mcp-card">
        <div class="mcp-shot">
          <img :src="c.src" :alt="c.name + ' 实测截图'" loading="lazy" />
        </div>
        <div class="mcp-card-body">
          <div class="mcp-card-head">
            <h3>{{ c.name }}</h3>
            <span class="mcp-tag">{{ c.tag }}</span>
          </div>
          <p>{{ c.desc }}</p>
        </div>
      </div>
    </div>

    <!-- 三步接入 -->
    <div class="mcp-steps">
      <div v-for="s in mcpSteps" :key="s.no" class="mcp-step">
        <div class="mcp-step-no">{{ s.no }}</div>
        <h4>{{ s.title }}</h4>
        <p>{{ s.desc }}</p>
      </div>
    </div>

    <!-- 能力点 -->
    <div class="mcp-caps">
      <span v-for="cap in mcpCaps" :key="cap" class="mcp-cap">{{ cap }}</span>
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

/* ---------- MCP 开放生态 ---------- */
.mcp-section {
  background: linear-gradient(180deg, #f4f7ff 0%, #ffffff 100%);
  max-width: none;
  border-top: 1px solid rgba(47, 84, 235, 0.06);
}
.mcp-section > * {
  max-width: 1160px;
  margin-left: auto;
  margin-right: auto;
}
.mcp-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 26px;
}
.mcp-card {
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.08);
  border-radius: 18px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}
.mcp-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 24px 54px rgba(47, 84, 235, 0.16);
}
.mcp-shot {
  background: #f2f5fc;
  border-bottom: 1px solid rgba(15, 24, 48, 0.06);
}
.mcp-shot img {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 10;
  object-fit: cover;
  object-position: top center;
}
.mcp-card-body {
  padding: 20px 22px 22px;
}
.mcp-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.mcp-card-head h3 {
  font-size: 17px;
  font-weight: 800;
  color: #0f1830;
  margin: 0;
}
.mcp-tag {
  flex: none;
  font-size: 12px;
  font-weight: 700;
  color: #2f54eb;
  background: rgba(47, 84, 235, 0.09);
  border: 1px solid rgba(47, 84, 235, 0.18);
  padding: 3px 12px;
  border-radius: 999px;
  white-space: nowrap;
}
.mcp-card-body p {
  font-size: 13.5px;
  line-height: 1.7;
  color: #54607d;
  margin: 0;
}
.mcp-steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-top: 30px;
}
.mcp-step {
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.07);
  border-radius: 16px;
  padding: 24px 22px;
}
.mcp-step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2f6bff, #00164f);
  color: #fff;
  font-weight: 800;
  font-size: 15px;
  margin-bottom: 12px;
}
.mcp-step h4 {
  font-size: 15.5px;
  font-weight: 700;
  color: #0f1830;
  margin: 0 0 6px;
}
.mcp-step p {
  font-size: 13px;
  line-height: 1.65;
  color: #6b7493;
  margin: 0;
}
.mcp-caps {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
  margin-top: 30px;
}
.mcp-cap {
  font-size: 13px;
  font-weight: 600;
  color: #1c2b4a;
  background: #fff;
  border: 1px solid #dbe4f8;
  border-radius: 999px;
  padding: 8px 18px;
  box-shadow: 0 4px 12px rgba(47, 84, 235, 0.06);
}

/* ---------- 响应式 ---------- */
@media (max-width: 980px) {
  .mcp-grid {
    grid-template-columns: 1fr;
  }
  .mcp-steps {
    grid-template-columns: 1fr;
    gap: 16px;
  }
}
@media (max-width: 640px) {
  .mcp-grid,
  .mcp-steps {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 52px 18px;
  }
}
</style>
