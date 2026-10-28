<script setup lang="ts">
/**
 * 落地页产品截图轮播（T7.3 自 LandingView 拆出）：3D 环绕 coverflow。
 * 轮播索引/自动播放定时器/窄屏监听全部由本组件自持（状态不外泄），
 * 数据来自 ./landingContent。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { shots } from './landingContent'

const shotIndex = ref(0)
let shotTimer: number | undefined
function shotGo(i: number) {
  shotIndex.value = (i + shots.length) % shots.length
  restartShotTimer()
}
function restartShotTimer() {
  if (shotTimer) window.clearInterval(shotTimer)
  shotTimer = window.setInterval(() => {
    shotIndex.value = (shotIndex.value + 1) % shots.length
  }, 4000)
}
function pauseShotTimer() {
  if (shotTimer) window.clearInterval(shotTimer)
  shotTimer = undefined
}
onMounted(() => {
  restartShotTimer()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  if (shotTimer) window.clearInterval(shotTimer)
  window.removeEventListener('resize', onResize)
})

/* ---- 3D 环绕轮播（coverflow）：按当前索引计算每张图的空间位移 ---- */
const isNarrow = ref(false)
function onResize() {
  isNarrow.value = window.innerWidth < 760
}
/** 把线性距离折算成环绕距离（-n/2 ~ n/2），保证首尾过渡走短弧 */
function shotOffset(i: number): number {
  const n = shots.length
  let d = i - shotIndex.value
  if (d > n / 2) d -= n
  if (d < -n / 2) d += n
  return d
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function shotStyle(i: number): any {
  const d = shotOffset(i)
  const abs = Math.abs(d)
  if (abs > 2) {
    return { opacity: '0', transform: 'translateX(0%) translateZ(-900px) rotateY(0deg)', zIndex: '0', pointerEvents: 'none' }
  }
  // 窄屏收拢位移，避免侧图溢出屏幕（中图加宽到 88% 后，xStep 按同比例折算，侧卡屏幕位置不变）
  const xStep = isNarrow.value ? 38 : 29
  const zStep = isNarrow.value ? 160 : 260
  const x = d * xStep
  const z = -abs * zStep
  const ry = d === 0 ? 0 : d > 0 ? -38 : 38
  return {
    opacity: String(Math.max(1 - abs * 0.4, 0.12)),
    transform: `translateX(${x}%) translateZ(${z}px) rotateY(${ry}deg)`,
    zIndex: String(10 - abs),
    pointerEvents: 'auto'
  }
}
</script>

<template>
  <!-- ============ 产品截图轮播 ============ -->
  <section id="shots" class="section shots-section">
    <div class="section-head">
      <h2>产品截图</h2>
      <p>真实界面一览：从问数到驾驶舱，从调优到分享</p>
    </div>
    <div class="shot-stage" @mouseenter="pauseShotTimer()" @mouseleave="restartShotTimer()">
      <div class="shot-viewport">
        <img
          v-for="(s, i) in shots"
          :key="s.src"
          :src="s.src"
          :alt="s.title"
          class="shot-img"
          :class="{ active: i === shotIndex }"
          :style="shotStyle(i)"
          :title="s.title"
          loading="lazy"
          @click="shotGo(i)"
        />
      </div>
      <div class="shot-caption">
        <h3>{{ shots[shotIndex].title }}</h3>
        <p>{{ shots[shotIndex].desc }}</p>
      </div>
      <button class="shot-btn prev" aria-label="上一张" @click="shotGo(shotIndex - 1)">‹</button>
      <button class="shot-btn next" aria-label="下一张" @click="shotGo(shotIndex + 1)">›</button>
      <div class="shot-dots">
        <button
          v-for="(s, i) in shots"
          :key="'dot-' + s.src"
          class="shot-dot"
          :class="{ on: i === shotIndex }"
          :aria-label="'第' + (i + 1) + '张：' + s.title"
          @click="shotGo(i)"
        ></button>
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

/* ---------- 产品截图轮播（3D 环绕） ---------- */
.shots-section .section-head {
  margin-bottom: 28px;
}
/* 轮播加宽：突破常规 1160 版心，视觉更开阔 */
.shots-section {
  max-width: 1360px;
}
.shot-stage {
  position: relative;
  max-width: 1280px;
  margin: 0 auto;
  border-radius: 16px;
  background: linear-gradient(135deg, #eef3ff, #f8faff);
  border: 1px solid #e3eaf8;
  padding: 26px 18px 56px;
  box-shadow: 0 18px 50px rgba(47, 84, 235, 0.1);
  /* 侧图 translateX 会伸出面板外，横向裁掉，避免撑出页面级横向滚动条（纵向保持 visible，圆点指示器不被裁） */
  overflow-x: clip;
  overflow-y: visible;
}
.shot-viewport {
  position: relative;
  width: 100%;
  /* 素材实测≈2.16:1、中图占 88% 宽 → 2.16×0.88≈2.45，图片区域与素材比例一致，contain 不再留黑边 */
  aspect-ratio: 2.45 / 1;
  perspective: 1500px;
  perspective-origin: 50% 42%;
  transform-style: preserve-3d;
}
.shot-img {
  position: absolute;
  top: 0;
  left: 50%;
  width: 88%;
  height: 100%;
  margin-left: -44%;
  object-fit: contain;
  border-radius: 12px;
  cursor: pointer;
  background: #0b1530;
  transform-style: preserve-3d;
  transition: transform 0.65s cubic-bezier(0.22, 0.61, 0.36, 1), opacity 0.5s ease;
  will-change: transform, opacity;
  box-shadow: 0 14px 38px rgba(11, 21, 48, 0.28);
}
.shot-img.active {
  box-shadow: 0 26px 64px rgba(11, 21, 48, 0.4);
}
.shot-caption {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 10px;
  text-align: center;
}
.shot-caption h3 {
  font-size: 15px;
  color: #1c2b4a;
  margin-bottom: 2px;
}
.shot-caption p {
  font-size: 13px;
  color: #6a7492;
}
.shot-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.9);
  color: #2f54eb;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(28, 43, 74, 0.18);
  transition: background 0.2s, transform 0.2s;
}
.shot-btn:hover {
  background: #ffffff;
  transform: translateY(-50%) scale(1.06);
}
.shot-btn.prev { left: 10px; }
.shot-btn.next { right: 10px; }
.shot-dots {
  position: absolute;
  bottom: -26px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 8px;
}
.shot-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: none;
  background: #c8d3ec;
  cursor: pointer;
  padding: 0;
  transition: background 0.2s, width 0.2s;
}
.shot-dot.on {
  background: #2f54eb;
  width: 22px;
  border-radius: 5px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 640px) {
  .section {
    padding: 52px 18px;
  }
  .shot-stage {
    padding: 12px 10px 50px;
  }
  .shot-viewport {
    /* 窄屏同法折算：素材≈2.16:1 × 中图 76% ≈ 1.64，消除窄屏黑边 */
    aspect-ratio: 1.64 / 1;
  }
  .shot-img {
    width: 76%;
    margin-left: -38%;
    border-radius: 8px;
  }
  .shot-btn {
    width: 32px;
    height: 32px;
    font-size: 18px;
  }
}
</style>
