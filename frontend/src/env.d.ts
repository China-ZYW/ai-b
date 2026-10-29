/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const component: DefineComponent<{}, {}, any>
  export default component
}

// Vite 运行期注入的前端环境变量（与 frontend/.env.example 保持同步）。
// 未写入时一律为 undefined，IcpFooter / LandingTryStats 等消费方自行给默认。
interface ImportMetaEnv {
  /** 备案主体名称（公司全称 / 个人站可写"个人"） */
  readonly VITE_ICP_COMPANY?: string
  /** ICP 备案号，例："蜀ICP备2026000000号-1" */
  readonly VITE_ICP_NUMBER?: string
  /** 落地页 Demo 嵌入地址（可选） */
  readonly VITE_DEMO_EMBED_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
