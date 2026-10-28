<script setup lang="ts">
/**
 * 落地页联系方式 + 页脚（T7.3 自 LandingView 拆出）。
 * 「预约演示」与原实现一致：未登录跳登录页，已登录直达产品。
 */
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const year = new Date().getFullYear()

function primaryAction() {
  if (userStore.isLoggedIn) router.push('/app/chat')
  else router.push('/login')
}
</script>

<template>
  <!-- ============ 交流群与联系方式 ============ -->
  <section id="contact" class="section">
    <div class="section-head">
      <h2>交流群与联系方式</h2>
      <p>加入用户社区，或直接与我们取得联系</p>
    </div>
    <div class="contact-grid">
      <div class="contact-card">
        <h3>社区交流</h3>
        <p class="contact-tip">欢迎通过项目 Issue / 讨论区参与交流，一起把产品打磨得更好。</p>
      </div>
      <div class="contact-card">
        <h3>反馈与贡献</h3>
        <ul class="contact-list">
          <li><span class="c-label">邮箱</span><span class="c-value">请配置 VITE_CONTACT_EMAIL</span></li>
          <li><span class="c-label">贡献</span><span class="c-value">欢迎提交 Pull Request</span></li>
          <li><span class="c-label">许可</span><span class="c-value">Apache-2.0</span></li>
        </ul>
        <button class="btn-outline" @click="primaryAction">预约演示</button>
      </div>
    </div>
  </section>

  <!-- ============ 页脚 ============ -->
  <footer class="footer">
    <span>aibi · AI-Native BI</span>
    <span class="dot">·</span>
    <span>Apache-2.0 License</span>
    <span class="copyright">© {{ year }} aibi</span>
  </footer>
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

/* ---------- 按钮（预约演示用 outline，自持） ---------- */
.btn-outline {
  font-family: inherit;
  cursor: pointer;
  border-radius: 10px;
  font-weight: 600;
  transition: all 0.2s ease;
  background: #fff;
  color: #2f6bff;
  border: 1.5px solid #2f6bff;
  padding: 9px 18px;
  font-size: 14px;
}
.btn-outline:hover {
  background: #eef3ff;
}

/* ---------- 联系 ---------- */
.contact-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 24px;
  max-width: 860px;
  margin: 0 auto;
}
.contact-card {
  background: #fff;
  border: 1px solid rgba(15, 24, 48, 0.08);
  border-radius: 18px;
  padding: 30px;
  text-align: center;
}
.contact-card h3 {
  font-size: 18px;
  font-weight: 700;
  color: #0f1830;
  margin: 0 0 20px;
}
.qr-box {
  width: 170px;
  height: 170px;
  margin: 0 auto 16px;
  border: 1px solid #e3e9f5;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  overflow: hidden;
}
.qr-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.contact-tip {
  font-size: 12px;
  color: #8a93ad;
  margin: 0 0 4px;
}
.contact-list {
  list-style: none;
  padding: 0;
  margin: 0 0 20px;
  text-align: left;
}
.contact-list li {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 0;
  border-bottom: 1px dashed rgba(15, 24, 48, 0.06);
  font-size: 13px;
}
.c-label {
  color: #8a93ad;
}
.c-value {
  color: #0f1830;
  font-weight: 600;
  text-align: right;
}

/* ---------- 页脚 ---------- */
.footer {
  max-width: 1160px;
  margin: 0 auto;
  padding: 28px 24px 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #8a93ad;
  border-top: 1px solid rgba(15, 24, 48, 0.06);
}
.footer a {
  color: #8a93ad;
  text-decoration: none;
}
.footer a:hover {
  color: #2f6bff;
}
.footer .dot {
  opacity: 0.5;
}
.footer .copyright {
  width: 100%;
  text-align: center;
  margin-top: 6px;
  opacity: 0.7;
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .contact-grid {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 52px 18px;
  }
}
</style>
