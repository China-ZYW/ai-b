#requires -Version 5.1
<#
  后端接口联调冒烟脚本（Feature 1 超管后台 + Feature 2 指标/驾驶舱）。
  直接打到后端 :8080（前端 Vite 代理只是透传）。

  仅使用首次播种产生的开发口令（开源仓不预置硬编码默认值）：
  superadmin 优先取 -SuperPass / env:AIBI_SUPERADMIN_PASSWORD；admin 优先取 -TenantPass / env:AIBI_DEMO_ADMIN_PASSWORD；
  两者都未传入时脚本启动即抛错，提醒从后端启动日志中取 WARN 行随机口令。
  MySQL / DeepSeek 等真实密钥只在启动进程时经环境变量注入，不出现在本脚本。

  用法： powershell -ExecutionPolicy Bypass -File backend\api-smoke.ps1 [-BaseUrl http://localhost:8080] [-SuperPass <pwd>] [-TenantPass <pwd>]
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SuperUser = "superadmin",
    [string]$SuperPass = $env:AIBI_SUPERADMIN_PASSWORD,
    [string]$TenantUser = "admin",
    [string]$TenantPass = $env:AIBI_DEMO_ADMIN_PASSWORD
)

if (-not $SuperPass) {
    throw "缺少超管口令：请传入 -SuperPass 或设置环境变量 AIBI_SUPERADMIN_PASSWORD（开源仓不预置默认口令，首次启动日志 WARN 行可取随机口令）。"
}
if (-not $TenantPass) {
    throw "缺少演示管理员口令：请传入 -TenantPass 或设置环境变量 AIBI_DEMO_ADMIN_PASSWORD（同上）。"
}

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$cond) {
    if ($cond) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name" -ForegroundColor Red }
}

# 统一请求：成功返回解析后的 R（含 code/message/data）；4xx/5xx 返回带 __status 的对象，不抛异常。
function Api([string]$method, [string]$path, [string]$token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $a = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = "application/json;charset=utf-8" }
    if ($null -ne $body) { $a["Body"] = ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 12))) }
    try {
        return Invoke-RestMethod @a
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $txt = $reader.ReadToEnd()
            $parsed = $null
            try { $parsed = $txt | ConvertFrom-Json } catch {}
            return [pscustomobject]@{
                __status = $status
                code     = $(if ($parsed) { $parsed.code } else { $null })
                message  = $(if ($parsed) { $parsed.message } else { $txt })
            }
        }
        throw
    }
}

function Login([string]$u, [string]$p) {
    # 远端 MySQL 空闲后首个请求可能因陈旧连接超时(5xx)，重试数次预热连接池；明确业务失败(密码错/门禁)不重试。
    for ($try = 1; $try -le 4; $try++) {
        $r = Api "POST" "/api/auth/login" $null @{ username = $u; password = $p }
        if ($r.code -eq 0) { return $r.data }
        if ($r.__status -ge 500) { Start-Sleep -Seconds 3; continue }
        return $null
    }
    return $null
}

$stamp = Get-Date -Format "MMddHHmmss"
Write-Host "==== AI-BI 接口联调冒烟 ($BaseUrl) ====" -ForegroundColor Cyan

# ---------- Feature 1：超管登录与鉴权 ----------
$sa = Login $SuperUser $SuperPass
Check "超管登录成功且 role=SUPER_ADMIN" ($null -ne $sa -and $sa.user.role -eq "SUPER_ADMIN")
$sat = $sa.token

$ov = Api "GET" "/api/admin/overview" $sat $null
Check "GET /api/admin/overview 汇总(code=0, tenantCount>=1)" ($ov.code -eq 0 -and $ov.data.tenantCount -ge 1)

# ---------- 租户 CRUD + 配额/期限 ----------
$tenants = Api "GET" "/api/admin/tenants" $sat $null
Check "GET /api/admin/tenants 列表" ($tenants.code -eq 0)

$newTenant = Api "POST" "/api/admin/tenants" $sat @{
    name = "冒烟测试租户_$stamp"; plan = "FREE"; status = 1
    dailyLimit = 5; totalLimit = 100; expireAt = $null
}
$tid = $newTenant.data.id
Check "POST /api/admin/tenants 创建(FREE→dailyLimit=5)" ($newTenant.code -eq 0 -and $newTenant.data.dailyLimit -eq 5)

$upd = Api "PUT" "/api/admin/tenants/$tid" $sat @{
    name = "冒烟测试租户_改_$stamp"; plan = "PRO"; status = 1
    dailyLimit = 50; totalLimit = -1; expireAt = "2030-12-31T23:59:59"
}
Check "PUT /api/admin/tenants/{id} 改套餐/配额/期限" ($upd.code -eq 0 -and $upd.data.plan -eq "PRO" -and $upd.data.dailyLimit -eq 50 -and $null -ne $upd.data.expireAt)

