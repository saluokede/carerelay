# 一键停止：前端 → 后端 → 数据库
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

Write-Host ''
Write-Host '=== 停止前端 ===' -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*serve-frontend.mjs*' } |
    ForEach-Object {
        Write-Host "  结束 node PID $($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Host ''
Write-Host '=== 停止后端 ===' -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*carerelay-backend*' } |
    ForEach-Object {
        Write-Host "  结束 java PID $($_.ProcessId)"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Host ''
Write-Host '=== 停止 MySQL ===' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'db-stop.ps1')

Write-Host ''
Write-Host '已全部停止。数据仍在 C:\carerelay-mysql-root\cr-mysql\data，下次启动不会丢。' -ForegroundColor Green
Write-Host ''
