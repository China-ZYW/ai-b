<template>
  <div class="loading">
    <!-- 边框灯光跑马灯：双光弧沿气泡边框追逐 -->
    <div class="chase-border">
      <div class="chase-inner">
        <span class="dot"></span><span class="dot"></span><span class="dot"></span>
        <span class="loading-text">正在查询…</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 边框灯光跑马灯加载动画 ===== */
.loading {
  width: 100%;
}
.chase-border {
  position: relative;
  border-radius: 14px;
  padding: 1.5px;
  overflow: hidden;
  background: #fff;
  isolation: isolate;
}
/* 两道错相光弧绕边框旋转（伪元素是大号旋转方盘，中心被内层白底遮住，只剩边框环透光） */
.chase-border::before,
.chase-border::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 220%;
  aspect-ratio: 1 / 1;
  translate: -50% -50%;
  z-index: 0;
  animation: chase-spin 2.4s linear infinite;
}
.chase-border::before {
  background: conic-gradient(
    transparent 0deg,
    transparent 292deg,
    rgba(37, 99, 235, 0.18) 314deg,
    #2563eb 338deg,
    #7fb2ff 352deg,
    transparent 360deg
  );
}
.chase-border::after {
  background: conic-gradient(
    transparent 0deg,
    transparent 296deg,
    rgba(14, 165, 233, 0.14) 320deg,
    #38bdf8 344deg,
    #93c5fd 356deg,
    transparent 360deg
  );
  animation-delay: -1.2s;
}
.chase-inner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 5px;
  border-radius: 12.5px;
  background: #fff;
  padding: 10px 14px;
}
@keyframes chase-spin {
  to {
    transform: rotate(360deg);
  }
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2563eb;
  animation: blink 1.2s infinite;
}
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; }
  40% { opacity: 1; }
}
.loading-text {
  font-size: 12px;
  color: #64748b;
  margin-left: 6px;
}
@media (prefers-reduced-motion: reduce) {
  .chase-border::before,
  .chase-border::after,
  .dot {
    animation: none;
  }
}
</style>
