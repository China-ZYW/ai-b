import { ref, type Ref } from 'vue'

/**
 * 写动作的**统一并发护栏**（v3.21 / §38）。
 *
 * <p>背景：写操作散落在 30 余个页面与组件里，此前各页各写各的护栏，出现三种典型缺陷——
 * ①`submitting.value = true` 前没有重入早退，只靠按钮 loading 兜底；
 * ②行级开关用**单个** `togglingId` ref，并发操作两行时后一行的 id 覆盖前一行，
 *   前一行的 loading 指示消失、且开关未禁用可被反复翻转，产生竞争写；
 * ③部分页面（如样例、预警规则）的开关完全没有护栏。
 *
 * <p>本模块是全项目唯一的护栏实现。**新增写操作时请复用这里，不要各写各的**。
 *
 * <p>注意分工：请求层的「同请求在途去重」（见 `@/api/request`）负责兜住**完全相同**的重复提交；
 * 本模块负责**交互语义**——把"正在处理中"如实反映到界面上，并阻止用户发起语义冲突的第二次操作
 * （例如同一行的开关在途时再次翻转，两次请求 body 不同、请求层无法去重）。
 * 两者互补，缺一不可。
 */

/** 单动作护栏：同一动作在途时，后来的调用直接早退（返回 `undefined`）。 */
export interface ActionGuard {
  /** 是否有动作在途（可直接绑到按钮的 `:loading`）。 */
  busy: Ref<boolean>
  /**
   * 执行动作；已有动作在途时**不再执行**，返回 `undefined`。
   *
   * @param action 真正的写动作
   * @returns 动作的返回值；被护栏拦下时为 `undefined`
   */
  run: <T>(action: () => Promise<T>) => Promise<T | undefined>
}

/**
 * 单动作护栏（用于"保存""重置""批量提交"这类同一时刻只应有一次的动作）。
 *
 * <p>典型用法：
 * ```ts
 * const { busy: submitting, run } = useActionGuard()
 * async function onSubmit(): Promise<void> {
 *   await run(async () => {
 *     await api.save(payload)
 *     ElMessage.success('已保存')
 *     await load()
 *   })
 * }
 * ```
 * 模板里 `:loading="submitting"` 即可。
 */
export function useActionGuard(): ActionGuard {
  const busy = ref(false)

  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return undefined
    busy.value = true
    try {
      return await action()
    } finally {
      busy.value = false
    }
  }

  return { busy, run }
}

/** 行级护栏：按行 id 独立记录在途状态，不同行之间互不干扰。 */
export interface RowBusyGuard<K extends string | number> {
  /** 该行是否有动作在途（可直接绑到开关/按钮的 `:loading`）。 */
  isBusy: (id: K) => boolean
  /** 是否有任意一行在途（用于"有行正在处理"这类整体提示）。 */
  anyBusy: () => boolean
  /** 在途行数。 */
  busyCount: () => number
  /**
   * 执行该行的动作；该行已有动作在途时**不再执行**，返回 `undefined`。
   *
   * @param id 行标识（必须是稳定且唯一的，如主键）
   * @param action 该行的写动作
   */
  run: <T>(id: K, action: () => Promise<T>) => Promise<T | undefined>
}

/**
 * 行级护栏（用于表格里的启停开关、行内按钮）。
 *
 * <p>**为什么不能用单个 `ref<number|null>`**：那个写法只能表达"某一行在处理中"，
 * 并发操作第 2 行时第 1 行的状态被覆盖——第 1 行看起来"没在加载了"，用户会以为失败而再点一次，
 * 于是同一行产生两次竞争写。按行 id 分桶后，每一行的在途状态互不干扰。
 *
 * <p>典型用法（表格内开关，注意同时绑 `:loading` 与 `:disabled`）：
 * ```vue
 * <el-switch
 *   :model-value="row.enabled"
 *   :loading="rowBusy.isBusy(row.id)"
 *   :disabled="rowBusy.isBusy(row.id)"
 *   @change="(v: number) => onToggle(row, v)"
 * />
 * ```
 */
export function useRowBusy<K extends string | number>(): RowBusyGuard<K> {
  // 按字符串 key 分桶（与 ChatView 的 `feedbackBusy[hid]` 同一手法）。
  // 不用 `ref(new Set<K>())`：`Ref<Set<K>>` 的 `.value` 会被 Vue 的类型解包成
  // `Set<UnwrapRefSimple<K>>`，对泛型 K 无法证明与 K 等价，类型检查过不去；
  // 用 `Record<string, true>` 没有类型参数，解包后类型恒等，且依然是响应式的。
  // key 前缀区分类型，避免数字 1 与字符串 '1' 撞桶。
  const busyIds = ref<Record<string, true>>({})

  function keyOf(id: K): string {
    return typeof id === 'string' ? `s:${id}` : `n:${id}`
  }

  function isBusy(id: K): boolean {
    return busyIds.value[keyOf(id)] === true
  }

  function anyBusy(): boolean {
    return Object.keys(busyIds.value).length > 0
  }

  function busyCount(): number {
    return Object.keys(busyIds.value).length
  }

  async function run<T>(id: K, action: () => Promise<T>): Promise<T | undefined> {
    const key = keyOf(id)
    if (busyIds.value[key]) return undefined
    busyIds.value[key] = true
    try {
      return await action()
    } finally {
      delete busyIds.value[key]
    }
  }

  return { isBusy, anyBusy, busyCount, run }
}

/**
 * 内容指纹：把任意值归一化成**与键序无关**的字符串，用于判断"用户到底改没改"。
 *
 * <p>为什么不用 `JSON.stringify`：同一份配置在前端编辑框里过一遍再提交，键序与缩进都可能变，
 * 逐字符比较必然误报"有变化"。本函数对对象键排序后序列化，键序差异不影响结果。
 *
 * <p>用途仅限**交互提示**（"内容未变化，未提交"）。真正的权威判定必须在后端做
 * （见 `ConfigService.upsert` 的幂等契约）：前端擅自认定"无变化"而拒绝提交，会吞掉用户
 * 真实但细微的改动。
 */
export function fingerprint(value: unknown): string {
  return stableStringify(value, new WeakSet<object>())
}

function stableStringify(value: unknown, seen: WeakSet<object>): string {
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'
  const type = typeof value
  if (type === 'number' || type === 'boolean' || type === 'bigint') return String(value)
  if (type === 'string') return JSON.stringify(value)
  if (type === 'function' || type === 'symbol') return 'undefined'
  if (value instanceof Date) return `"${value.toISOString()}"`

  const obj = value as object
  // 循环引用：退化为原型的粗略标识，避免无限递归（此时指纹不精确，但不会崩）
  if (seen.has(obj)) return '"[circular]"'
  seen.add(obj)

  if (Array.isArray(obj)) {
    const items = obj.map((item) => stableStringify(item, seen))
    return `[${items.join(',')}]`
  }

  const keys = Object.keys(obj).sort()
  const parts = keys.map((k) => `${JSON.stringify(k)}:${stableStringify((obj as Record<string, unknown>)[k], seen)}`)
  return `{${parts.join(',')}}`
}
