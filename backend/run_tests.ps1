# T3 测试运行脚本：指定测试类跑 mvn test（本机 maven-fresh + maven_res 仓库）
param([string]$Tests)
$env:JAVA_HOME = 'D:\soft\jdk17'
& D:\soft\maven-fresh\apache-maven-3.9.12\bin\mvn.cmd -q `
  "-Dmaven.repo.local=D:\soft\apache-maven-3.6.3-bin\maven_res" `
  "-Dtest=$Tests" "-DfailIfNoTests=false" `
  -f D:\project\qoder\b-ai\b-ai\backend\pom.xml test 2>&1 | Select-Object -Last 30
"EXIT=$LASTEXITCODE"
