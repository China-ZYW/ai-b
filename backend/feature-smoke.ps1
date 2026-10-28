#requires -Version 5.1
<#
  六项缺陷修复 运行级联调脚本（问题1-6）。直接打到后端 :8080（前端 Vite 代理只是透传）。

  覆盖：
    问题1  收藏/指标 去重      —— 收藏幂等返回同 id、列表仅 1 条；指标重复保存被拒(PARAM_ERROR=40002)
    问题2  大屏公开分享        —— 开关/令牌/公开免认证访问/密码门(REQUIRED/WRONG/OK)/关闭后旧链接 NOT_FOUND
    问题3  文件本地上传        —— CSV 落盘 + 列/预览解析 + 据 filePath 建源 + 连通测试（可选 Excel）
    问题4  推荐问题            —— 依 schema 生成，永不报错，返回非空字符串数组
    问题5/6 大屏主题           —— 六套预设 key 均可持久化并回显(dark/light/tech/emerald/sunset/purple)

  仅使用「开发默认登录口令」（admin/[see-startup-log]，为 application.yml 内置默认，非真实密钥）；
  MySQL / DeepSeek 等真实密钥只在启动后端进程时经环境变量注入，不出现在本脚本。

  用法：
    powershell -ExecutionPolicy Bypass -File backend\feature-smoke.ps1 [-BaseUrl http://localhost:8080]
  可选：
    -XlsxPath <path.xlsx>   额外实测 Excel(.xlsx) 上传解析（脚本不自动生成 xlsx）
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$TenantUser = "admin",
    [string]$TenantPass = "",
    [string]$XlsxPath = ""
)

$ErrorActionPreference = "Stop"
# 让中文输出在任意控制台代码页下都正确显示（避免 GBK 控制台把 UTF-8 输出显示为乱码）。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$cond) {
    if ($cond) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name" -ForegroundColor Red }
}

# 把 HTTP 错误响应转成带 __status 的对象（不抛异常）；无响应体时回退原始文本。
function ErrFromResponse($resp) {
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

# 传输层错误（连接被拒/超时）转成 __status=0 的合成对象，令脚本在后端未起时优雅降级而非崩溃。
function ErrFromTransport($ex) {
    return [pscustomobject]@{ __status = 0; code = $null; message = $ex.Message }
}

# 统一 JSON 请求：成功返回解析后的 R（含 code/message/data）。
function Api([string]$method, [string]$path, [string]$token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $a = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = "application/json;charset=utf-8" }
    if ($null -ne $body) { $a["Body"] = ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 12))) }
    try {
        return Invoke-RestMethod @a
    } catch {
        if ($_.Exception.Response) { return ErrFromResponse $_.Exception.Response }
        return ErrFromTransport $_.Exception
    }
}

