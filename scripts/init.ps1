#Requires -Version 5.1
<#
.SYNOPSIS
  aibi full-stack local bootstrap (Windows / PowerShell).
  Takes a clean checkout to a running system: prereq check -> local.env -> backend build
  -> frontend deps -> (optional) demo DB -> start backend + frontend.

.DESCRIPTION
  Non-destructive: never overwrites an existing backend/local.env, never drops DBs,
  never prints secret values. Idempotent: safe to re-run; already-satisfied steps are skipped.
  Tool resolution honours overrides (all optional):
    AIBI_JAVA_HOME  -> JDK 17+ home used for Maven build
    AIBI_MVN        -> full path to mvn(.cmd) if not on PATH
    AIBI_NODE       -> full path to node(.exe) if not on PATH
  Flags (non-interactive / CI):
    -NoPrompt            skip all confirmations, use safe defaults (build+deps yes, demo-db no, start yes)
    -SkipBackendBuild    do not run mvn package
    -SkipFrontendInstall do not run npm install
    -SkipDemoDb          do not offer importing poc sample schema
    -NoStart             prepare only, do not launch backend/frontend
    -BackendPort <n>     backend port (default 8080)
    -FrontendPort <n>    frontend port (default 5173)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\init.ps1
#>
[CmdletBinding()]
param(
    [switch]$NoPrompt,
    [switch]$SkipBackendBuild,
    [switch]$SkipFrontendInstall,
    [switch]$SkipDemoDb,
    [switch]$NoStart,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $RepoRoot 'backend'
$FrontendDir = Join-Path $RepoRoot 'frontend'
$LocalEnv = Join-Path $BackendDir 'local.env'
$LocalEnvExample = Join-Path $BackendDir 'local.env.example'
$SampleSchema = Join-Path $RepoRoot 'poc\nl2sql-poc\sql\sample_schema.sql'

function Write-Head($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }
function Ask($q) {
    if ($NoPrompt) { return $true }
    $a = Read-Host "$q [y/N]"
    return ($a -match '^[Yy]')
}

Write-Host "aibi bootstrap :: repo root = $RepoRoot"

# ---------------------------------------------------------------------------
# 1) Prerequisites
# ---------------------------------------------------------------------------
Write-Head "[1/6] Prerequisite check"

function Resolve-Java {
    if ($env:AIBI_JAVA_HOME) {
        $p = Join-Path $env:AIBI_JAVA_HOME 'bin\java.exe'
        if (Test-Path $p) { return $p }
    }
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    return $null
}
function Java-Major($javaBin) {
    # java -version writes to stderr; under $ErrorActionPreference='Stop' a 2>&1 merge
    # turns it into a terminating NativeCommandError. Lower the preference for the probe.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $raw = (& $javaBin -version 2>&1 | Out-String) } finally { $ErrorActionPreference = $prev }
    if ($raw -match 'version\s+"1\.(\d+)') { return [int]$matches[1] }   # legacy 1.8 -> 8
    if ($raw -match 'version\s+"?(\d+)')  { return [int]$matches[1] }    # modern 17/21
    return 0
}

$script:Ok = $true
$javaBin = Resolve-Java
if ($javaBin) {
    $jm = Java-Major $javaBin
    if ($jm -ge 17) { Write-Host "  [ok]  Java $jm ($javaBin)" }
    else { Write-Host "  [FAIL] Java $jm < 17 (need JDK 17+): $javaBin" -ForegroundColor Red; $script:Ok = $false }
} else { Write-Host "  [FAIL] java not found (set AIBI_JAVA_HOME or add JDK 17+ to PATH)" -ForegroundColor Red; $script:Ok = $false }

$mvnBin = if ($env:AIBI_MVN -and (Test-Path $env:AIBI_MVN)) { $env:AIBI_MVN } else { (Get-Command mvn -ErrorAction SilentlyContinue).Source }
if ($mvnBin) { Write-Host "  [ok]  Maven $mvnBin" } else { Write-Host "  [FAIL] mvn not found (add Maven to PATH or set AIBI_MVN)" -ForegroundColor Red; $script:Ok = $false }

$nodeBin = if ($env:AIBI_NODE -and (Test-Path $env:AIBI_NODE)) { $env:AIBI_NODE } else { (Get-Command node -ErrorAction SilentlyContinue).Source }
if ($nodeBin) { $nv = & $nodeBin -v; Write-Host "  [ok]  Node $nv ($nodeBin)" } else { Write-Host "  [FAIL] node not found (add Node to PATH or set AIBI_NODE)" -ForegroundColor Red; $script:Ok = $false }

$npmBin = (Get-Command npm -ErrorAction SilentlyContinue).Source
if (-not $npmBin -and $nodeBin) { $npmBin = Join-Path (Split-Path $nodeBin) 'npm.cmd' }
if ($npmBin -and (Test-Path $npmBin)) { Write-Host "  [ok]  npm $npmBin" } else { Write-Host "  [WARN] npm not found" -ForegroundColor Yellow }

$mysqlBin = (Get-Command mysql -ErrorAction SilentlyContinue).Source
if ($mysqlBin) { Write-Host "  [ok]  mysql CLI $mysqlBin (for optional demo DB import)" } else { Write-Host "  [info] mysql CLI absent (demo DB import will be skipped)" }

if (-not $script:Ok) {
    Write-Host ""; Write-Host "Prerequisites missing. Fix the [FAIL] items above and re-run." -ForegroundColor Red
    exit 1
}

# If JAVA_HOME override present, export it for the Maven build so --release works.
if ($env:AIBI_JAVA_HOME) { $env:JAVA_HOME = $env:AIBI_JAVA_HOME }

# ---------------------------------------------------------------------------
# 2) backend/local.env (secrets) -- copy from template, never overwrite
# ---------------------------------------------------------------------------
Write-Head "[2/6] backend/local.env"
$RequiredKeys = @('AIBI_DB_URL','AIBI_DB_USERNAME','AIBI_DB_PASSWORD','JWT_SECRET','AIBI_CIPHER_KEY')

function Read-EnvFile($path) {
    $map = @{}
    Get-Content $path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $kv = $line -split '=', 2
            $map[$kv[0].Trim()] = $kv[1].Trim().Trim('"').Trim("'")
        }
    }
    return $map
}

