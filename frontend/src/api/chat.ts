import { get, post } from './request'
import type { ChatQueryRequest, ChatQueryResponse, SkillVO } from '@/types'

/**
 * 问数接口（/api/chat）。
 * 同步一次性返回用 query；分步进度推送（SSE）见 @/api/sse 的 askStream。
 */
export const chatApi = {
  /**
   * 问数（同步返回）。
   *
   * <p>显式关闭请求层「同请求在途去重」（`dedupe: false`）：问数是用户的核心动作，
   * 同一问题连问两次是**用户的真实意图**（换个说法对比、或上一次答得不满意想重答）。
   * 静默合并会把第二次提问吞掉，而历史记录与配额消耗却只记一次——用户看到的是
   * "点了没反应"，比多答一次更难排查。因此这里刻意让每次点击都落到一次真实问数上。
   */
  query: (data: ChatQueryRequest) => post<ChatQueryResponse>('/chat/query', data, { dedupe: false }),
  /** 推荐问题（问题4）：依数据源 schema 由 LLM 智能生成，带缓存与降级兜底（永不报错）。 */
  suggestions: (datasourceId: number) =>
    get<string[]>('/chat/suggestions', { params: { datasourceId } }),
  /**
   * 上下文追问：依本轮问数（historyId）由 LLM 生成"接着答案往下钻"的追问。
   * 失败/空返回空数组，调用方回落静态推荐问题。
   */
  followups: (historyId: number) => get<string[]>('/chat/followups', { params: { historyId } }),
  /**
   * 问数技能清单（V4）：平台当前可用的答问能力与其是否免大模型。
   * 用于结果面板标注"本次由哪个技能回答"，以及调试时强制指定技能。
   */
  skills: () => get<SkillVO[]>('/chat/skills')
}
