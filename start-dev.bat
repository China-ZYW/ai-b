@echo off
REM ---------------------------------------------------------------------------
REM aibi local dev launcher: backend (Spring Boot :8080) + frontend (Vite :5173)
REM
REM Notes (environment-specific, verified on this machine):
REM   - JAVA_HOME must point to JDK 17+. The system default JAVA_HOME is JDK 8,
REM     which makes maven-compiler-plugin fail with "invalid flag: --release".
REM   - SERVER_PORT is set explicitly because the host may inject a different
REM     SERVER_PORT into the environment, overriding server.port in yml.
REM ---------------------------------------------------------------------------

set JAVA_HOME=D:\soft\jdk17
set SERVER_PORT=8080

set MVN=C:\Users\YeWai.Zhang\.m2\wrapper\dists\apache-maven-3.9.12-bin\7v3camr5asa67op289n7tbbbh2\apache-maven-3.9.12\bin\mvn.cmd
set NODE=C:\Users\YeWai.Zhang\.workbuddy\binaries\node\versions\22.22.2-3\node.exe

set BACKEND_DIR=D:\project\qoder\b-ai\b-ai\backend
set FRONTEND_DIR=D:\project\qoder\b-ai\b-ai\frontend

echo Starting backend on :8080 ...
start "aibi-backend" cmd /k ""%MVN%" -f "%BACKEND_DIR%\pom.xml" -DskipTests spring-boot:run"

echo Starting frontend on :5173 ...
start "aibi-frontend" cmd /k ""%NODE%" "%FRONTEND_DIR%\node_modules\vite\bin\vite.js" --host 0.0.0.0 --port 5173"

echo.
echo Backend : http://localhost:8080/actuator/health
echo Frontend: http://localhost:5173/
timeout /t 3 >nul
