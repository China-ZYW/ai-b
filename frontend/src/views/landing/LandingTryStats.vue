<script setup lang="ts">
/**
 * 落地页在线体验 + 信任数据条（T7.3 自 LandingView 拆出）。
 * iframe 为对外免登录演示嵌入；嵌入地址通过 VITE_DEMO_EMBED_URL 配置，
 * 未配置时展示占位提示（避免写死任何第三方在线地址）。stats 为示意数据（文案见 ./landingContent）。
 */
import { stats } from './landingContent'

const demoEmbedUrl = (import.meta.env.VITE_DEMO_EMBED_URL as string | undefined) || ''
</script>

<template>
  <!-- ============ 在线体验 ============ -->
  <section id="try" class="section try-section">
    <div class="section-head">
      <h2>在线体验</h2>
      <p>无感嵌入：免登录直接对话，自动记住你的问数历史</p>
    </div>
    <div class="try-frame">
      <iframe
        v-if="demoEmbedUrl"
        :src="demoEmbedUrl"
        width="100%"
        height="100%"
        style="border:0;display:block;min-height:100vh"
        allow="clipboard-write"
      ></iframe>
      <div v-else class="try-placeholder" style="display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center;padding:2rem">
        <p>在线体验演示未配置。请设置环境变量 <code>VITE_DEMO_EMBED_URL</code> 指向你的免登录嵌入地址。</p>
      </div>
    </div>
    <p class="try-note">
      一行 <code>&lt;iframe&gt;</code> 即可将对话问数无感嵌入你的业务系统——访客免登录、按外部用户标识隔离会话、自动沉淀对话历史。
    </p>
  </section>

  <!-- ============ 信任数据条 ============ -->
  <section class="stats-band">
    <div class="stats-inner">
      <div v-for="st in stats" :key="st.label" class="stat">
        <div class="stat-num">{{ st.num }}</div>
        <div class="stat-label">{{ st.label }}</div>
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

/* ---------- 在线体验 ---------- */
.try-section {
  background: #fff;
  max-width: none;
}
.try-section > * {
  max-width: 1160px;
  margin-left: auto;
  margin-right: auto;
}
.try-frame {
  margin: 0 auto;
  max-width: 1160px;
  border: 1px solid rgba(15, 24, 48, 0.08);
  border-radius: 18px;
  overflow: hidden;
  box-shadow: 0 18px 44px rgba(15, 24, 48, 0.12);
}
.try-frame iframe {
  width: 100%;
  display: block;
}
.try-note {
  margin: 16px auto 0;
  max-width: 1160px;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7280;
}
.try-note code {
  background: #eef2f8;
  border: 1px solid #dbe4f0;
  border-radius: 5px;
  padding: 1px 6px;
  font-size: 12px;
  color: #1d4ed8;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

/* ---------- 信任数据条 ---------- */
.stats-band {
  background: linear-gradient(135deg, #2f6bff, #00164f);
  padding: 48px 24px;
}
.stats-inner {
  max-width: 1000px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}
.stat {
  text-align: center;
  color: #fff;
}
.stat-num {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: 0.5px;
}
.stat-label {
  font-size: 13px;
  opacity: 0.85;
  margin-top: 6px;
}

/* 原视图遗留按钮变体：当前模板未使用（死样式），随拆分原样保留，不删不动 */
.btn-outline.light {
  background: transparent;
  color: #2f6bff;
  border-color: #2f6bff;
}
.btn-outline.light:hover {
  background: rgba(47, 107, 255, 0.08);
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .stats-inner {
    grid-template-columns: repeat(2, 1fr);
    gap: 24px;
  }
  .section {
    padding: 52px 18px;
  }
}
</style>
