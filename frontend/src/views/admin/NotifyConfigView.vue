<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyConfigApi, type NotifyConfig } from '@/api/notifyConfig'

/**
 * 平台后台 · 通知配置（/api/admin/notify-config）。
 *
 * <p>维护预警通知各渠道的参数：SMTP / 短信（阿里云、腾讯云）/ 钉钉 / 企微 / 飞书 / PushPlus，
 * 以及"渠道未配置时回落到哪个兜底渠道"。改完保存<b>立即生效</b>（通知实现发送时现读配置，无需重启）。
 *
 * <p><b>密钥不回显</b>：密码 / 密钥 / webhook / token 读取时为空，用 placeholder 提示"已配置（留空保持不变）"；
 * 提交时留空即保持原值，不会把已配好的密钥清掉。
 */

type FieldType = 'text' | 'password' | 'number' | 'switch' | 'select'

interface FieldDef {
  key: string
  label: string
  type?: FieldType
  options?: { label: string; value: string }[]
  hint?: string
  placeholder?: string
}

interface ChannelDef {
  key: string
  code: string
  title: string
  desc: string
  /** 测试发送需要目标时（邮件=收件人、短信=手机号）显示一个输入框。 */
  target?: { label: string; placeholder: string }
  fields: FieldDef[]
}

const TIMEOUT: FieldDef = { key: 'timeoutMs', label: '超时(毫秒)', type: 'number', hint: '挂在预警扫描线程上，必须限时' }

const CHANNELS: ChannelDef[] = [
  {
    key: 'email',
    code: 'EMAIL',
    title: '邮件（SMTP）',
    desc: '规则渠道选「邮件」时，给规则的收件人发送纯文本告警邮件。',
    target: { label: '测试收件人', placeholder: 'someone@example.com' },
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      { key: 'host', label: 'SMTP 主机', placeholder: 'smtp.exmail.qq.com' },
      { key: 'port', label: '端口', type: 'number', hint: '465=隐式 TLS，587=STARTTLS' },
      { key: 'username', label: '账号' },
      { key: 'password', label: '密码 / 授权码', type: 'password' },
      { key: 'from', label: '发件人', hint: '留空则用账号' },
      { key: 'sslEnabled', label: 'SSL', type: 'switch' },
      TIMEOUT
    ]
  },
  {
    key: 'sms',
    code: 'SMS',
    title: '短信',
    desc: '规则渠道选「短信」时，给规则的手机号发送短信；模板变量统一取"级别+规则名+当前值"。',
    target: { label: '测试手机号', placeholder: '13800138000' },
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      {
        key: 'provider',
        label: '服务商',
        type: 'select',
        options: [
          { label: '阿里云（Dysmsapi）', value: 'ALIYUN' },
          { label: '腾讯云（SMS 2021-01-11）', value: 'TENCENT' }
        ]
      },
      { key: 'signName', label: '短信签名' },
      { key: 'templateCode', label: '阿里云模板 CODE', hint: '如 SMS_123456' },
      { key: 'templateParamKey', label: '阿里云变量名', hint: '默认 name' },
      { key: 'templateId', label: '腾讯云模板 ID' },
      { key: 'sdkAppId', label: '腾讯云 SdkAppId' },
      { key: 'region', label: '区域', hint: '阿里云 cn-hangzhou / 腾讯云 ap-guangzhou' },
      { key: 'accessKeyId', label: 'AccessKeyId' },
      { key: 'accessKeySecret', label: 'AccessKeySecret', type: 'password' },
      TIMEOUT
    ]
  },
  {
    key: 'dingtalk',
    code: 'DINGTALK',
    title: '钉钉群机器人',
    desc: '群机器人 Webhook 推送；开启"加签"时填 Secret，会自动拼接 timestamp 与 sign。',
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      { key: 'webhook', label: 'Webhook 地址', type: 'password' },
      { key: 'secret', label: '加签 Secret', type: 'password', hint: '未开启加签则留空' },
      TIMEOUT
    ]
  },
  {
    key: 'wecom',
    code: 'WECOM',
    title: '企业微信群机器人',
    desc: '群机器人 Webhook 推送（text 消息）。',
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      { key: 'webhook', label: 'Webhook 地址', type: 'password' },
      TIMEOUT
    ]
  },
  {
    key: 'feishu',
    code: 'FEISHU',
    title: '飞书自定义机器人',
    desc: '自定义机器人 Webhook 推送（text 消息）。',
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      { key: 'webhook', label: 'Webhook 地址', type: 'password' },
      TIMEOUT
    ]
  },
  {
    key: 'pushplus',
    code: 'PUSHPLUS',
    title: 'PushPlus（微信推送）',
    desc: '只需填 token；地址固定为 PushPlus 官方网关。',
    fields: [
      { key: 'enabled', label: '启用', type: 'switch' },
      { key: 'token', label: 'Token', type: 'password' },
      TIMEOUT
    ]
  }
]

