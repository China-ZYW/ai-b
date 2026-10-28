<script setup lang="ts">
/**
 * 数据源「新建 / 编辑」对话框（T7.3 自 DataSourceView 逐字搬移）：
 * 名称/类型 + 按类型切换的三区块（本地文件 → DsUploadSection / 对象存储 / 数据库）+ 测试结果 + footer。
 * el-form 实例经回调 :ref 写回 ctx.formRef（拆分后字符串 ref 无法再绑定注入上下文，Metric 同款解法）。
 */
import type { FormInstance } from 'element-plus'
import { useDsCtx } from './useDataSource'
import { TYPE_GROUPS, isLocalFileType, isObjectStorage } from './dsUtils'
import PermGuard from '@/components/PermGuard.vue'
import DsUploadSection from './DsUploadSection.vue'

const ctx = useDsCtx()
</script>

<template>
  <el-dialog
    v-model="ctx.dialogVisible"
    :title="ctx.isEdit ? '编辑数据源' : '新建数据源'"
    width="560px"
    align-center
  >
    <el-form :ref="(el: unknown) => (ctx.formRef = el as unknown as FormInstance | undefined)" :model="ctx.form" :rules="ctx.rules" label-width="92px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="ctx.form.name" placeholder="例如：订单主库" maxlength="64" clearable />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-select v-model="ctx.form.type" placeholder="选择数据源类型" style="width: 100%" @change="ctx.onTypeChange">
          <el-option-group v-for="g in TYPE_GROUPS" :key="g.group" :label="g.group">
            <el-option v-for="o in g.options" :key="o.value" :label="o.label" :value="o.value" />
          </el-option-group>
        </el-select>
      </el-form-item>

      <!-- 本地文件类（CSV / Excel / Parquet）：支持上传 -->
      <DsUploadSection v-if="isLocalFileType(ctx.form.type)" />

      <!-- 对象存储类（S3 / 阿里云 OSS / MinIO）：复用 host=Endpoint / database=Bucket /
           username=AccessKey / password=SecretKey / filePath=ObjectKey，手动填写 -->
      <template v-else-if="isObjectStorage(ctx.form.type)">
        <el-form-item label="Endpoint" prop="host">
          <el-input v-model="ctx.form.host" placeholder="如 https://oss-cn-hangzhou.aliyuncs.com 或 http://127.0.0.1:9000" clearable />
        </el-form-item>
        <el-form-item label="Bucket" prop="database">
          <el-input v-model="ctx.form.database" placeholder="存储桶名称" clearable />
        </el-form-item>
        <el-form-item label="AccessKey" prop="username">
          <el-input v-model="ctx.form.username" placeholder="访问密钥 ID" clearable />
        </el-form-item>
        <el-form-item label="SecretKey" prop="password">
          <el-input
            v-model="ctx.form.password"
            type="password"
            show-password
            :placeholder="ctx.isEdit ? '留空表示保持原密钥不变' : '访问密钥 Secret'"
          />
        </el-form-item>
        <el-form-item label="对象 Key" prop="filePath">
          <el-input v-model="ctx.form.filePath" placeholder="对象路径，如 data/orders.parquet" clearable />
        </el-form-item>
        <el-form-item label="区域 Region">
          <el-input v-model="ctx.form.region" placeholder="可选，如 us-east-1（缺省 us-east-1）" clearable />
        </el-form-item>
        <div class="xd-tip">
          兼容 S3 协议的对象存储：文件按 Endpoint/Bucket/Key 下载到本地临时库后以标准 SQL 问数；
          MinIO 等自建服务用 IP:端口 形式的 Endpoint，并开启 path-style 寻址。
        </div>
      </template>

      <!-- 数据库类 -->
      <template v-else>
        <el-form-item label="主机" prop="host">
          <el-input v-model="ctx.form.host" placeholder="例如 127.0.0.1" clearable />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="ctx.form.port" :min="0" :max="65535" controls-position="right" style="width: 160px" />
        </el-form-item>
        <el-form-item label="数据库" prop="database">
          <el-input v-model="ctx.form.database" placeholder="数据库名" clearable />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="ctx.form.username" placeholder="数据库用户名" clearable />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="ctx.form.password"
            type="password"
            show-password
            :placeholder="ctx.isEdit ? '留空表示保持原密码不变' : '数据库密码'"
          />
        </el-form-item>
        <el-form-item label="跨库联合">
          <el-select
            v-model="ctx.form.extraCatalogs"
            multiple
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="留空 = 只分析主库"
            style="width: 100%"
          >
            <el-option v-for="c in ctx.catalogOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="xd-tip">{{ ctx.catalogTip }}</div>
        </el-form-item>
        <el-form-item label="JDBC 参数">
          <el-input v-model="ctx.form.jdbcParams" placeholder="可选，k=v&k=v，例如 useSSL=false&serverTimezone=Asia/Shanghai" clearable />
        </el-form-item>
      </template>

      <!-- 测试结果 -->
      <el-alert
        v-if="ctx.testResult"
        :type="ctx.testResult.success ? 'success' : 'error'"
        :closable="false"
        show-icon
        :title="ctx.testResult.success ? `连接成功（${ctx.testResult.elapsedMs} ms）` : ctx.testResult.message"
      />
    </el-form>

    <template #footer>
      <PermGuard code="datasource:config:test" mode="disable" tip="当前角色无连通性测试权限">
        <el-button :loading="ctx.testing" @click="ctx.onTest">
          <el-icon><Connection /></el-icon> 测试连接
        </el-button>
      </PermGuard>
      <el-button @click="ctx.dialogVisible = false">取消</el-button>
      <PermGuard code="datasource:config:write" mode="disable" tip="当前角色无数据源管理权限">
        <el-button type="primary" :loading="ctx.submitting" @click="ctx.onSubmit">保存</el-button>
      </PermGuard>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 组内说明文字（原与 up-merge-tip/up-saved-tip 同一条规则，拆分后各自自持）；
   在下拉框/区块正下方需要一点间距（up-* 是 flex 行内文本，不跟着加 margin） */
.xd-tip {
  font-size: 11.5px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
</style>
