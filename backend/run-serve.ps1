#requires -Version 5.1
<#
  以 detached 进程启动后端，并把 stdout/stderr 重定向到 run.log / run.err.log，便于运行级诊断。
  本脚本【不含任何密钥】：数据库/DeepSeek 等凭证由调用方经环境变量注入，Start-Process 子进程继承之。
  AIBI_CIPHER_KEY / JWT_SECRET 未设置时沿用 application.yml 默认值（dev；已验证默认 cipher-key 可解密现有数据源凭证）。
  用法：先设 $env:SPRING_DATASOURCE_URL/USERNAME/PASSWORD、$env:DEEPSEEK_API_KEY，再 & run-serve.ps1
#>
$log = Join-Path $PSScriptRoot "run.log"
$err = Join-Path $PSScriptRoot "run.err.log"
Start-Process -FilePath "D:\soft\jdk21\bin\java.exe" `
    -ArgumentList "-jar", "target\aibi.jar" `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $log `
    -RedirectStandardError $err `
    -WindowStyle Hidden
Write-Output "launched; log=$log"