# multipart/form-data 文件上传（PowerShell 5.1 无 Invoke-RestMethod -Form，手工构造字节体）。
function Upload([string]$path, [string]$token, [string]$filePath) {
    $boundary = "----aibi" + [System.Guid]::NewGuid().ToString("N")
    $LF = "`r`n"
    $fileName = [System.IO.Path]::GetFileName($filePath)
    $fileBytes = [System.IO.File]::ReadAllBytes($filePath)
    $enc = New-Object System.Text.UTF8Encoding($false)
    $head = "--$boundary$LF" +
            "Content-Disposition: form-data; name=`"file`"; filename=`"$fileName`"$LF" +
            "Content-Type: application/octet-stream$LF$LF"
    $tail = "$LF--$boundary--$LF"
    $hb = $enc.GetBytes($head)
    $tb = $enc.GetBytes($tail)
    $body = New-Object byte[] ($hb.Length + $fileBytes.Length + $tb.Length)
    [System.Buffer]::BlockCopy($hb, 0, $body, 0, $hb.Length)
    [System.Buffer]::BlockCopy($fileBytes, 0, $body, $hb.Length, $fileBytes.Length)
    [System.Buffer]::BlockCopy($tb, 0, $body, $hb.Length + $fileBytes.Length, $tb.Length)
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    try {
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl$path" -Headers $headers `
            -ContentType "multipart/form-data; boundary=$boundary" -Body $body
    } catch {
        if ($_.Exception.Response) { return ErrFromResponse $_.Exception.Response }
        return ErrFromTransport $_.Exception
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
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "aibi-smoke-$stamp"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Write-Host "==== AI-BI 六项缺陷 运行级联调 ($BaseUrl) ====" -ForegroundColor Cyan

# ---------- 登录（租户管理员） ----------
$ta = Login $TenantUser $TenantPass
Check "租户用户登录成功且 role=USER" ($null -ne $ta -and $ta.user.role -eq "USER")
if ($null -eq $ta) {
    Write-Host "登录失败，无法继续。请确认：后端已启动(:8080)、MySQL 可达、存在 $TenantUser/$TenantPass。" -ForegroundColor Red
    Write-Host "==== 结果: PASS=$($script:pass) FAIL=$($script:fail) ====" -ForegroundColor Red
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    exit 1
}
$tat = $ta.token

# ================= 问题3：文件本地上传 =================
$csvPath = Join-Path $tmp "sales_$stamp.csv"
$csvText = @"
region,product,amount,order_date
East,Laptop,12000,2026-01-05
North,Phone,8000,2026-01-06
South,Tablet,5400,2026-01-07
East,Phone,9600,2026-01-08
West,Laptop,7300,2026-01-09
"@
[System.IO.File]::WriteAllText($csvPath, $csvText, (New-Object System.Text.UTF8Encoding($false)))

$up = Upload "/api/datasource/upload" $tat $csvPath
Check "问题3 POST /api/datasource/upload 落盘并解析CSV(filePath+列>=4+预览>=1)" `
    ($up.code -eq 0 -and $up.data.filePath -and @($up.data.columns).Count -ge 4 -and @($up.data.previewRows).Count -ge 1)
if ($up.code -eq 0) {
    Write-Host ("       type={0} size={1} cols=[{2}] previewRows={3} truncated={4}" -f `
        $up.data.type, $up.data.size, ($up.data.columns -join ','), @($up.data.previewRows).Count, $up.data.truncated) -ForegroundColor DarkGray
} else {
    Write-Host ("       upload 失败: status={0} code={1} msg={2}" -f $up.__status, $up.code, $up.message) -ForegroundColor DarkYellow
}

