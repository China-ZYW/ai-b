/**
 * 让 :style 绑定支持 CSS 自定义属性（--*）：大屏 / 卡片主题令牌以 CSS 变量下发
 * （见 @/theme/dashboardTheme）。
 *
 * 关键：本文件必须是「模块」——顶部的 `export {}` 不可删除。否则 `declare module '@vue/runtime-dom'`
 * 会被 TypeScript 当作「环境模块声明」而整体**替换**真实的 @vue/runtime-dom，导致 vue 经
 * `export * from '@vue/runtime-dom'` 转出的 ref/computed/onMounted 等全部丢失（TS2305）。
 * 有了 `export {}` 后，它才是「模块增强」，与原模块声明合并。
 */
export {}

declare module '@vue/runtime-dom' {
  interface CSSProperties {
    [key: `--${string}`]: string | number
  }
}
