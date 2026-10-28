<script setup lang="ts">
/**
 * 驾驶舱编辑器顶部配置条（T7.1 自 DashboardEditorView.vue 拆出，模板与样式逐字搬移）：
 * 名称/主题/背景/样式/Logo/列数/刷新/大屏与自由画布开关 + AI 布局、预览、保存动作。
 * 状态经 useEditorCtx 从编辑器编排 composable 注入，本组件不持有业务状态。
 */
import { useEditorCtx } from './useEditor'
import { BG_PRESETS, emptyStyle } from './editorTypes'
import { FONT_OPTIONS, SCALE_OPTIONS } from '@/theme/dashboardTheme'

const ctx = useEditorCtx()
</script>

<template>
  <div class="editor-top">
    <el-button text class="back-btn" @click="ctx.goBack">
      <el-icon><ArrowLeft /></el-icon> 返回
    </el-button>
    <el-input v-model="ctx.form.name" class="name-input" placeholder="驾驶舱名称" maxlength="64" />
    <div class="cfg-group">
      <span class="cfg-label">主题</span>
      <el-select v-model="ctx.form.theme" size="small" style="width: 136px">
        <el-option v-for="o in ctx.themeOpts" :key="o.key" :label="o.label" :value="o.key">
          <span class="theme-opt">
            <span class="theme-swatch">
              <i v-for="(c, ci) in o.swatch" :key="ci" :style="{ background: c }" />
            </span>
            {{ o.label }}
          </span>
        </el-option>
      </el-select>
    </div>
    <div class="cfg-group">
      <span class="cfg-label">背景</span>
      <el-popover placement="bottom" :width="320" trigger="click">
        <template #reference>
          <el-button size="small">背景</el-button>
        </template>
        <div class="bg-panel">
          <p class="bg-title">预设</p>
          <div class="bg-presets">
            <button
              v-for="p in BG_PRESETS"
              :key="p.label"
              class="bg-chip"
              :class="{ active: ctx.form.background === p.value }"
              :style="p.value ? { background: p.value } : {}"
              :title="p.label"
              @click="ctx.applyBgPreset(p.value)"
            >
              <span v-if="!p.value" class="bg-chip-follow">跟随主题</span>
            </button>
          </div>
          <p class="bg-title">自定义 CSS</p>
          <el-input v-model="ctx.bgInput" size="small" placeholder="如 #0b2a7a 或 url(...)" @keyup.enter="ctx.applyBgCustom" />
          <el-button size="small" class="bg-apply" @click="ctx.applyBgCustom">应用</el-button>
          <p class="bg-title">上传图片</p>
          <el-upload
            class="bg-upload"
            :show-file-list="false"
            :before-upload="ctx.beforeBgUpload"
            :http-request="ctx.uploadBg"
            accept="image/*"
          >
            <el-button size="small">选择图片</el-button>
          </el-upload>
          <p class="bg-hint muted">背景覆盖整屏；卡片/字体/配色见「样式」。</p>
        </div>
      </el-popover>
    </div>
    <div class="cfg-group">
      <span class="cfg-label">样式</span>
      <el-popover placement="bottom" :width="344" trigger="click">
        <template #reference>
          <el-button size="small">样式</el-button>
        </template>
        <div class="sp">
          <p class="sp-title">字体</p>
          <el-select v-model="ctx.form.styleConfig.fontFamily" size="small" placeholder="跟随系统" clearable style="width: 100%">
            <el-option v-for="f in FONT_OPTIONS" :key="f.label" :label="f.label" :value="f.value" />
          </el-select>
          <p class="sp-title">字号比例</p>
          <el-select v-model="ctx.form.styleConfig.fontScale" size="small" placeholder="标准（1.0×）" clearable style="width: 100%">
            <el-option v-for="s in SCALE_OPTIONS" :key="s.value" :label="s.label" :value="String(s.value)" />
          </el-select>
          <p class="sp-title">卡片圆角</p>
          <el-select v-model="ctx.form.styleConfig.cardRadius" size="small" placeholder="跟随主题" clearable style="width: 100%">
            <el-option label="直角（0）" value="0" />
            <el-option label="小（6）" value="6" />
            <el-option label="中（10）" value="10" />
            <el-option label="大（16）" value="16" />
            <el-option label="超大（24）" value="24" />
          </el-select>
          <p class="sp-title">配色（空=跟随主题）</p>
          <div class="sp-row">
            <span class="sp-label">顶栏背景</span>
            <el-input v-model="ctx.form.styleConfig.headerBg" size="small" placeholder="色值/渐变 CSS" />
          </div>
          <div class="sp-row">
            <span class="sp-label">卡片背景</span>
            <el-input v-model="ctx.form.styleConfig.cardBg" size="small" placeholder="色值/渐变 CSS" />
          </div>
          <div class="sp-row">
            <span class="sp-label">卡片边框</span>
            <el-color-picker v-model="ctx.colorCardBorder" size="small" />
          </div>
          <div class="sp-row">
            <span class="sp-label">显示边框</span>
            <el-switch v-model="ctx.showCardBorder" />
          </div>
          <div class="sp-row">
            <span class="sp-label">强调色</span>
            <el-color-picker v-model="ctx.colorAccent" size="small" />
          </div>
          <div class="sp-row">
            <span class="sp-label">主文字</span>
            <el-color-picker v-model="ctx.colorTextPrimary" size="small" />
          </div>
          <div class="sp-row">
            <span class="sp-label">次文字</span>
            <el-color-picker v-model="ctx.colorTextSecondary" size="small" />
          </div>
          <div class="sp-row">
            <span class="sp-label">弱文字</span>
            <el-color-picker v-model="ctx.colorTextMuted" size="small" />
          </div>
          <el-button size="small" class="sp-reset" @click="ctx.form.styleConfig = emptyStyle()">恢复默认</el-button>
          <p class="sp-hint muted">空项=跟随主题；以上对整屏所有组件生效；单个格子可在其右上角齿轮里单独覆盖。</p>
        </div>
      </el-popover>
    </div>
    <div class="cfg-group">
      <span class="cfg-label">Logo</span>
      <el-popover placement="bottom" :width="300" trigger="click">
        <template #reference>
          <el-button size="small">Logo</el-button>
        </template>
        <div class="logo-panel">
          <p class="logo-title">上传或填写 Logo 图片地址（显示于大屏顶栏左侧）</p>
          <el-upload
            class="logo-upload"
            :show-file-list="false"
            :before-upload="ctx.beforeBgUpload"
            :http-request="ctx.uploadLogo"
            accept="image/*"
          >
            <el-button size="small">选择图片</el-button>
          </el-upload>
          <el-input v-model="ctx.logoUrl" size="small" placeholder="或填写图片 URL" />
          <div v-if="ctx.logoUrl" class="logo-preview">
            <img :src="ctx.logoUrl" alt="logo 预览" />
            <el-button size="small" text type="danger" @click="ctx.logoUrl = ''">移除</el-button>
          </div>
          <p class="logo-hint muted">留空则大屏顶栏只显示标题。</p>
        </div>
      </el-popover>
    </div>
    <div class="cfg-group">
      <span class="cfg-label">列数</span>
      <el-input-number v-model="ctx.form.cols" size="small" :min="6" :max="24" :step="2" controls-position="right" style="width: 92px" />
    </div>
    <div class="cfg-group">
      <span class="cfg-label">刷新(秒)</span>
      <el-input-number v-model="ctx.form.refreshSeconds" size="small" :min="5" :max="600" :step="5" controls-position="right" style="width: 96px" />
    </div>
    <el-checkbox v-model="ctx.form.isScreen" :true-value="1" :false-value="0">数据大屏</el-checkbox>
    <el-checkbox v-model="ctx.freeCanvas">自由画布</el-checkbox>
    <div class="top-spacer" />
    <el-button size="small" @click="ctx.autoLayout"><el-icon><MagicStick /></el-icon> AI 自适应布局</el-button>
    <el-button size="small" :loading="ctx.aiDesigning" @click="ctx.aiSmartDesign">
      <el-icon><Star /></el-icon> AI 智能设计
    </el-button>
    <el-button size="small" @click="ctx.openScreen"><el-icon><FullScreen /></el-icon> 大屏预览</el-button>
    <el-button size="small" type="primary" :loading="ctx.saving" @click="ctx.save"><el-icon><Check /></el-icon> 保存</el-button>
  </div>
