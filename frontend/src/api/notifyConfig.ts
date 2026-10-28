import { get, post, put } from './request'

/**
 * 平台「通知配置」接口（/api/admin/notify-config，SUPER_ADMIN）。
 *
 * <p>配置按渠道分组（email / sms / dingtalk / wecom / feishu / pushplus）。**密钥不回显**：
 * 读取时敏感字段（SMTP 密码、短信 AccessKeySecret、webhook、PushPlus token）一律为空，
 * 另由 `secretsSet["渠道.字段"]` 标明"是否已配置"；提交时<b>留空表示保持原值</b>（不是清空）。
 */
export interface NotifyConfig {
  /** 兜底渠道码：规则指定的渠道未接入/不可用时的回落目标。 */
  fallbackChannel: string
  email?: Record<string, unknown>
  sms?: Record<string, unknown>
  dingtalk?: Record<string, unknown>
  wecom?: Record<string, unknown>
  feishu?: Record<string, unknown>
  pushplus?: Record<string, unknown>
  /** 形如 `"email.password": true` —— 密钥是否已配置（密钥本身不回显）。 */
  secretsSet?: Record<string, boolean>
}

/** 渠道可用性（页面标注「可用 / 未配置」）。 */
export interface NotifyChannelState {
  channel: string
  usable: boolean
}

export const notifyConfigApi = {
  /** 读取配置（密钥不回显）。 */
  get: () => get<NotifyConfig>('/admin/notify-config'),
  /** 保存配置（密钥留空=保持原值）；保存即生效。 */
  save: (data: Record<string, unknown>) => put<NotifyConfig>('/admin/notify-config', data),
  /** 渠道清单与可用性 + 当前兜底渠道。 */
  channels: () =>
    get<{ channels: NotifyChannelState[]; fallbackChannel: string }>('/admin/notify-config/channels'),
  /**
   * 连通性测试：用该渠道本身发一条测试通知（不走兜底回落，失败原因原样返回）。
   * @param target EMAIL=收件人、SMS=手机号；webhook 类与 PushPlus 用配置里的地址，可不传
   */
  test: (channel: string, target?: string) =>
    post<{ ok: boolean; message: string }>(`/admin/notify-config/test/${channel}`, null, {
      params: target ? { target } : {}
    })
}
