<template>
  <div class="page">
    <el-card shadow="never" class="head-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">MCP 令牌</span>
          <span class="dim">为某个组织的某个用户签发对外访问令牌，外部 AI 客户端（Claude / Cursor / 自建 Agent）凭它调用 <code>POST /mcp</code> 问数。</span>
        </div>
      </template>

      <div class="toolbar">
        <span class="field-label">组织</span>
        <el-select v-model="tenantId" filterable placeholder="选择组织" style="width: 240px" @change="onTenantChange">
          <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（#${t.id}）`" :value="t.id" />
        </el-select>

        <span class="field-label">用户</span>
        <el-select v-model="uid" filterable :disabled="!tenantId" placeholder="选择用户" style="width: 240px">
          <el-option
            v-for="u in users"
            :key="u.id"
            :label="u.nickname || u.username"
            :value="u.id"
          >
            <span>{{ u.nickname || u.username }}</span>
            <span class="dim option-sub">{{ u.username }}</span>
          </el-option>
        </el-select>

        <el-button :disabled="!tenantId" @click="load">刷新</el-button>
        <el-button type="primary" :disabled="!tenantId || !uid" :loading="creating" @click="openCreate">
          签发令牌
        </el-button>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="令牌绑定到「具体组织的真实用户」：调用时以他的角色执行，权限、脱敏、配额、审计全部照常生效——MCP 不是绕过治理的旁路。明文只在签发弹窗里出现一次，请立即保存。"
        class="tip"
      />
    </el-card>

    <!-- 外部 AI 客户端使用教程 -->
    <el-card shadow="never" class="tutorial-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">外部 AI 客户端使用教程</span>
          <span class="dim">把上方签发的令牌配置到 Claude / Cursor / VS Code 等支持 MCP 的客户端，让它们直接问 aibi 已接入的数据。</span>
        </div>
      </template>

      <el-collapse>
        <el-collapse-item title="一、支持哪些客户端" name="1">
          <p class="tut-p">只要客户端支持 <b>MCP Streamable HTTP</b>（传输类型 <code>type: "http"</code>）即可接入，例如：</p>
          <ul class="tut-ul">
            <li>Claude Desktop（设置 → 开发者 → 编辑配置）</li>
            <li>Cursor（<code>~/.cursor/mcp.json</code> 或设置中的 MCP）</li>
            <li>VS Code + GitHub Copilot（<code>.vscode/mcp.json</code> 或 <code>mcp.servers</code>）</li>
            <li>Cline、Cherry Studio、5ire 等任意兼容客户端</li>
          </ul>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="tip"
            title='仅支持旧版 SSE（type:"sse"）的客户端暂不可用：本服务采用无状态 Streamable HTTP，不提供 SSE 长连接。'
          />
        </el-collapse-item>

        <el-collapse-item title="二、配置步骤" name="2">
          <ol class="tut-ol">
            <li>在上方「组织 / 用户」中选择要绑定的身份，点击 <b>签发令牌</b>，复制明文令牌（只出现一次，请立即保存）。</li>
            <li>在客户端的 MCP 配置文件里加入下方配置，把 <code>aibi_pat_...</code> 替换成你的令牌。</li>
            <li>重启 / 刷新客户端，看到 <code>aibi</code> 服务器已连接后即可开始提问。</li>
          </ol>

          <div class="code-label">配置文件（mcp.json / claude_desktop_config.json）</div>
          <div class="code-block">
            <el-button size="small" class="copy-btn" @click="copyText(clientConfigJson, '配置')">复制</el-button>
            <pre>{{ clientConfigJson }}</pre>
          </div>

          <div class="code-label">或用 curl 自测连通性（把令牌换成你的）</div>
          <div class="code-block">
            <el-button size="small" class="copy-btn" @click="copyText(curlExample, 'curl 命令')">复制</el-button>
            <pre>{{ curlExample }}</pre>
          </div>
        </el-collapse-item>

        <el-collapse-item title="三、可用工具" name="3">
          <p class="tut-p">工具按令牌绑定用户的权限动态出现，常见如下（<b>均只读，不提供裸 SQL</b>）：</p>
          <ul class="tut-ul">
            <li><code>ask_data</code>：用自然语言问数，返回列、数据行与 AI 结论。</li>
            <li><code>list_datasources</code>：列出当前用户可见的数据源（id + 名称）。</li>
            <li><code>list_metrics</code>：列出已沉淀指标，优先复用已验证口径。</li>
            <li><code>refresh_metric</code>：刷新指定指标取最新值（快且口径稳定）。</li>
          </ul>
          <p class="tut-p">示例问法：「上个月的销售额是多少」「按品类统计销售额」「各渠道订单占比」。</p>
          <p class="tut-p dim">提问前建议先 <code>list_metrics</code> 看看有没有已沉淀指标，有则优先 <code>refresh_metric</code>。</p>
        </el-collapse-item>

        <el-collapse-item title="四、安全与限额" name="4">
          <ul class="tut-ul">
            <li>令牌绑定具体用户，<b>以他的角色执行</b>：权限、脱敏、配额、审计全部照常生效——MCP 不是绕过治理的旁路。</li>
            <li>限流：每用户约 30 次 / 分钟、每组织约 120 次 / 分钟，超出返回工具级错误（非 401）。</li>
            <li>令牌丢失即到本页 <b>吊销</b>，客户端会立即 401；服务端只存哈希，无法查看明文，请妥善保存。</li>
            <li>支持 OAuth 2.1 的客户端可读取 <code>/.well-known/oauth-protected-resource</code> 自动发现授权服务器；用 PAT 则直接带 <code>Bearer</code> 头即可。</li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" border stripe size="small">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="tokenPrefix" label="令牌前缀" min-width="180">
          <template #default="{ row }">
            <code>{{ row.tokenPrefix }}</code>
          </template>
        </el-table-column>
        <el-table-column label="所属用户" width="120">
          <template #default="{ row }">{{ userName(row.uid) }}</template>
        </el-table-column>
        <el-table-column prop="scopes" label="范围" width="110" />
        <el-table-column label="有效期至" width="170">
          <template #default="{ row }">{{ row.expiresAt ? formatDateTime(row.expiresAt) : '不过期' }}</template>
        </el-table-column>
        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">{{ row.lastUsedAt ? formatDateTime(row.lastUsedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.revokedAt" size="small" type="danger" effect="plain">已吊销</el-tag>
            <el-tag v-else-if="row.disabled" size="small" type="warning" effect="plain">已过期</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">有效</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              :disabled="!!row.revokedAt || rowBusy.isBusy(row.id)"
              :loading="rowBusy.isBusy(row.id)"
              @click="revoke(row)"
            >
              吊销
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span class="dim">该组织暂无 MCP 令牌</span>
        </template>
      </el-table>
    </el-card>

    <!-- 签发结果：明文只此一次 -->
    <el-dialog v-model="tokenVisible" title="令牌已签发（请立即保存）" width="560px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="关闭此窗口后将无法再次查看明文，服务端只保存哈希。" />
      <div class="token-box">
        <code>{{ createdToken }}</code>
      </div>
      <div class="token-usage dim">
        用法：<code>Authorization: Bearer {{ createdToken }}</code>，端点 <code>POST /mcp</code>
      </div>
      <template #footer>
        <el-button @click="tokenVisible = false">我已保存</el-button>
        <el-button type="primary" @click="copyToken">复制令牌</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminTenantApi, adminUserApi } from '@/api/admin'