const FALLBACK_OPTIONS = [
  { label: '站内日志 (LOG)', value: 'LOG' },
  { label: '邮件 (EMAIL)', value: 'EMAIL' },
  { label: '短信 (SMS)', value: 'SMS' },
  { label: '钉钉 (DINGTALK)', value: 'DINGTALK' },
  { label: '企业微信 (WECOM)', value: 'WECOM' },
  { label: '飞书 (FEISHU)', value: 'FEISHU' },
  { label: 'PushPlus (PUSHPLUS)', value: 'PUSHPLUS' }
]

/** 表单模型：channelKey → 字段表。 */
const form = reactive<Record<string, Record<string, unknown>>>({
  email: {},
  sms: {},
  dingtalk: {},
  wecom: {},
  feishu: {},
  pushplus: {}
})
const fallbackChannel = ref('LOG')
/** 密钥是否已配置（密钥本身不回显）。 */
const secretsSet = ref<Record<string, boolean>>({})
/** 渠道当前是否可用（后端按配置判定）。 */
const usable = ref<Record<string, boolean>>({})
const loading = ref(false)
const saving = ref(false)
const testing = reactive<Record<string, boolean>>({})
const testTargets = reactive<Record<string, string>>({})

function applyConfig(c: NotifyConfig): void {
  fallbackChannel.value = c.fallbackChannel || 'LOG'
  const bag = c as unknown as Record<string, unknown>
  for (const ch of CHANNELS) {
    const src = (bag[ch.key] as Record<string, unknown> | undefined) ?? {}
    form[ch.key] = { ...src }
    // 密钥字段读取时为空：保留空串，占位提示由 secretsSet 决定
    for (const f of ch.fields) {
      if (f.type === 'password') form[ch.key][f.key] = ''
    }
  }
  secretsSet.value = c.secretsSet ?? {}
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [c, st] = await Promise.all([notifyConfigApi.get(), notifyConfigApi.channels()])
    applyConfig(c)
    const map: Record<string, boolean> = {}
    for (const item of st.channels ?? []) map[item.channel] = item.usable
    usable.value = map
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

async function save(): Promise<void> {
  saving.value = true
  try {
    const payload: Record<string, unknown> = { fallbackChannel: fallbackChannel.value }
    for (const ch of CHANNELS) payload[ch.key] = { ...form[ch.key] }
    const c = await notifyConfigApi.save(payload)
    applyConfig(c)
    await load()
    ElMessage.success('已保存，立即生效（无需重启）')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function test(def: ChannelDef): Promise<void> {
  testing[def.code] = true
  try {
    const target = testTargets[def.key]?.trim()
    if (def.target && !target) {
      ElMessage.warning(`请先填写${def.target.label}`)
      return
    }
    const r = await notifyConfigApi.test(def.code, target)
    if (r?.ok) ElMessage.success(r.message || '已发送')
    else ElMessage.error(r?.message || '发送失败')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    testing[def.code] = false
  }
}

/** 密钥字段占位：已配置则提示"留空保持不变"。 */
function secretPlaceholder(def: ChannelDef, f: FieldDef): string {
  if (f.type !== 'password') return f.placeholder ?? ''
  return secretsSet.value[`${def.key}.${f.key}`] ? '已配置（留空保持不变）' : '未配置'
}
</script>

<template>
  <div class="notify-config-view" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="ph-title">通知配置</h2>
        <p class="ph-desc">
          维护智能预警的外发渠道参数：SMTP、短信（阿里云 / 腾讯云）、钉钉、企业微信、飞书、PushPlus。
          <strong>保存即生效</strong>，无需重启；密钥（密码 / 密钥 / Webhook / token）<strong>不回显</strong>，
          编辑时留空表示保持不变。规则选中的渠道未配置或不可用时，会自动回落到下方「兜底渠道」。
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
      </div>
    </div>

    <div class="card fallback-card">
      <span class="fb-label">兜底渠道</span>
      <el-select v-model="fallbackChannel" style="width: 220px">
        <el-option v-for="o in FALLBACK_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <span class="fb-hint muted">
        规则指定的渠道未接入 / 不可用时的回落目标；若兜底渠道也不可用，最终落到站内日志（告警绝不会静默消失）。
      </span>
    </div>

    <div class="channel-grid">
      <div v-for="def in CHANNELS" :key="def.key" class="card channel-card">
        <div class="cc-head">
          <span class="cc-title">{{ def.title }}</span>
          <el-tag :type="usable[def.code] ? 'success' : 'info'" size="small" effect="plain">
            {{ usable[def.code] ? '可用' : '未配置' }}
          </el-tag>
        </div>
        <p class="cc-desc muted">{{ def.desc }}</p>

        <el-form label-width="130px" label-position="left" size="default">
          <el-form-item v-for="f in def.fields" :key="f.key" :label="f.label">
            <el-switch v-if="f.type === 'switch'" v-model="form[def.key][f.key]" />
            <el-input-number
              v-else-if="f.type === 'number'"
              v-model="form[def.key][f.key] as number"
              :min="0"
              :controls="false"
              style="width: 160px"
            />
            <el-select v-else-if="f.type === 'select'" v-model="form[def.key][f.key]" style="width: 240px">
              <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input
              v-else
              v-model="form[def.key][f.key] as string"
              :type="f.type === 'password' ? 'password' : 'text'"
              :show-password="f.type === 'password'"
              :placeholder="secretPlaceholder(def, f)"
              clearable
            />
            <span v-if="f.hint" class="field-hint muted">{{ f.hint }}</span>
          </el-form-item>
        </el-form>

        <div class="cc-foot">
          <el-input
            v-if="def.target"
            v-model="testTargets[def.key]"
            :placeholder="def.target.placeholder"
            style="width: 200px"
            clearable
          />
          <el-button
            type="primary"
            plain
            :loading="testing[def.code]"
            @click="test(def)"
          >
            测试发送
          </el-button>
          <span class="muted cc-foot-hint">测试直接走该渠道本身（不回落），失败原因会原样提示。</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notify-config-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.ph-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0 0 4px;
}
.ph-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 820px;
  line-height: 1.7;
}
.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.fallback-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  flex-wrap: wrap;
}
.fb-label {
  font-weight: 600;
}
.fb-hint {
  font-size: 12.5px;
}
.channel-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(430px, 1fr));
  gap: 16px;
}
.channel-card {
  padding: 14px 16px;
}
.cc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.cc-title {
  font-size: 15px;
  font-weight: 700;
}
.cc-desc {
  margin: 6px 0 12px;
  font-size: 12.5px;
  line-height: 1.6;
}
.field-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}
.cc-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-light);
}
.cc-foot-hint {
  font-size: 12px;
}
</style>