# 可选：Excel 上传（需用户提供 .xlsx；脚本不自动生成二进制 xlsx）
if ($XlsxPath -and (Test-Path $XlsxPath)) {
    $upx = Upload "/api/datasource/upload" $tat $XlsxPath
    Check "问题3(可选) 上传 Excel 解析(type=EXCEL, 有列)" `
        ($upx.code -eq 0 -and $upx.data.type -eq "EXCEL" -and @($upx.data.columns).Count -ge 1)
}

# 据 filePath 建文件数据源（供问题1/4 复用其 id）
$dsId = $null
$createdDs = $false
if ($up.code -eq 0 -and $up.data.filePath) {
    $dsType = if ($up.data.type) { $up.data.type } else { "CSV" }
    $ds = Api "POST" "/api/datasource" $tat @{ name = "联调上传源_$stamp"; type = $dsType; filePath = $up.data.filePath }
    if ($ds.code -eq 0) { $dsId = $ds.data.id; $createdDs = $true }
    Check "问题3 POST /api/datasource 据上传 filePath 建源(type=$dsType)" ($ds.code -eq 0 -and $null -ne $dsId)
    if ($dsId) {
        $tst = Api "POST" "/api/datasource/$dsId/test" $tat $null
        Check "问题3 POST /api/datasource/{id}/test 文件源可连通/读取(success=true)" ($tst.code -eq 0 -and $tst.data.success -eq $true)
    }
}
# 兜底：上传链路失败时退回既有数据源，保证问题1/4 仍可测
if (-not $dsId) {
    $dsList = Api "GET" "/api/datasource" $tat $null
    if ($dsList.code -eq 0 -and @($dsList.data).Count -ge 1) { $dsId = @($dsList.data)[0].id }
    if ($dsId) { Write-Host "       (上传链路未通过，问题1/4 改用既有数据源 id=$dsId)" -ForegroundColor DarkYellow }
}
if (-not $dsId) { Check "存在可用数据源 id（问题1/4 前置）" $false }

# ================= 问题1：收藏/指标 去重 =================
$favId = $null
$metricId = $null
if ($dsId) {
    $q = "联调去重问题_$stamp"
    $favBody = @{ datasourceId = $dsId; title = "联调收藏_$stamp"; question = $q; sql = "SELECT 1" }
    $f1 = Api "POST" "/api/favorite" $tat $favBody
    $f2 = Api "POST" "/api/favorite" $tat $favBody   # 同 uid+数据源+问题
    $favId = $f1.data
    Check "问题1 POST /api/favorite 首次收藏成功返回 id" ($f1.code -eq 0 -and $null -ne $favId)
    Check "问题1 重复收藏幂等（返回同一 id，不新增）" ($f2.code -eq 0 -and $f2.data -eq $favId)
    $favs = Api "GET" "/api/favorite" $tat $null
    $dupCount = @($favs.data | Where-Object { $_.question -eq $q }).Count
    Check "问题1 收藏列表中该问题仅 1 条（未重复）" ($favs.code -eq 0 -and $dupCount -eq 1)

    $metricBody = @{
        name = "联调指标_$stamp"; description = "dedup"; datasourceId = $dsId
        question = "联调指标问题_$stamp"; sql = "SELECT 1 AS total"
        chartSpec = @{ type = "BAR"; title = "联调"; xField = "k"; yFields = @("total"); seriesField = $null; reason = "smoke" }
    }
    $m1 = Api "POST" "/api/metric" $tat $metricBody
    $m2 = Api "POST" "/api/metric" $tat $metricBody   # 同 数据源+问题 → 拒绝
    $metricId = $m1.data.id
    Check "问题1 POST /api/metric 首次保存成功" ($m1.code -eq 0 -and $null -ne $metricId)
    Check "问题1 重复保存指标被拒（PARAM_ERROR=40002）" ($m2.code -eq 40002)
}

# ================= 问题4：推荐问题 =================
if ($dsId) {
    $sg = Api "GET" "/api/chat/suggestions?datasourceId=$dsId" $tat $null
    $sgArr = @($sg.data)
    $allStr = ($sgArr.Count -ge 1) -and (@($sgArr | Where-Object { $_ -is [string] -and $_.Trim().Length -gt 0 }).Count -eq $sgArr.Count)
    Check "问题4 GET /api/chat/suggestions 依 schema 返回非空问题(code=0)" ($sg.code -eq 0 -and $allStr)
    if ($sg.code -eq 0) { $sgArr | Select-Object -First 6 | ForEach-Object { Write-Host "       · $_" -ForegroundColor DarkGray } }
}

# ================= 问题2：大屏公开分享 + 问题5/6：主题 =================
$dashId = $null
$auto = $null
if ($dsId) {
    if (-not $metricId) {
        $mm = Api "POST" "/api/metric" $tat @{
            name = "联调大屏指标_$stamp"; description = "screen"; datasourceId = $dsId
            question = "联调大屏问题_$stamp"; sql = "SELECT 1 AS total"
            chartSpec = @{ type = "BAR"; title = "联调"; xField = "k"; yFields = @("total"); seriesField = $null; reason = "smoke" }
        }
        if ($mm.code -eq 0) { $metricId = $mm.data.id }
    }
    if ($metricId) {
        $auto = Api "POST" "/api/dashboard/auto-design" $tat @{
            name = "联调分享大屏_$stamp"; metricIds = @($metricId); theme = "dark"; refreshSeconds = 30; isScreen = 1
        }
        if ($auto.code -eq 0) { $dashId = $auto.data.id }
        Check "问题2 前置：auto-design 生成可分享大屏" ($null -ne $dashId)
    }
}

$shareToken = $null
if ($dashId) {
    # 开启分享（无密码、不过期）
    $sh = Api "PUT" "/api/dashboard/$dashId/share" $tat @{ enabled = $true; password = $null; expireHours = $null }
    $shareToken = $sh.data.token
    Check "问题2 PUT /{id}/share 开启分享返回 token+url" `
        ($sh.code -eq 0 -and $sh.data.enabled -eq $true -and $shareToken -and $sh.data.url)
    if ($sh.code -eq 0) { Write-Host ("       url={0}" -f $sh.data.url) -ForegroundColor DarkGray }

    # 分享状态预填
    $st = Api "GET" "/api/dashboard/$dashId/share" $tat $null
    Check "问题2 GET /{id}/share 状态(enabled=true, hasPassword=false)" `
        ($st.code -eq 0 -and $st.data.enabled -eq $true -and $st.data.hasPassword -eq $false)

    # 公开访问（免认证）→ OK + data
    if ($shareToken) {
        $pub = Api "GET" "/api/public/dashboard/$shareToken" $null $null
        Check "问题2 GET /api/public/dashboard/{token} 免认证访问 status=OK 且有 data" `
            ($pub.code -eq 0 -and "$($pub.data.status)" -eq "OK" -and $null -ne $pub.data.data)
    }

    # 设密码后：无密码→PASSWORD_REQUIRED；错密码→PASSWORD_WRONG；对密码→OK
    $pw = "Share12345"
    $sh2 = Api "PUT" "/api/dashboard/$dashId/share" $tat @{ enabled = $true; password = $pw; expireHours = $null }
    Check "问题2 设置访问密码后 hasPassword=true" ($sh2.code -eq 0 -and $sh2.data.hasPassword -eq $true)
    if ($shareToken) {
        $req = Api "GET" "/api/public/dashboard/$shareToken" $null $null
        Check "问题2 有密码未提供 → PASSWORD_REQUIRED" ($req.code -eq 0 -and "$($req.data.status)" -eq "PASSWORD_REQUIRED")
        $wrong = Api "GET" "/api/public/dashboard/${shareToken}?password=bad" $null $null
        Check "问题2 密码错误 → PASSWORD_WRONG" ($wrong.code -eq 0 -and "$($wrong.data.status)" -eq "PASSWORD_WRONG")
        $right = Api "GET" "/api/public/dashboard/${shareToken}?password=$pw" $null $null
        Check "问题2 密码正确 → OK 且有 data" ($right.code -eq 0 -and "$($right.data.status)" -eq "OK" -and $null -ne $right.data.data)
    }

    # ---------- 问题5/6：六套主题持久化 + 回显 ----------
    $layout = $auto.data.layout
    $cols = if ($auto.data.cols) { $auto.data.cols } else { 12 }
    $themeKeys = @('dark', 'light', 'tech', 'emerald', 'sunset', 'purple')
    $themeOk = $true
    foreach ($k in $themeKeys) {
        $u = Api "PUT" "/api/dashboard/$dashId" $tat @{
            name = "联调分享大屏_$stamp"; theme = $k; cols = $cols; refreshSeconds = 30; isScreen = 1; layout = $layout
        }
        if (-not ($u.code -eq 0 -and $u.data.theme -eq $k)) {
            $themeOk = $false
            Write-Host ("       主题 {0} 未正确持久化: code={1} theme={2}" -f $k, $u.code, $u.data.theme) -ForegroundColor DarkYellow
        }
    }
    Check "问题5/6 六套预设主题均可持久化并回显(dark/light/tech/emerald/sunset/purple)" $themeOk

    # 关闭分享 → 旧链接立即失效
    $off = Api "PUT" "/api/dashboard/$dashId/share" $tat @{ enabled = $false; password = $null; expireHours = $null }
    Check "问题2 关闭分享 enabled=false 且清空 token" ($off.code -eq 0 -and $off.data.enabled -eq $false -and $null -eq $off.data.token)
    if ($shareToken) {
        $gone = Api "GET" "/api/public/dashboard/$shareToken" $null $null
        Check "问题2 关闭后旧 token 访问 → NOT_FOUND" ($gone.code -eq 0 -and "$($gone.data.status)" -eq "NOT_FOUND")
    }
}

# 非法 token → NOT_FOUND（无需前置大屏）
$bad = Api "GET" "/api/public/dashboard/nonexistent_token_$stamp" $null $null
Check "问题2 不存在的 token → NOT_FOUND" ($bad.code -eq 0 -and "$($bad.data.status)" -eq "NOT_FOUND")

# ================= 清理 =================
if ($dashId) { $null = Api "DELETE" "/api/dashboard/$dashId" $tat $null }
if ($metricId) { $null = Api "DELETE" "/api/metric/$metricId" $tat $null }
if ($favId) { $null = Api "DELETE" "/api/favorite/$favId" $tat $null }
if ($createdDs -and $dsId) { $null = Api "DELETE" "/api/datasource/$dsId" $tat $null }   # 仅删本脚本建的源
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
Write-Host "---- 清理完成 ----" -ForegroundColor DarkGray

Write-Host ""
Write-Host "==== 结果: PASS=$($script:pass) FAIL=$($script:fail) ====" -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
