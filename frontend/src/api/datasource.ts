import { del, get, post, put } from './request'
import type {
  DataSourceRequest,
  DataSourceVO,
  FileUploadResult,
  TableMeta,
  TestConnectionResult
} from '@/types'

/** 数据源管理接口（/api/datasource）。凭证只写不读，响应仅返回 hasPassword。 */
export const datasourceApi = {
  list: () => get<DataSourceVO[]>('/datasource'),
  create: (data: DataSourceRequest) => post<DataSourceVO>('/datasource', data),
  update: (id: number, data: DataSourceRequest) => put<DataSourceVO>(`/datasource/${id}`, data),
  remove: (id: number) => del<void>(`/datasource/${id}`),
  /** 测试尚未保存的配置（新建向导里"测试连接"）。 */
  test: (data: DataSourceRequest) => post<TestConnectionResult>('/datasource/test', data),
  /** 测试已保存的数据源。 */
  testById: (id: number) => post<TestConnectionResult>(`/datasource/${id}/test`),
  /** 查看表结构（供展示与常见问题推荐）。 */
  schema: (id: number) => get<TableMeta[]>(`/datasource/${id}/schema`),
  /**
   * 立即同步表结构（`/datasource/{id}/sync`）：后端失效 schema 缓存 → 重新扫描 → 回写 last_schema_sync_at。
   * 表新增/改名/删列后用于让问数立刻看到最新结构，不必等定时重扫。
   */
  sync: (id: number) => post<TableMeta[]>(`/datasource/${id}/sync`),
  /** 本地上传文件（问题3）：返回落盘 filePath + 预览，供回填表单后再保存入库。 */
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return post<FileUploadResult>('/datasource/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
