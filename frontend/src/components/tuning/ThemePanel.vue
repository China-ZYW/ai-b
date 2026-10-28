<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { themeApi } from '@/api/theme'
import type { ThemeConfig } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 主题管理面板（配置化升级 V2，样式配置化）：维护大屏/问数图表的主题令牌（theme_config）。
 * 内置预设（builtin=1，由 DataSeeder 播种）只读；本租户可新建自定义主题（tokens 为 DashboardTheme 同构 JSON）。
 * 前端 resolveTheme 以「API 优先 + 内置兜底」解析，保存后大屏/公开页即时可选用。
 */
const loading = ref(false)
const rows = ref<ThemeConfig[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await themeApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

function isBuiltin(t: ThemeConfig): boolean {
  return t.builtin === 1 || t.tenantId == null
}

/** 安全解析 tokens JSON（失败返回 null，供预览容错）。 */
function parseTokens(t: ThemeConfig): Record<string, unknown> | null {
  try {
    return JSON.parse(t.tokens) as Record<string, unknown>
  } catch {
    return null
  }
}

function str(v: unknown, fallback: string): string {
  return typeof v === 'string' && v ? v : fallback
}
function palette(v: unknown): string[] {
  return Array.isArray(v) ? (v as string[]).slice(0, 6) : []
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const editingId = ref<number | null>(null)
const isEdit = ref(false)

interface FormState {
  code: string
  name: string
  dark: number
  tokens: string
}
const emptyForm = (): FormState => ({ code: '', name: '', dark: 1, tokens: '' })
const form = reactive<FormState>(emptyForm())

const previewTokens = computed<Record<string, unknown> | null>(() => {
  try {
    return JSON.parse(form.tokens) as Record<string, unknown>
  } catch {
    return null
  }
})
const tokensValid = computed(() => previewTokens.value !== null)

function openCreate(): void {
  editingId.value = null
  isEdit.value = false
  Object.assign(form, emptyForm())
  // 以内置深色主题为起点，降低手写 JSON 成本
  const seed = rows.value.find((t) => isBuiltin(t) && t.code === 'dark') || rows.value[0]
  if (seed) {
    form.tokens = prettyTokens(seed.tokens)
    form.dark = seed.dark ?? 1
  }
  drawerVisible.value = true
}

function openEdit(t: ThemeConfig): void {
  editingId.value = t.id ?? null
  isEdit.value = true
  Object.assign(form, {
    code: t.code,
    name: t.name ?? '',
    dark: t.dark ?? 0,
    tokens: prettyTokens(t.tokens)
  })
  drawerVisible.value = true
}

function prettyTokens(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 从已有主题复制 tokens 作为编辑起点。 */
function copyFrom(code: string): void {
  const src = rows.value.find((t) => t.code === code)
  if (src) {
    form.tokens = prettyTokens(src.tokens)
    form.dark = src.dark ?? form.dark
    ElMessage.success(`已填入「${src.name || src.code}」的令牌，可继续修改`)
  }
}

async function onSubmit(): Promise<void> {
  if (!form.code.trim()) {
    ElMessage.warning('请填写主题 code（英文标识）')
    return
  }
  if (!tokensValid.value) {
    ElMessage.warning('tokens 必须是合法 JSON')
    return
  }
  await runSubmit(async () => {
    try {

      const payload: ThemeConfig = {
        code: form.code.trim(),
        name: form.name.trim() || form.code.trim(),
        dark: form.dark,
        tokens: JSON.stringify(JSON.parse(form.tokens)) // 压缩存储
      }
      if (isEdit.value && editingId.value != null) {
        await themeApi.update({ ...payload, id: editingId.value })
        ElMessage.success('主题已更新')
      } else {
        await themeApi.create(payload)
        ElMessage.success('主题已创建')
      }
      drawerVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function onRemove(t: ThemeConfig): Promise<void> {
  if (t.id == null) return
  try {
    await ElMessageBox.confirm(`确定删除主题「${t.name || t.code}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await themeApi.remove(t.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="theme-panel">
    <div class="panel-toolbar">
      <p class="tb-tip">
        主题令牌驱动大屏与问数图表的配色。内置预设只读；可新建自定义主题，保存后在大屏主题选择器中即时可选。
      </p>
      <div class="tb-actions">
        <el-button size="default" :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button type="primary" size="default" @click="openCreate"><el-icon><Plus /></el-icon> 新建主题</el-button>
      </div>
    </div>

    <div v-loading="loading" class="theme-grid">
      <div v-for="t in rows" :key="t.id ?? t.code" class="theme-card card">
        <!-- 预览 -->
        <div
          class="tc-preview"
          :style="{ background: str(parseTokens(t)?.screenBg, '#0b1a3a') }"
        >
          <div
            class="tc-mini-card"
            :style="{
              background: str(parseTokens(t)?.cardBg, 'rgba(255,255,255,0.08)'),
              borderColor: str(parseTokens(t)?.cardBorder, 'rgba(255,255,255,0.2)')
            }"
          >
            <span class="tc-title" :style="{ color: str(parseTokens(t)?.textPrimary, '#fff') }">
              销售总览
            </span>
            <span class="tc-sub" :style="{ color: str(parseTokens(t)?.textSecondary, 'rgba(255,255,255,0.7)') }">
              实时 · {{ t.dark === 1 ? '深色' : '浅色' }}
            </span>
            <div class="tc-bars">
              <i
                v-for="(c, i) in palette(parseTokens(t)?.chartPalette)"
                :key="i"
                :style="{ background: c, height: 8 + ((i * 7) % 22) + 'px' }"
              />
            </div>
          </div>
          <span class="tc-accent" :style="{ background: str(parseTokens(t)?.accent, '#00c2ff') }" />
        </div>
        <!-- 元信息 -->
        <div class="tc-body">
          <div class="tc-head">
            <span class="tc-name">{{ t.name || t.code }}</span>
            <div class="tc-tags">
              <el-tag :type="t.dark === 1 ? 'info' : 'warning'" effect="plain" size="small" round>
                {{ t.dark === 1 ? '深色' : '浅色' }}
              </el-tag>
              <el-tag :type="isBuiltin(t) ? 'success' : 'primary'" effect="plain" size="small" round>
                {{ isBuiltin(t) ? '内置' : '自定义' }}
              </el-tag>
            </div>
          </div>
          <div class="tc-code mono">{{ t.code }}</div>
          <div class="tc-ops">
            <template v-if="!isBuiltin(t)">
              <el-button size="small" text @click="openEdit(t)"><el-icon><Edit /></el-icon> 编辑</el-button>
              <el-button size="small" text type="danger" @click="onRemove(t)"><el-icon><Delete /></el-icon> 删除</el-button>
            </template>
            <span v-else class="muted small">内置只读</span>
            <el-button size="small" text @click="openCreate(); copyFrom(t.code)">
              <el-icon><CopyDocument /></el-icon> 以此为模板
            </el-button>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && rows.length === 0" description="暂无主题" :image-size="80" />
    </div>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑主题' : '新建主题'" size="560px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="Code" required>
          <el-input v-model="form.code" :disabled="isEdit" placeholder="英文标识，例如：brand-blue" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="展示名，例如：品牌蓝" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="深色系">
          <el-switch v-model="form.dark" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="令牌">
          <el-input
            v-model="form.tokens"
            type="textarea"
            :rows="16"
            class="mono"
            spellcheck="false"
            placeholder="DashboardTheme 同构 JSON"
          />
          <span class="field-hint" :class="tokensValid ? 'muted' : 'danger'">
            {{ tokensValid ? '合法 JSON' : 'JSON 格式有误，请检查' }}
          </span>
        </el-form-item>
        <!-- 实时预览 -->
        <el-form-item label="预览">
          <div
            class="tc-preview live"
            :style="{ background: str(previewTokens?.screenBg, '#0b1a3a') }"
          >
            <div
              class="tc-mini-card"
              :style="{
                background: str(previewTokens?.cardBg, 'rgba(255,255,255,0.08)'),
                borderColor: str(previewTokens?.cardBorder, 'rgba(255,255,255,0.2)')
              }"
            >
              <span class="tc-title" :style="{ color: str(previewTokens?.textPrimary, '#fff') }">销售总览</span>
              <span class="tc-sub" :style="{ color: str(previewTokens?.textSecondary, 'rgba(255,255,255,0.7)') }">
                实时预览
              </span>
              <div class="tc-bars">
                <i
                  v-for="(c, i) in palette(previewTokens?.chartPalette)"
                  :key="i"
                  :style="{ background: c, height: 8 + ((i * 7) % 22) + 'px' }"
                />
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!tokensValid" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.theme-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-tip {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  max-width: 640px;
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 60px;
}
.theme-card {
  overflow: hidden;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.tc-preview {
  position: relative;
  height: 132px;
  padding: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tc-preview.live {
  width: 100%;
  height: 120px;
  border-radius: var(--radius-md);
}
.tc-mini-card {
  width: 100%;
  max-width: 210px;
  border: 1px solid;
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  backdrop-filter: blur(2px);
}
.tc-title {
  font-size: 13px;
  font-weight: 700;
}
.tc-sub {
  font-size: 11px;
}
.tc-bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 30px;
  margin-top: 6px;
}
.tc-bars i {
  width: 12px;
  border-radius: 3px 3px 0 0;
  display: block;
}
.tc-accent {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.tc-body {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.tc-name {
  font-size: 14px;
  font-weight: 700;
}
.tc-tags {
  display: flex;
  gap: 6px;
}
.tc-code {
  font-size: 12px;
  color: var(--text-secondary);
}
.tc-ops {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
}
.field-hint.danger {
  color: var(--el-color-danger);
}
</style>