# ---------- 账号 CRUD + 重置密码 + 启停 ----------
$uname = "smoke_u_$stamp"
$newUser = Api "POST" "/api/admin/users" $sat @{
    tenantId = $tid; username = $uname; password = "Smoke@123456"; nickname = "冒烟号"; role = "USER"; status = 1
}
$uid = $newUser.data.id
Check "POST /api/admin/users 建号(响应不含密码)" ($newUser.code -eq 0 -and $null -eq $newUser.data.passwordHash)

$users = Api "GET" "/api/admin/users?tenantId=$tid" $sat $null
Check "GET /api/admin/users?tenantId 列表" ($users.code -eq 0 -and @($users.data | Where-Object { $_.id -eq $uid }).Count -ge 1)

$rp = Api "POST" "/api/admin/users/$uid/reset-password" $sat @{ password = "Smoke@654321" }
Check "POST /api/admin/users/{id}/reset-password" ($rp.code -eq 0)

# ---------- 登录门禁：租户停用 → TENANT_DISABLED(40305) ----------
$null = Api "PUT" "/api/admin/tenants/$tid/status?status=0" $sat $null
$loginDisabled = Api "POST" "/api/auth/login" $null @{ username = $uname; password = "Smoke@654321" }
Check "租户停用后登录被拒(TENANT_DISABLED=40305)" ($loginDisabled.code -eq 40305)
$null = Api "PUT" "/api/admin/tenants/$tid/status?status=1" $sat $null
$loginOk = Api "POST" "/api/auth/login" $null @{ username = $uname; password = "Smoke@654321" }
Check "租户恢复后重置密码可登录" ($loginOk.code -eq 0)

# ---------- 越权：普通用户访问 /api/admin/** → 403 ----------
$ta = Login $TenantUser $TenantPass
Check "租户用户登录成功且 role=USER" ($null -ne $ta -and $ta.user.role -eq "USER")
$tat = $ta.token
$forbid = Api "GET" "/api/admin/tenants" $tat $null
Check "普通用户访问 /api/admin/tenants 被拒(HTTP 403)" ($forbid.__status -eq 403)

# ---------- 大模型配置：全局默认 + 租户覆盖 + 测试 ----------
$llms = Api "GET" "/api/admin/llm-configs" $sat $null
Check "GET /api/admin/llm-configs 列表(含播种的全局默认, hasApiKey)" ($llms.code -eq 0 -and @($llms.data).Count -ge 1 -and @($llms.data | Where-Object { $_.hasApiKey }).Count -ge 1)

$globalDeepseek = $llms.data | Where-Object { $_.provider -eq "DEEPSEEK" -and $null -eq $_.tenantId } | Select-Object -First 1
if ($globalDeepseek) {
    $t = Api "POST" "/api/admin/llm-configs/$($globalDeepseek.id)/test" $sat $null
    Check "POST /api/admin/llm-configs/{id}/test 真实连通(DeepSeek)" ($t.code -eq 0)
} else {
    Check "POST /api/admin/llm-configs/{id}/test 真实连通(DeepSeek)" $false
}

$tenantLlm = Api "POST" "/api/admin/llm-configs" $sat @{
    tenantId = $tid; provider = "OPENAI_COMPATIBLE"; name = "冒烟租户配置_$stamp"
    baseUrl = "https://api.deepseek.com"; apiKey = "sk-invalid-smoke"; model = "deepseek-chat"
    timeoutSeconds = 30; priority = 50; enabled = 1
}
Check "POST /api/admin/llm-configs 建租户覆盖(hasApiKey=true, 不回明文)" ($tenantLlm.code -eq 0 -and $tenantLlm.data.hasApiKey -eq $true -and $null -eq $tenantLlm.data.apiKey)
$tlid = $tenantLlm.data.id
$dis = Api "PUT" "/api/admin/llm-configs/$tlid/enabled?enabled=0" $sat $null
Check "PUT /api/admin/llm-configs/{id}/enabled 停用" ($dis.code -eq 0 -and $dis.data.enabled -eq 0)

# ---------- Feature 2：模板 / 指标 / 驾驶舱 ----------
$tpls = Api "GET" "/api/dashboard-template" $tat $null
Check "GET /api/dashboard-template 列出 4 套内置模板" ($tpls.code -eq 0 -and @($tpls.data).Count -ge 4)
$tplCode = ($tpls.data | Select-Object -First 1).code

