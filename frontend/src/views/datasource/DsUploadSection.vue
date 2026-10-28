<script setup lang="ts">
/**
 * 数据源对话框「本地文件类」区块（T7.3 自 DataSourceView 逐字搬移）：
 * 上传 + 文件路径 + 解析预览 + 工作表勾选/合并 + 已存范围回显。
 * fragment 多根：两个 el-form-item 需保持 el-form 直接子节点布局，
 * 且 uploadResult/savedSheetSummary 的 v-if/v-else-if 链必须相邻，不能包壳。
 */
import { useDsCtx } from './useDataSource'
import { formatSize } from './dsUtils'

const ctx = useDsCtx()
</script>

<template>
  <el-form-item label="文件上传">
    <el-upload
      class="ds-upload"
      drag
      :http-request="ctx.customUpload"
      :before-upload="ctx.beforeUpload"
      :show-file-list="false"
      :disabled="ctx.uploading"
      accept=".csv,.xlsx,.xls,.parquet"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将 CSV / Excel / Parquet 拖到此处，或<em>点击上传</em></div>
      <template #tip>
        <div class="el-upload__tip">
          支持 .csv / .xlsx / .xls / .parquet，单文件 ≤ 20MB；上传后自动解析并预览，确认无误再保存。
        </div>
      </template>
    </el-upload>
  </el-form-item>

  <el-form-item label="文件路径" prop="filePath">
    <el-input
      v-model="ctx.form.filePath"
      :placeholder="ctx.uploading ? '正在解析文件…' : '上传后自动回填，也可手填服务器上的绝对路径'"
      :disabled="ctx.uploading"
      clearable
    />
  </el-form-item>

  <!-- 上传解析预览 -->
  <div v-if="ctx.uploadResult" class="upload-preview">
    <div class="up-head">
      <el-icon class="up-ico"><Document /></el-icon>
      <span class="up-name truncate">{{ ctx.uploadResult.originalName }}</span>
      <el-tag size="small" effect="plain" round>{{ formatSize(ctx.uploadResult.size) }}</el-tag>
      <el-tag size="small" effect="plain" type="success" round>
        {{
          ctx.importableSheets.length > 1
            ? `${ctx.importableSheets.length} 张可导入表`
            : `${ctx.currentSheet?.columns.length ?? 0} 列`
        }}
      </el-tag>
      <el-tooltip
        v-if="ctx.skippedSheets.length"
        placement="top"
        :content="`首行没有表头，已跳过：${ctx.skippedSheets.map((s) => s.name).join('、')}`"
      >
        <el-tag size="small" effect="plain" type="warning" round>
          {{ ctx.skippedSheets.length }} 张已跳过
        </el-tag>
      </el-tooltip>
    </div>

    <!-- 多张工作表：勾选导入范围 + 同构合并（单表/CSV 无此选择，不渲染） -->
    <div v-if="ctx.importableSheets.length > 1" class="up-sheets">
      <div class="up-sheets-head">
        <el-checkbox
          :model-value="ctx.allChecked"
          :indeterminate="ctx.someChecked && !ctx.allChecked"
          @change="ctx.onToggleAll($event)"
        >
          全选
        </el-checkbox>
        <span class="up-count">已选 {{ ctx.form.sheetNames.length }} / {{ ctx.importableSheets.length }}</span>
      </div>
      <div class="up-sheet-list">
        <div
          v-for="s in ctx.importableSheets"
          :key="s.name"
          class="up-sheet"
          :class="{ active: ctx.currentSheet?.name === s.name }"
        >
          <el-checkbox
            :model-value="ctx.form.sheetNames.includes(s.name)"
            @change="ctx.toggleSheet(s.name, $event)"
          >
            <span class="up-sheet-name truncate">{{ s.name }}</span>
            <span class="up-sheet-meta">{{ s.columns.length }} 列</span>
          </el-checkbox>
          <el-button size="small" text type="primary" @click="ctx.activeSheet = s.name">预览</el-button>
        </div>
        <div v-for="s in ctx.skippedSheets" :key="s.name" class="up-sheet skipped">
          <span class="up-sheet-name truncate">{{ s.name }}</span>
          <span class="up-sheet-meta">首行无表头，已跳过</span>
        </div>
      </div>
      <div class="up-merge">
        <el-switch v-model="ctx.form.sheetMerge" size="small" />
        <span class="up-merge-label">合并同构工作表</span>
        <span class="up-merge-tip">
          列名与顺序完全一致的工作表合成一张表（如 12 个月度 sheet），问“全年合计”不必跨表 UNION；结构不同的仍各自建表。
        </span>
      </div>
    </div>

    <div v-if="ctx.importableSheets.length > 1" class="up-cur">
      <span class="up-cur-name truncate">预览：{{ ctx.currentSheet?.name || '—' }}</span>
      <el-tag v-if="ctx.currentSheet?.truncated" size="small" effect="plain" type="warning" round>预览已截断</el-tag>
    </div>
    <el-table :data="ctx.previewTableData" size="small" border max-height="220" class="up-table">
      <el-table-column
        v-for="(col, i) in ctx.currentSheet?.columns ?? []"
        :key="`${col}-${i}`"
        :label="col"
        :prop="String(i)"
        min-width="120"
        show-overflow-tooltip
      />
    </el-table>
  </div>

  <!-- 未重新上传：回显已保存的导入范围（没有清单可勾，改选需重新上传文件） -->
  <div v-else-if="ctx.savedSheetSummary" class="up-saved">
    <el-icon class="up-ico"><Document /></el-icon>
    <span class="up-saved-text">{{ ctx.savedSheetSummary }}</span>
    <span class="up-saved-tip">如需调整勾选范围，请重新上传该文件。</span>
  </div>
</template>

<style scoped>
.ds-upload {
  width: 100%;
}
.ds-upload :deep(.el-upload-dragger) {
  padding: 18px 12px;
}
.upload-preview {
  margin: -4px 0 6px;
  padding: 12px 14px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--ikb-ghost);
}
.up-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.up-ico {
  color: var(--ikb);
}
.up-name {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-primary);
  max-width: 200px;
}
.up-table {
  width: 100%;
}
/* 多工作表勾选区（嵌在 .upload-preview 内，用卡片底色拉出层次） */
.up-sheets {
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
}
.up-sheets-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}
.up-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary);
}
.up-sheet-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  max-height: 172px;
  overflow-y: auto;
}
.up-sheet {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}
.up-sheet.active {
  background: var(--ikb-ghost);
}
.up-sheet.skipped {
  opacity: 0.55;
}
.up-sheet :deep(.el-checkbox) {
  flex: 1;
  min-width: 0;
  height: auto;
}
.up-sheet :deep(.el-checkbox__label) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12.5px;
}
.up-sheet-name {
  max-width: 190px;
  font-weight: 600;
  color: var(--text-primary);
}
.up-sheet-meta {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--text-secondary);
}
.up-merge {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-light);
  flex-wrap: wrap;
}
.up-merge-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}
.up-merge-tip,
.up-saved-tip {
  font-size: 11.5px;
  color: var(--text-secondary);
  line-height: 1.5;
}
.up-cur {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.up-cur-name {
  max-width: 260px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}
/* 编辑态 / 手填路径时的已存导入范围回显 */
.up-saved {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  margin: -4px 0 6px;
  padding: 10px 12px;
  border: 1px dashed var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
}
.up-saved-text {
  font-size: 12.5px;
  color: var(--text-primary);
}
</style>
