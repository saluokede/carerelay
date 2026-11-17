# 医养接力站 · 本机启动前端（5173）
# ---------------------------------------------------------------
# 用法：.\scripts\frontend-dev.ps1
# 前置：后端已在 8081（.\scripts\backend-run.ps1）
# 说明：本机没有 npm，用解包出来的 npm-cli.js 代替 npm install/run；
#      依赖已装好时直接起 vite dev 即可。
# ---------------------------------------------------------------
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$fe      = Join-Path $root 'frontend'
$npmCli  = 'D:\学习\tools\_dl\package\bin\npm-cli.js'
$npmCache = 'D:\学习\tools\_dl\npm-cache'
$apiTarget = if ($env:CR_API_TARGET) { $env:CR_API_TARGET } else { 'http://127.0.0.1:8081' }

Push-Location $fe
try {
    if (-not (Test-Path (Join-Path $fe 'node_modules\vite'))) {
        Write-Host "首次运行，安装前端依赖..." -ForegroundColor Cyan
        node $npmCli install --registry=https://registry.npmmirror.com --cache $npmCache --ignore-scripts --no-audit --no-fund
    }
    $env:CR_API_TARGET = $apiTarget
    Write-Host "启动前端：http://127.0.0.1:5173  （/api → $apiTarget）" -ForegroundColor Green
    node (Join-Path $fe 'node_modules\vite\bin\vite.js')
} finally {
    Pop-Location
}