# 保存 3 个指标（不同图表类型），SQL 用确定性 SELECT 1，验证刷新链路（不消耗 LLM/配额）
$specs = @(
    @{ type = "BAR"; title = "冒烟柱状"; xField = "k"; yFields = @("total"); seriesField = $null; reason = "smoke" },
    @{ type = "LINE"; title = "冒烟折线"; xField = "k"; yFields = @("total"); seriesField = $null; reason = "smoke" },
    @{ type = "PIE"; title = "冒烟饼图"; xField = "k"; yFields = @("total"); seriesField = $null; reason = "smoke" }
)
$metricIds = @()
foreach ($sp in $specs) {
    $m = Api "POST" "/api/metric" $tat @{
        name = "冒烟指标_$($sp.type)_$stamp"; description = "smoke"; datasourceId = 1
        question = "冒烟测试"; sql = "SELECT 1 AS total"; chartSpec = $sp
    }
    if ($m.code -eq 0) { $metricIds += $m.data.id }
}
Check "POST /api/metric 保存 3 个指标" ($metricIds.Count -eq 3)

$mid = $metricIds[0]
$refresh = Api "POST" "/api/metric/$mid/refresh" $tat $null
Check "POST /api/metric/{id}/refresh 重跑SQL返回结果(rowCount>=1)" ($refresh.code -eq 0 -and $refresh.data.result.rowCount -ge 1)

$mList = Api "GET" "/api/metric" $tat $null
Check "GET /api/metric 列表(租户隔离, chartSpec 为对象)" ($mList.code -eq 0 -and @($mList.data).Count -ge 3 -and $null -ne (@($mList.data)[0].chartSpec.type))

# 一键自动布局
$auto = Api "POST" "/api/dashboard/auto-design" $tat @{
    name = "冒烟自动驾驶舱_$stamp"; metricIds = $metricIds; theme = "dark"; refreshSeconds = 30; isScreen = 1
}
$autoId = $auto.data.id
Check "POST /api/dashboard/auto-design 生成布局(3 widget)" ($auto.code -eq 0 -and @($auto.data.layout).Count -eq 3)

# 从模板实例化（把 3 个指标绑定到模板前 3 个槽位）
$fromTpl = Api "POST" "/api/dashboard/from-template" $tat @{
    name = "冒烟模板驾驶舱_$stamp"; templateCode = $tplCode; metricIds = $metricIds; theme = "dark"; refreshSeconds = 20; isScreen = 1
}
$tplDashId = $fromTpl.data.id
$boundCount = @($fromTpl.data.layout | Where-Object { $null -ne $_.metricId }).Count
Check "POST /api/dashboard/from-template 绑定指标到槽位" ($fromTpl.code -eq 0 -and $boundCount -ge 3 -and $fromTpl.data.templateCode -eq $tplCode)

# 大屏批量刷新
$data = Api "GET" "/api/dashboard/$autoId/data" $tat $null
$okWidgets = @($data.data.widgets | Where-Object { $null -ne $_.result }).Count
Check "GET /api/dashboard/{id}/data 批量刷新(全部 widget 有数据)" ($data.code -eq 0 -and $okWidgets -eq 3)

# 保存布局（更新）
$layout = $auto.data.layout
$updDash = Api "PUT" "/api/dashboard/$autoId" $tat @{
    name = "冒烟自动驾驶舱_改_$stamp"; theme = "light"; cols = 12; refreshSeconds = 15; isScreen = 0; layout = $layout
}
Check "PUT /api/dashboard/{id} 保存布局/主题/刷新频率" ($updDash.code -eq 0 -and $updDash.data.theme -eq "light")

# ---------- 配额状态新字段 ----------
$quota = Api "GET" "/api/quota" $tat $null
Check "GET /api/quota 含每日/累计配额字段" ($quota.code -eq 0 -and $null -ne $quota.data.PSObject.Properties["totalLimit"] -and $null -ne $quota.data.PSObject.Properties["totalUsed"])

# ---------- 回归：数据源 / 历史（原 18 项抽样） ----------
$ds = Api "GET" "/api/datasource" $tat $null
Check "回归 GET /api/datasource 列表" ($ds.code -eq 0 -and @($ds.data).Count -ge 1)
$hist = Api "GET" "/api/history?limit=5&offset=0" $tat $null
Check "回归 GET /api/history 列表" ($hist.code -eq 0)
$noauth = Api "GET" "/api/metric" $null $null
Check "回归 无 token 访问 /api/metric → HTTP 401" ($noauth.__status -eq 401)

# ---------- 清理 ----------
$null = Api "DELETE" "/api/dashboard/$autoId" $tat $null
$null = Api "DELETE" "/api/dashboard/$tplDashId" $tat $null
foreach ($mid2 in $metricIds) { $null = Api "DELETE" "/api/metric/$mid2" $tat $null }
$null = Api "DELETE" "/api/admin/llm-configs/$tlid" $sat $null
$null = Api "DELETE" "/api/admin/tenants/$tid" $sat $null   # 级联删除该租户下账号
Write-Host "---- 清理完成 ----" -ForegroundColor DarkGray

Write-Host ""
Write-Host "==== 结果: PASS=$($script:pass) FAIL=$($script:fail) ====" -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