import { mcpPatApi } from '@/api/mcpPat'
import type { McpPatVO } from '@/api/mcpPat'
import type { AdminUserVO, TenantVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { useRowBusy } from '@/utils/actionGuard'

/**
 * 对外 MCP 令牌管理（平台后台 · 运营组）。
 *
 * 按「组织 + 用户」签发个人访问令牌（PAT）。三个刻意的设计：
 * - 明文只在签发弹窗出现一次（服务端只存 SHA-256 哈希，无法再次展示）；
 * - 令牌绑定真实用户，MCP 调用以他的角色执行，治理全部照常生效；
 * - 吊销立即生效，且不提供"重新启用"——只能重新签发，避免误以为吊销可回滚。
 */
const loading = ref(false)
const creating = ref(false)
const list = ref<McpPatVO[]>([])
const tenants = ref<TenantVO[]>([])
const users = ref<AdminUserVO[]>([])
const tenantId = ref<number | null>(null)
const uid = ref<number | null>(null)
const rowBusy = useRowBusy<number>()

/* ---------------- 外部客户端教程（动态拼出当前站点端点） ---------------- */
/** MCP 端点：与前端同源，沿用当前站点 origin + /mcp（生产即 https://<域名>/mcp）。 */
const mcpUrl = computed(() => `${window.location.origin}/mcp`)
/** 客户端 mcp.json 配置样例（令牌为占位，需用户替换）。 */
const clientConfigJson = computed(() =>
  JSON.stringify(
    {
      mcpServers: {
        aibi: {
          type: 'http',
          url: mcpUrl.value,
          headers: { Authorization: 'Bearer aibi_pat_此处替换为你的令牌' }
        }
      }
    },
    null,
    2
  )
)
/** curl 自测连通性样例。 */
const curlExample = computed(
  () =>
    `curl -X POST ${mcpUrl.value} \\\n` +
    `  -H "Content-Type: application/json" \\\n` +
    `  -H "Authorization: Bearer aibi_pat_此处替换为你的令牌" \\\n` +
    `  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'`
)

/* ---------------- 签发结果 ---------------- */
const tokenVisible = ref(false)
const createdToken = ref('')

async function load(): Promise<void> {
  if (!tenantId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    list.value = await mcpPatApi.list(tenantId.value)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 按用户 id 回显昵称（列表只返回 uid，避免为展示再拉一次用户接口）。 */
function userName(id: number): string {
  const u = users.value.find((x) => x.id === id)
  return u ? u.nickname || u.username : `#${id}`
}

function onTenantChange(): void {
  uid.value = null
  users.value = []
  void loadUsers()
  void load()
}

async function loadUsers(): Promise<void> {
  if (!tenantId.value) return
  try {
    users.value = await adminUserApi.list(tenantId.value)
  } catch {
    users.value = []
  }
}

async function openCreate(): Promise<void> {
  if (!tenantId.value || !uid.value) return
  const target = users.value.find((u) => u.id === uid.value)
  const label = target ? target.nickname || target.username : `#${uid.value}`
  try {
    await ElMessageBox.confirm(
      `将为「${label}」签发一枚 MCP 令牌（默认 90 天）。令牌以该用户的角色执行问数，权限与配额照常生效。`,
      '确认签发',
      { type: 'warning', confirmButtonText: '签发', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  creating.value = true
  try {
    const res = await mcpPatApi.create({
      tenantId: tenantId.value,
      uid: uid.value,
      name: `MCP-${label}`,
      days: 90
    })
    createdToken.value = res.token
    tokenVisible.value = true
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

async function revoke(row: McpPatVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `吊销后使用该令牌的客户端将立即无法调用（401）。此操作不可撤销，只能重新签发。\n\n令牌：${row.tokenPrefix}`,
      '确认吊销',
      { type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await rowBusy.run(row.id, async () => {
    await mcpPatApi.revoke(row.id)
    ElMessage.success('已吊销')
    await load()
  })
}

async function copyToken(): Promise<void> {
  try {
    await navigator.clipboard.writeText(createdToken.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

/** 教程里的配置 / curl 一键复制。 */
async function copyText(text: string, label: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制${label}`)
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

onMounted(async () => {
  try {
    tenants.value = await adminTenantApi.list()
    // 默认选中第一个组织，避免空页无从下手
    if (tenants.value.length) {
      tenantId.value = tenants.value[0].id
      await loadUsers()
      await load()
    }
  } catch {
    // 拦截器已提示
  }
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.head-card :deep(.el-card__header) {
  padding: 12px 16px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.field-label {
  font-size: 13px;
  color: var(--text-secondary, #606266);
}
.tip {
  margin-top: 12px;
}
.dim {
  color: var(--text-secondary, #909399);
  font-size: 12.5px;
}
.option-sub {
  float: right;
  margin-left: 12px;
}
.token-box {
  margin: 12px 0;
  padding: 12px;
  border: 1px dashed #d9d9d9;
  border-radius: 8px;
  background: var(--fill-lighter);
  word-break: break-all;
  font-size: 12.5px;
  line-height: 1.6;
}
.token-usage {
  word-break: break-all;
}
.tutorial-card {
  scroll-margin-top: 12px;
}
.tut-p {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular, #303133);
}
.tut-ul,
.tut-ol {
  margin: 0 0 8px;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-regular, #303133);
}
.tut-ul li,
.tut-ol li {
  margin-bottom: 2px;
}
.code-label {
  font-size: 12.5px;
  color: var(--text-secondary, #606266);
  margin: 12px 0 6px;
}
.code-block {
  position: relative;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: #1e1e1e;
  margin-bottom: 4px;
}
.code-block pre {
  margin: 0;
  padding: 12px 14px;
  color: #e6e6e6;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  overflow-x: auto;
}
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
