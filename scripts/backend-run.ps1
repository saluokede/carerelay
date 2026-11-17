# 医养接力站 · 本机启动后端（8081）
# ---------------------------------------------------------------
# 用法：.\scripts\backend-run.ps1            # 打包并前台运行（Ctrl+C 退出）
#      .\scripts\backend-run.ps1 -SkipBuild  # 跳过打包，直接跑现有 jar
# 前置：先跑 .\scripts\db-start.ps1 把 MySQL 起在 3307
# ---------------------------------------------------------------
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'

$root  = Split-Path -Parent $PSScriptRoot
$mvn   = 'D:\学习\tools\idea\plugins\maven\lib\maven3\bin\mvn.cmd'
$repo  = 'D:\学习\tools\_dl\m2'
$jar   = Join-Path $root 'backend\target\carerelay-backend-0.1.0-SNAPSHOT.jar'
$port  = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8081' }

if (-not $SkipBuild -or -not (Test-Path $jar)) {
    Write-Host "打包后端（mvn package -DskipTests）..." -ForegroundColor Cyan
    & $mvn -B "-Dmaven.repo.local=$repo" -f (Join-Path $root 'backend\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "打包失败" }
}

# 数据库连接：与本机 MySQL（scripts/db-start.ps1）一致
if (-not $env:DB_PORT) { $env:DB_PORT = '3307' }
if (-not $env:DB_HOST) { $env:DB_HOST = '127.0.0.1' }
if (-not $env:DB_NAME) { $env:DB_NAME = 'carerelay' }
if (-not $env:DB_USER) { $env:DB_USER = 'root' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'root' }

Write-Host "启动后端：http://127.0.0.1:$port  （健康检查 /api/v1/health）" -ForegroundColor Green
Write-Host "接口文档：http://127.0.0.1:$port/swagger-ui.html" -ForegroundColor Green

if ($env:CR_AI_API_KEY) {
    Write-Host "AI：已注入 CR_AI_API_KEY（$($env:CR_AI_MODEL)），四个环走真实模型" -ForegroundColor Green
} else {
    Write-Host "AI：未注入 CR_AI_API_KEY，将降级为本地模式（模板兜底，界面会标注本地模式）" -ForegroundColor Yellow
    Write-Host "    启用模型：`$env:CR_AI_API_KEY='sk-...'; .\scripts\backend-run.ps1 -SkipBuild" -ForegroundColor DarkGray
}

# 优先用仓库自带的 JDK，避免 PATH 里没有 java
$java = 'D:\学习\tools\jdk21\jdk-21.0.12.1+1\bin\java.exe'
if (Test-Path $java) {
    & $java -jar $jar --server.port=$port
} else {
    java -jar $jar --server.port=$port
}
