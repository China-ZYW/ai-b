#requires -Version 5.1
<#
  以 detached 进程启动后端 jar，并把 stdout/stderr 重定向到 run.log / run.err.log，便于运行级诊断。
  本脚本【不含任何密钥】：数据库 / LLM / JWT / AES 等凭据由调用方经环境变量注入，Start-Process 子进程继承之。
  用法：先设 $env:AIBI_DB_URL / USERNAME / PASSWORD / JWT_SECRET / AIBI_CIPHER_KEY / DEEPSEEK_API_KEY，
        再 & .\run-serve.ps1

  Java 二进制选择优先级：
    1. $env:AIBI_JAVA_CMD —— 显式指定 java.exe 绝对路径
    2. $env:JAVA_HOME\bin\java.exe —— 走标准 JDK_HOME 约定
    3. PATH 中的 java —— 兜底
#>
$log = Join-Path $PSScriptRoot "run.log"
$err = Join-Path $PSScriptRoot "run.err.log"

$java = if ($env:AIBI_JAVA_CMD) {
    $env:AIBI_JAVA_CMD
} elseif ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} else {
    'java'
}

Start-Process -FilePath $java `
    -ArgumentList "-jar", "target\aibi.jar" `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $log `
    -RedirectStandardError $err `
    -WindowStyle Hidden
Write-Output "launched via $java; log=$log"