if (-not (Test-Path $LocalEnv)) {
    Copy-Item $LocalEnvExample $LocalEnv
    Write-Host "  Copied local.env.example -> local.env" -ForegroundColor Yellow
    Write-Host "  EDIT IT NOW and fill real values (all gitignored, never committed):" -ForegroundColor Yellow
    Write-Host ("    " + $LocalEnv)
    Write-Host "  Required: " ($RequiredKeys -join ', ')
    if (-not $NoPrompt) { Read-Host "  Press Enter after you saved the file (Ctrl+C to stop and fill later)" }
}

$envMap = Read-EnvFile $LocalEnv
$missing = @()
foreach ($k in $RequiredKeys) {
    $v = $envMap[$k]
    if (-not $v -or $v -match '<' ) { $missing += $k }
}
if ($envMap['JWT_SECRET'] -and $envMap['JWT_SECRET'].Length -lt 32 -and $envMap['JWT_SECRET'] -notmatch '<') {
    $missing += 'JWT_SECRET(<32 bytes)'
}
if ($missing.Count -gt 0) {
    Write-Host ("  [WARN] Unfilled/invalid keys: " + ($missing -join ', ')) -ForegroundColor Yellow
    Write-Host "         Backend will fail-fast on start until these are set (see backend/local.env.example)." -ForegroundColor Yellow
} else {
    Write-Host "  [ok]  local.env has all required keys"
}

