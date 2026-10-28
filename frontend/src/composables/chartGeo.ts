import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'

/**
 * 地图 geoJSON 加载器（问数准确率与图表能力升级 V3 - MAP 图表）。
 *
 * ECharts 的 `map` series 需先 `registerMap('china', geoJSON)` 才能渲染。geoJSON 体积大且不随
 * 业务变化，故走 CDN（阿里 DataV）按需拉取，用 promise 缓存避免重复请求；网络不可达时返回 false，
 * 由调用方（BaseChart）回落明细表，绝不抛错阻断问数结果展示。
 */

/** 注册用的地图名（与 useChartOption 的 MAP series `map` 字段一致）。 */
export const CHINA_MAP = 'china'

/** 阿里 DataV 全国 geoJSON（含省级边界）。 */
const GEO_URL = 'https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json'

let loadPromise: Promise<boolean> | null = null

/** 中国地图 geoJSON 是否已注册到 echarts。 */
export function isChinaMapRegistered(): boolean {
  return !!echarts.getMap(CHINA_MAP)
}

/**
 * 确保中国地图已注册（CDN 拉取 + registerMap）。promise 缓存：并发/重复调用只拉取一次；
 * 失败时清空缓存以便下次重试，返回 false（不抛错）。
 */
export function ensureChinaMap(): Promise<boolean> {
  if (isChinaMapRegistered()) return Promise.resolve(true)
  if (loadPromise) return loadPromise
  loadPromise = fetch(GEO_URL)
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error('http ' + r.status))))
    .then((geo) => {
      echarts.registerMap(CHINA_MAP, geo)
      return true
    })
    .catch(() => {
      loadPromise = null
      return false
    })
  return loadPromise
}

/** 判断一个 option 是否含需要中国地图的 map series（供 BaseChart 决定是否异步加载 geoJSON）。 */
export function optionNeedsChinaMap(option: EChartsOption | null | undefined): boolean {
  const series = option?.series as unknown
  if (!series) return false
  const list = Array.isArray(series) ? series : [series]
  return list.some(
    (s) => !!s && typeof s === 'object' && (s as { type?: string }).type === 'map'
      && ((s as { map?: string }).map === CHINA_MAP || !(s as { map?: string }).map)
  )
}
