# 医养接力站 · 启动本机 MySQL（便携版，3307 端口）
# ---------------------------------------------------------------
# 两处历史坑，写在这里免得再踩：
#
# 1) mysqld.exe 是原生程序，--datadir 里的中文会被解析坏
#    （实测 --datadir="D:\学习\tools\mysql-data" 被读成 D:\data\ 然后启动失败）。
#    所以运行目录放纯 ASCII 路径：C:\carerelay-mysql-root\cr-mysql。
#
# 2) 运行目录不能放 $env:TEMP：每次 dsh 命令的 TEMP 都是独立新目录
#    （C:\Users\xxx\AppData\Local\Temp\dsh-xxxx），命令一结束目录就被清掉，
#    数据库随之消失，后端再连就是 Connection refused。
#    因此固定用 C:\carerelay-mysql-root\cr-mysql，跨会话复用同一份数据。
#
# 用法：.\scripts\db-start.ps1
# 停止：.\scripts\db-stop.ps1
# ---------------------------------------------------------------

# PS 5.1 会把原生程序的 stderr 当成 ErrorRecord，配合 Stop 会中断，故用 Continue，
# 所有原生调用 2>$null 吞掉警告，靠输出判断成败。
$ErrorActionPreference = 'Continue'

$src       = 'D:\学习\tools\mysql-8.0.29-winx64'   # 官方 zip 解压出的二进制（放中文路径下可以，不能直接运行）
$runRoot   = 'C:\carerelay-mysql-root'
$mysqlHome = Join-Path $runRoot 'cr-mysql'
$data      = Join-Path $mysqlHome 'data'
$pidFile   = Join-Path $runRoot 'cr-mysql.pid'
$port      = 3307

function Get-Ping($adminExe, [string]$pwd) {
    # 原生程序 stderr 在 PS 5.1 会变成 ErrorRecord，统一合并成字符串
    if ($pwd) {
        $raw = (& $adminExe -u root "-p$pwd" -h 127.0.0.1 -P $port --protocol=TCP ping 2>$null) -join ' '
    } else {
        $raw = (& $adminExe -u root -h 127.0.0.1 -P $port --protocol=TCP ping 2>$null) -join ' '
    }
    return ($raw -match 'alive')
}

New-Item -ItemType Directory -Force $runRoot | Out-Null

# 0) 3307 上已有实例在跑（包括别人手工起的）
$admin = Join-Path $mysqlHome 'bin\mysqladmin.exe'
if (Test-Path $admin) {
    if ((Get-Ping $admin 'root') -or (Get-Ping $admin '')) {
        Write-Host "MySQL 已在 127.0.0.1:$port 运行，跳过启动。" -ForegroundColor Green
        & (Join-Path $mysqlHome 'bin\mysql.exe') -u root -proot -h 127.0.0.1 -P $port --protocol=TCP `
            -e "CREATE DATABASE IF NOT EXISTS carerelay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>$null | Out-Null
        Write-Host "数据库就绪：jdbc:mysql://127.0.0.1:$port/carerelay  (root/root)" -ForegroundColor Green
        exit 0
    }
}

if (-not (Test-Path "$src\bin\mysqld.exe")) {
    throw "找不到 MySQL 二进制：$src\bin\mysqld.exe，请先把 mysql-8.0.29-winx64.zip 解压到 D:\学习\tools\"
}

# 1) 首次运行：复制二进制到 ASCII 目录（约 860MB，只做一次）
if (-not (Test-Path (Join-Path $mysqlHome 'bin\mysqld.exe'))) {
    Write-Host "首次运行，复制 MySQL 到 $mysqlHome ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force $mysqlHome | Out-Null
    robocopy $src $mysqlHome /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
}

# 2) 首次运行：初始化数据目录（root 空密码，稍后统一成 root）
if (-not (Test-Path (Join-Path $data 'mysql'))) {
    Write-Host "初始化数据目录 $data ..." -ForegroundColor Cyan
    & (Join-Path $mysqlHome 'bin\mysqld.exe') --initialize-insecure --basedir="$mysqlHome" --datadir="$data" --console 2>$null | Out-Null
}

# 3) 启动 mysqld（独立进程：本脚本退出后数据库继续跑）
Write-Host "启动 mysqld（127.0.0.1:$port）..." -ForegroundColor Cyan
$proc = Start-Process -FilePath (Join-Path $mysqlHome 'bin\mysqld.exe') `
    -ArgumentList "--basedir=`"$mysqlHome`"", "--datadir=`"$data`"", "--port=$port" `
    -WorkingDirectory $mysqlHome -WindowStyle Hidden -PassThru
$proc.Id | Set-Content $pidFile

$alive = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 800
    if (Get-Ping (Join-Path $mysqlHome 'bin\mysqladmin.exe') 'root') { $alive = $true; break }
    if (Get-Ping (Join-Path $mysqlHome 'bin\mysqladmin.exe') '') { $alive = $true; break }
}
if (-not $alive) { throw "mysqld 没起来，检查 $data\*.err 日志" }
Write-Host "mysqld 已启动（PID $($proc.Id)）。" -ForegroundColor Green

# 4) 统一 root 密码为 root（与 .env.example 的 DB_PASSWORD 一致），并建库
& (Join-Path $mysqlHome 'bin\mysql.exe') -u root -h 127.0.0.1 -P $port --protocol=TCP `
    -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'root'; FLUSH PRIVILEGES;" 2>$null | Out-Null
& (Join-Path $mysqlHome 'bin\mysql.exe') -u root -proot -h 127.0.0.1 -P $port --protocol=TCP `
    -e "CREATE DATABASE IF NOT EXISTS carerelay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>$null | Out-Null

Write-Host "数据库就绪：jdbc:mysql://127.0.0.1:$port/carerelay  (root/root)" -ForegroundColor Green