</template>

<style scoped>
.editor-top {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-light);
  background: var(--grad-topbar);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.back-btn {
  font-weight: 600;
}
.name-input {
  width: 220px;
}
.name-input :deep(.el-input__inner) {
  font-weight: 700;
}
.cfg-group {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cfg-label {
  font-size: 12.5px;
  color: var(--text-secondary);
  font-weight: 600;
}
.top-spacer {
  flex: 1;
}
.theme-opt {
  display: flex;
  align-items: center;
  gap: 8px;
}
.theme-swatch {
  display: inline-flex;
  gap: 2px;
}
.theme-swatch i {
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
/* ---------- 大屏背景面板（范围 A+B） ---------- */
.bg-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bg-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin: 4px 0 0;
}
.bg-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.bg-chip {
  width: 64px;
  height: 40px;
  border-radius: 6px;
  border: 1px solid var(--border-light);
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  overflow: hidden;
}
.bg-chip.active {
  outline: 2px solid var(--ikb-light);
  outline-offset: 1px;
}
.bg-chip-follow {
  font-size: 10px;
  color: var(--text-placeholder);
  background: var(--bg-card);
  border-radius: 3px;
  padding: 1px 3px;
}
.bg-apply {
  align-self: flex-start;
}
.bg-hint {
  font-size: 11px;
  margin: 0;
}
/* ---------- 顶栏 Logo 面板 ---------- */
.logo-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.logo-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin: 0;
}
.logo-upload {
  align-self: flex-start;
}
.logo-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
}
.logo-preview img {
  max-width: 120px;
  max-height: 64px;
  object-fit: contain;
}
.logo-hint {
  font-size: 11px;
  margin: 0;
}
/* ---------- 大屏样式面板（卡片/字体/配色）---------- */
.sp {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sp-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin: 4px 0 0;
}
.sp-row {
  display: grid;
  grid-template-columns: 64px 1fr;
  align-items: center;
  gap: 8px;
}
.sp-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.sp-reset {
  align-self: flex-start;
  margin-top: 4px;
}
.sp-hint {
  font-size: 11px;
  margin: 0;
}
</style>
