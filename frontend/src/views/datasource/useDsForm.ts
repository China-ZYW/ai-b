/**
 * 数据源「新建 / 编辑」对话框状态机（T7.3 自 DataSourceView 逐字搬移）。
 * 含表单、校验规则、本地上传解析与工作表勾选域（两者经 form 深度耦合，同域持有）。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, type CheckboxValueType, type FormInstance, type FormRules, type UploadRawFile, type UploadRequestOptions } from 'element-plus'
import { useDatasourceStore } from '@/stores/datasource'
import type { DataSourceRequest, DataSourceVO, FileSheetPreview, FileUploadResult, TestConnectionResult } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'
import {
  MAX_UPLOAD_SIZE,
  defaultPort,
  emptyForm,
  isFileType,
  isLocalFileType,
  isObjectStorage,
  regionOf,
  type DsForm
} from './dsUtils'

export function useDsForm() {
  const dsStore = useDatasourceStore()

  /* ---------------- 新建 / 编辑对话框 ---------------- */
  const dialogVisible = ref(false)
  const editingId = ref<number | null>(null)
  const { busy: submitting, run: runSubmit } = useActionGuard()
  const testing = ref(false)
  const testResult = ref<TestConnectionResult | null>(null)
  const formRef = ref<FormInstance>()

  const form = reactive<DsForm>(emptyForm())
  const isEdit = computed(() => editingId.value != null)
  const isFile = computed(() => isFileType(form.type))

  /* ---------------- 跨库联合分析 ---------------- */
  /**
   * 「额外库/模式」下拉候选，来自最近一次「测试连接」返回的清单（后端已剔除系统库并排序）。
   * 主库自身不在候选里：它默认就参与扫表，选进来只会让同一批表在提示词里出现两份。
   */
  const catalogOptions = computed<string[]>(() => {
    const primary = form.database.trim().toLowerCase()
    return (testResult.value?.catalogs ?? []).filter((c) => c.toLowerCase() !== primary)
  })

  /**
   * 输入项下方的说明。
   * 未测连接时没有候选清单，得讲清楚清单从哪来，否则用户面对一个空下拉无从下手；
   * 已选时则讲清后果（表名带前缀、可跨库 JOIN），避免用户以为只是“多看几张表”。
   */
  const catalogTip = computed<string>(() =>
    catalogOptions.value.length > 0
      ? `已选 ${form.extraCatalogs.length} 个额外库。它们的表名带「库.」前缀，可与主库的表直接 JOIN；清空则只分析主库。`
      : '留空 = 只分析主库。点「测试连接」可拉取本服务器上的库清单再选，也可直接输入库名后回车。'
  )

  /* ---------------- 本地上传（问题3） ---------------- */
  const uploading = ref(false)
  const uploadResult = ref<FileUploadResult | null>(null)

  /** 当前正在预览的工作表名（多 sheet 时点"预览"切换）。 */
  const activeSheet = ref('')

  /** 可导入的工作表（首行有表头）；封面页/说明页不在此列，单独置灰展示。 */
  const importableSheets = computed<FileSheetPreview[]>(() =>
    (uploadResult.value?.sheets ?? []).filter((s) => s.importable)
  )

  /** 无可导入工作表时为 null（上传接口已拦下这种情况，仅作防御）。 */
  const currentSheet = computed<FileSheetPreview | null>(() => {
    const list = importableSheets.value
    if (!list.length) return null
    return list.find((s) => s.name === activeSheet.value) ?? list[0]
  })

  /** 预览表格数据：把 string[][] 转为按列索引取值的对象数组（跟随当前选中的工作表）。 */
  const previewTableData = computed<Record<string, string>[]>(() => {
    const s = currentSheet.value
    if (!s) return []
    return s.previewRows.map((row) => {
      const obj: Record<string, string> = {}
      s.columns.forEach((_, i) => {
        obj[String(i)] = row[i] ?? ''
      })
      return obj
    })
  })

  /** 全选 / 清空工作表勾选（按工作簿原序写回，保证建表顺序稳定可预期）。 */
  function selectAllSheets(): void {
    form.sheetNames = importableSheets.value.map((s) => s.name)
  }
  function clearSheets(): void {
    form.sheetNames = []
  }

  /**
   * 「全选」总开关。取消全选后名单为空，而空数组在后端的含义是「导入全部」，
   * 无法表达「一张也不要」，故 onTest/onSubmit 会拦下并提示，不会静默变成全选。
   */
  function onToggleAll(checked: CheckboxValueType): void {
    if (checked === true) selectAllSheets()
    else clearSheets()
  }

  /** 无表头而被跳过的工作表（封面页/说明页/空表），置灰展示以免用户以为解析漏了。 */
  const skippedSheets = computed<FileSheetPreview[]>(() =>
    (uploadResult.value?.sheets ?? []).filter((s) => !s.importable)
  )

  const someChecked = computed<boolean>(() => form.sheetNames.length > 0)
  const allChecked = computed<boolean>(
    () => importableSheets.value.length > 0 && form.sheetNames.length === importableSheets.value.length
  )

  /**
   * 勾选 / 取消单张工作表。
   * 重写整个数组而非 push/splice：一是按工作簿原序落库使建表顺序稳定可预期，
   * 二是 el-checkbox 的 change 事件回传 CheckboxValueType（可能为字符串/数字），统一在此收敛成布尔。
   */
  function toggleSheet(name: string, checked: CheckboxValueType): void {
    const on = checked === true
    const set = new Set(form.sheetNames)
    if (on) set.add(name)
    else set.delete(name)
    form.sheetNames = importableSheets.value.filter((s) => set.has(s.name)).map((s) => s.name)
  }

  /**
   * 未上传（编辑已存数据源、或手填服务器路径）时的导入范围说明。
   * 此时没有工作表清单可勾选，只把已保存的选择讲清楚，避免用户以为设置丢了。
   */
  const savedSheetSummary = computed<string>(() => {
    if (uploadResult.value || !form.filePath.trim()) return ''
    const merge = form.sheetMerge ? '，列结构一致的工作表合并为一张表' : ''
    return form.sheetNames.length
      ? `已选 ${form.sheetNames.length} 张：${form.sheetNames.join('、')}${merge}`
      : `导入全部工作表${merge}`
  })

  /** 上传前校验：类型 + 大小。 */
  function beforeUpload(file: UploadRawFile): boolean {
    const lower = file.name.toLowerCase()
    const okType =
      lower.endsWith('.csv') || lower.endsWith('.xlsx') || lower.endsWith('.xls') || lower.endsWith('.parquet')
    if (!okType) {
      ElMessage.error('仅支持 CSV / Excel / Parquet 文件（.csv / .xlsx / .xls / .parquet）')
      return false
    }
    if (file.size > MAX_UPLOAD_SIZE) {
      ElMessage.error('文件过大，请上传不超过 20MB 的文件')
      return false
    }
    return true
  }

  /** 自定义上传：调后端落盘 + 解析，回填 filePath、默认全选工作表并展示预览（类型依后端识别自动切换）。 */
  async function customUpload(options: UploadRequestOptions): Promise<void> {
    uploading.value = true
    uploadResult.value = null
    try {
      const res = await dsStore.upload(options.file)
      uploadResult.value = res
      form.filePath = res.filePath
      if (res.type === 'CSV' || res.type === 'EXCEL' || res.type === 'PARQUET') form.type = res.type
      const importable = (res.sheets ?? []).filter((s) => s.importable)
      activeSheet.value = importable[0]?.name ?? ''
      // 默认全选；只有一张可导入表时置空（= 全部），避免无谓地存一份选择
      form.sheetNames = importable.length > 1 ? importable.map((s) => s.name) : []
      form.sheetMerge = false
      const skipped = (res.sheets ?? []).length - importable.length
      ElMessage.success(
        importable.length > 1
          ? `已解析「${res.originalName}」，共 ${importable.length} 张可导入工作表`
            + (skipped > 0 ? `（${skipped} 张无表头已跳过）` : '')
          : `已解析「${res.originalName}」，共 ${res.columns.length} 列`
      )
    } catch {
      // 拦截器已提示（含文件过大 / 类型不支持 / 落盘失败）
    } finally {
      uploading.value = false
    }
  }

  const rules = computed<FormRules>(() => ({
    name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
    type: [{ required: true, message: '请选择类型', trigger: 'change' }],
    host: isLocalFileType(form.type)
      ? []
      : [{ required: true, message: isObjectStorage(form.type) ? '请输入访问域名 / Endpoint' : '请输入主机地址', trigger: 'blur' }],
    database: isLocalFileType(form.type)
      ? []
      : [{ required: true, message: isObjectStorage(form.type) ? '请输入 Bucket 名称' : '请输入数据库名', trigger: 'blur' }],
    filePath: isFileType(form.type)
      ? [{ required: true, message: isObjectStorage(form.type) ? '请输入对象 Key（如 data/orders.parquet）' : '请输入文件路径', trigger: 'blur' }]
      : [],
    password: isObjectStorage(form.type) ? [{ required: true, message: '请输入 SecretKey', trigger: 'blur' }] : []
  }))

  function openCreate(): void {
    editingId.value = null
    Object.assign(form, emptyForm())
    testResult.value = null
    uploadResult.value = null
    dialogVisible.value = true
  }

  function openEdit(d: DataSourceVO): void {
    editingId.value = d.id
    Object.assign(form, {
      name: d.name,
      type: d.type,
      host: d.host || '',
      port: d.port ?? defaultPort(d.type),
      database: d.database || '',
      username: d.username || '',
      password: '',
      filePath: d.filePath || '',
      jdbcParams: d.jdbcParams || '',
      region: regionOf(d.jdbcParams),
      // 已存的工作表选择直接回显；不重新上传时原样回传，不会被误清为"全部"
      sheetNames: [...(d.sheetNames ?? [])],
      sheetMerge: !!d.sheetMerge,
      // 已存的额外库同样原样回显（此时还没测连接、没有候选清单，el-select 会把它们展示为可删的标签）
      extraCatalogs: [...(d.extraCatalogs ?? [])]
    })
    testResult.value = null
    uploadResult.value = null
    activeSheet.value = ''
    dialogVisible.value = true
  }

  function onTypeChange(type: string): void {
    form.port = defaultPort(type)
    testResult.value = null
    form.region = ''
    if (isLocalFileType(type)) {
      form.extraCatalogs = []   // 文件类没有“库”的概念，切过去时清掉，免得隐藏字段被一起提交
    } else {
      // 对象存储 / 数据库：没有“上传文件”与“工作表”概念，清掉隐藏字段避免误提交
      uploadResult.value = null
      form.sheetNames = []
      form.sheetMerge = false
      form.extraCatalogs = []
    }
  }

  /** 组装为后端请求体：本地文件只带 filePath；对象存储复用 host/db/username/password/filePath + region；
   *  空密码在编辑时表示保持不变。 */
  function buildPayload(): DataSourceRequest {
    const base: DataSourceRequest = {
      name: form.name.trim(),
      type: form.type,
      password: form.password.trim() || null,
      jdbcParams: form.jdbcParams.trim() || null
    }
    if (isLocalFileType(form.type)) {
      base.filePath = form.filePath.trim()
      // 全选了就不存具体名单（传 null = 全部）：日后文件被替换、sheet 增减时仍能全量导入
      const all = importableSheets.value.map((s) => s.name)
      base.sheetNames = all.length > 1 && form.sheetNames.length === all.length ? null : [...form.sheetNames]
      base.sheetMerge = form.sheetMerge
    } else if (isObjectStorage(form.type)) {
      base.host = form.host.trim()
      base.database = form.database.trim()
      base.username = form.username.trim() || null
      base.filePath = form.filePath.trim()
      // region 经 jdbcParams 透传（后端 ObjectStorageService 读 params.region，缺省 us-east-1）
      base.jdbcParams = form.region.trim() ? `region=${form.region.trim()}` : null
    } else {
      base.host = form.host.trim()
      base.port = form.port
      base.database = form.database.trim()
      base.username = form.username.trim() || null
      // 空数组传 null（= 只扫主库），与升级前的老数据同义，也避免落库一个无意义的 "[]"
      base.extraCatalogs = form.extraCatalogs.length > 0 ? [...form.extraCatalogs] : null
    }
    return base
  }

  async function onTest(): Promise<void> {
    if (isFile.value && importableSheets.value.length > 1 && form.sheetNames.length === 0) {
      ElMessage.warning('请至少勾选一张工作表')
      return
    }
    testing.value = true
    testResult.value = null
    try {
      testResult.value = await dsStore.test(buildPayload())
    } catch {
      // 拦截器已提示
    } finally {
      testing.value = false
    }
  }

  async function onSubmit(): Promise<void> {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    // 空数组在后端的含义是"导入全部"，无法表达"一张也不要"；故此处直接拦下而不是静默变成全选
    if (isFile.value && importableSheets.value.length > 1 && form.sheetNames.length === 0) {
      ElMessage.warning('请至少勾选一张工作表')
      return
    }
    await runSubmit(async () => {
      try {

        if (isEdit.value && editingId.value != null) {
          await dsStore.update(editingId.value, buildPayload())
          ElMessage.success('数据源已更新')
        } else {
          await dsStore.create(buildPayload())
          ElMessage.success('数据源已创建')
        }
        dialogVisible.value = false
      } catch {
        // 拦截器已提示
      }
    })
  }

  return {
    dialogVisible,
    editingId,
    submitting,
    testing,
    testResult,
    formRef,
    form,
    isEdit,
    isFile,
    catalogOptions,
    catalogTip,
    uploading,
    uploadResult,
    activeSheet,
    importableSheets,
    currentSheet,
    previewTableData,
    selectAllSheets,
    clearSheets,
    onToggleAll,
    skippedSheets,
    someChecked,
    allChecked,
    toggleSheet,
    savedSheetSummary,
    beforeUpload,
    customUpload,
    rules,
    openCreate,
    openEdit,
    onTypeChange,
    buildPayload,
    onTest,
    onSubmit
  }
}

export type DsFormDomain = ReturnType<typeof useDsForm>