# ---------------------------------------------------------------------------
# 3) Build backend -> target/aibi.jar
# ---------------------------------------------------------------------------
Write-Head "[3/6] Build backend (mvn -DskipTests package)"
if ($SkipBackendBuild) {
    Write-Host "  skipped (-SkipBackendBuild)"
} else {
    Push-Location $BackendDir
    try {
        & $mvnBin -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "mvn package failed (exit $LASTEXITCODE)" }
        $jar = Join-Path $BackendDir 'target\aibi.jar'
        if (-not (Test-Path $jar)) { throw "expected artifact not found: $jar" }
        Write-Host "  [ok]  built $jar"
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# 4) Frontend dependencies
# ---------------------------------------------------------------------------
Write-Head "[4/6] Frontend deps (npm install)"
if ($SkipFrontendInstall) {
    Write-Host "  skipped (-SkipFrontendInstall)"
} else {
    $nm = Join-Path $FrontendDir 'node_modules'
    if (Test-Path $nm) {
        Write-Host "  [ok]  node_modules present, skip install (delete it to force reinstall)"
    } else {
        Push-Location $FrontendDir
        try { & $npmBin install; if ($LASTEXITCODE -ne 0) { throw "npm install failed" } } finally { Pop-Location }
        Write-Host "  [ok]  npm install done"
    }
}

# ---------------------------------------------------------------------------
# 5) Optional: import demo business DB (aibi_demo) from poc sample schema
# ---------------------------------------------------------------------------
Write-Head "[5/6] Demo business DB (optional)"
if ($SkipDemoDb) {
    Write-Host "  skipped (-SkipDemoDb)"
} elseif (-not $mysqlBin -or -not (Test-Path $SampleSchema)) {
    Write-Host "  skipped (mysql CLI or sample_schema.sql absent)"
} else {
    Write-Host "  The seeded demo datasource (DataSeeder) points to aibi_demo by default."
    Write-Host "  Import local sample schema? (creates database aibi_demo, drops its tables) idempotent."
    Write-Host "  WARNING: import targets a LOCAL MySQL. Do NOT point it at the shared remote demo instance."
    if (Ask "  Import $SampleSchema (creates DB aibi_demo)?") {
        $myHost = if ($NoPrompt) { '127.0.0.1' } else { $h = Read-Host '  host'; if ($h) { $h } else { '127.0.0.1' } }
        $myPort = if ($NoPrompt) { '3306' } else { $p = Read-Host '  port'; if ($p) { $p } else { '3306' } }
        $myUser = if ($NoPrompt) { 'root' } else { $u = Read-Host '  user'; if ($u) { $u } else { 'root' } }
        if ($env:MYSQL_PWD) { $myPw = $env:MYSQL_PWD }
        elseif ($NoPrompt) { Write-Host "  [WARN] no MYSQL_PWD in env, cannot authenticate non-interactively" -ForegroundColor Yellow; $myPw = $null }
        else { $sec = Read-Host '  password' -AsSecureString; $myPw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)) }
        if ($myPw) {
            $env:MYSQL_PWD = $myPw
            try {
                Get-Content $SampleSchema -Raw -Encoding UTF8 | & $mysqlBin -h $myHost -P $myPort -u $myUser
                if ($LASTEXITCODE -eq 0) { Write-Host "  [ok]  demo schema imported into aibi_demo" } else { Write-Host "  [WARN] import failed (non-fatal)" -ForegroundColor Yellow }
            } finally { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue; $myPw = $null }
        } else { Write-Host "  skipped (no password)" }
    } else {
        Write-Host "  skipped by user"
    }
}

# ---------------------------------------------------------------------------
# 6) Start services
# ---------------------------------------------------------------------------
Write-Head "[6/6] Start backend + frontend"
if ($NoStart) {
    Write-Host "  skipped (-NoStart). Start later with scripts\init.ps1 -NoPrompt or run-serve.ps1 / npm run dev."
    Write-Host ""; Write-Host "Bootstrap prepared. All steps green = ready to run." -ForegroundColor Green
    exit 0
}
if (-not (Ask "  Start backend (:$BackendPort) and frontend (:$FrontendPort) now?")) {
    Write-Host "  Not starting. Re-run later or use start-dev.bat."
    exit 0
}

$jar = Join-Path $BackendDir 'target\aibi.jar'
$runLog = Join-Path $BackendDir 'run.log'
$runErr = Join-Path $BackendDir 'run.err.log'
Write-Host "  launching backend (auto-loads local.env, log -> backend\run.log)"
Start-Process -FilePath $javaBin -ArgumentList '-jar', $jar -WorkingDirectory $BackendDir `
    -RedirectStandardOutput $runLog -RedirectStandardError $runErr -WindowStyle Hidden

# health poll
Write-Host "  waiting for backend health ..."
$ready = $false
$healthUrl = "http://127.0.0.1:$BackendPort/actuator/health"
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 3
    try {
        $r = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($r.status -eq 'UP') { $ready = $true; break }
    } catch { }
    Write-Host "    ...$($i*3)s"
}
if ($ready) { Write-Host "  [ok]  backend UP at $healthUrl" } else { Write-Host "  [WARN] backend not UP within 180s; check backend\run.log" -ForegroundColor Yellow }

# frontend dev server (force IPv4 backend to avoid Windows localhost->IPv6 empty 500)
$env:VITE_BACKEND = "http://127.0.0.1:$BackendPort"
Write-Host "  launching frontend (VITE_BACKEND=$env:VITE_BACKEND)"
Push-Location $FrontendDir
try {
    Start-Process -FilePath $npmBin -ArgumentList 'run','dev','--','--port',"$FrontendPort" -WorkingDirectory $FrontendDir -WindowStyle Hidden
} finally { Pop-Location }

Write-Host ""
Write-Host "Done. Open:" -ForegroundColor Green
Write-Host "  Frontend: http://localhost:$FrontendPort/"
Write-Host "  Backend : http://localhost:$BackendPort/actuator/health"
Write-Host "  Login   : superadmin / admin — 口令取后端首次启动日志 WARN 行中的随机值；或在 backend/local.env 写入 AIBI_SUPERADMIN_PASSWORD / AIBI_DEMO_ADMIN_PASSWORD 后重启"
Write-Host "Backend log: $runLog"
