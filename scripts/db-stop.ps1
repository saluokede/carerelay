# 医养接力站 · 停止本机 MySQL（与 db-start.ps1 同一运行目录）
$ErrorActionPreference = 'Continue'
$mysqlHome = 'C:\carerelay-mysql-root\cr-mysql'
$pidFile   = 'C:\carerelay-mysql-root\cr-mysql.pid'
$port      = 3307

$admin = Join-Path $mysqlHome 'bin\mysqladmin.exe'
if (Test-Path $admin) {
    $alive = (& $admin -u root -proot -h 127.0.0.1 -P $port --protocol=TCP ping 2>$null) -join ' '
    if ($alive -match 'alive') {
        & $admin -u root -proot -h 127.0.0.1 -P $port --protocol=TCP shutdown 2>$null | Out-Null
        Write-Host "MySQL 已停止。" -ForegroundColor Green
        exit 0
    }
}
if (Test-Path $pidFile) {
    $pid_ = Get-Content $pidFile
    Stop-Process -Id $pid_ -ErrorAction SilentlyContinue
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Host "已按 PID 记录停止 mysqld（$pid_）。" -ForegroundColor Green
} else {
    Write-Host "MySQL 未在运行。" -ForegroundColor Yellow
}
