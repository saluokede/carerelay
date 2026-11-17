# 一键启动：数据库 → 后端 → 前端 → 打开浏览器
#
# 用法：双击项目根目录的「启动.cmd」
# 停止：双击「停止.cmd」（或 scripts\stop-all.ps1）
# 强制重建前端：powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -Rebuild
#
# 前置：JDK 21、Node 18+；后端 jar 已构建（backend\target\carerelay-backend-0.1.0-SNAPSHOT.jar）
# 说明：MySQL 用便携版，数据目录固定在 C:\carerelay-mysql-root（mysqld 不认中文路径）

param(
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$script:failed = $false
$root = Split-Path -Parent $PSScriptRoot
$tmpDir = Join-Path $root '.tmp\tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

function Test-Port([int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Http([string]$url, [int]$seconds, [string]$name) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) {
                Write-Host "  [OK] $name 已就绪" -ForegroundColor Green
                return $true
            }
        } catch {
            # 还没起来，继续等
        }
        Start-Sleep -Milliseconds 800
    }
    Write-Host "  [!] $name 在 $seconds 秒内没起来" -ForegroundColor Yellow
    return $false
}

function Rotate-Log([string]$path) {
    if (Test-Path $path) {
        $prev = [System.IO.Path]::ChangeExtension($path, '.prev.log')
        Move-Item -LiteralPath $path -Destination $prev -Force
    }
}

Write-Host ''
Write-Host '=== 1/4 启动 MySQL ===' -ForegroundColor Cyan
if (Test-Port 3307) {
    Write-Host '  [i] 3307 已在监听，跳过（多半是上次没停的 MySQL）' -ForegroundColor DarkGray
} else {
    & (Join-Path $PSScriptRoot 'db-start.ps1')
}

Write-Host ''
Write-Host '=== 2/4 启动后端（8081）===' -ForegroundColor Cyan
$jar = Join-Path $root 'backend\target\carerelay-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
    Write-Host '  [!] 还没构建后端。先在 backend 目录执行：mvn -DskipTests package' -ForegroundColor Yellow
    exit 1
}

if (Test-Port 8081) {
    Write-Host '  [i] 8081 已在监听，后端应该已经在跑，跳过' -ForegroundColor DarkGray
} else {
    $javaCandidates = @(
        'D:\学习\tools\jdk21\jdk-21.0.12.1+1\bin\java.exe',
        $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }),
        (Get-Command java -ErrorAction SilentlyContinue).Source
    )
    $java = $javaCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

    if (-not $java) {
        Write-Host '  [!] 找不到 java，请安装 JDK 21 或设置 JAVA_HOME' -ForegroundColor Red
        exit 1
    }

    $env:DB_HOST = '127.0.0.1'
    $env:DB_PORT = '3307'
    $env:SERVER_PORT = '8081'

    $log = Join-Path $root '.tmp\backend.log'
    Rotate-Log $log
    # 路径里可能有空格：ArgumentList 会被按空格拆开，所以自己加引号
    $argLine = '-Djava.io.tmpdir="{0}" -jar "{1}"' -f $tmpDir, $jar
    Start-Process -FilePath $java `
        -ArgumentList $argLine `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $log `
        -RedirectStandardError (Join-Path $root '.tmp\backend.err.log')
    Write-Host "  [i] 已启动，日志：$log" -ForegroundColor DarkGray
    if (-not (Wait-Http 'http://127.0.0.1:8081/api/v1/health' 90 '后端')) {
        Write-Host '  [x] 后端没起来，看 .tmp\backend.err.log' -ForegroundColor Red
        $script:failed = $true
    }
}

Write-Host ''
Write-Host '=== 3/4 启动前端（4173）===' -ForegroundColor Cyan
if (Test-Port 4173) {
    Write-Host '  [i] 4173 已在监听，前端应该已经在跑，跳过' -ForegroundColor DarkGray
} else {
    $fe = Join-Path $root 'frontend'
    $distIndex = Join-Path $fe 'dist\index.html'

    $needBuild = $Rebuild -or -not (Test-Path $distIndex)
    if (-not $needBuild) {
        $distTime = (Get-Item $distIndex).LastWriteTime
        $srcTime = (Get-ChildItem (Join-Path $fe 'src') -Recurse -File |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1).LastWriteTime
        if ($srcTime -gt $distTime) {
            Write-Host '  [i] 源码比 dist 新，重新构建前端…' -ForegroundColor DarkGray
            $needBuild = $true
        }
    }

    if ($needBuild) {
        $npm = (Get-Command npm -ErrorAction SilentlyContinue).Source
        if (-not $npm) {
            Write-Host '  [!] 找不到 npm，请安装 Node 18+' -ForegroundColor Red
            exit 1
        }
        Push-Location $fe
        try {
            & $npm run build
            if ($LASTEXITCODE -ne 0) {
                Write-Host '  [!] 前端构建失败，看上面的报错' -ForegroundColor Red
                exit 1
            }
        } finally {
            Pop-Location
        }
    }

    $node = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $node) {
        Write-Host '  [!] 找不到 node，请安装 Node 18+' -ForegroundColor Red
        exit 1
    }

    $feLog = Join-Path $root '.tmp\frontend.log'
    Rotate-Log $feLog
    $serveScript = Join-Path $root 'scripts\serve-frontend.mjs'
    Start-Process -FilePath $node `
        -ArgumentList ('"{0}"' -f $serveScript) `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $feLog `
        -RedirectStandardError (Join-Path $root '.tmp\frontend.err.log')
    if (-not (Wait-Http 'http://127.0.0.1:4173/' 30 '前端')) {
        Write-Host '  [x] 前端没起来，看 .tmp\frontend.err.log' -ForegroundColor Red
        $script:failed = $true
    }
}

Write-Host ''
Write-Host '=== 4/4 打开浏览器 ===' -ForegroundColor Cyan
Start-Process 'http://127.0.0.1:4173'

Write-Host ''
if ($script:failed) {
    Write-Host '启动没完全成功：看 .tmp\backend.err.log 与 .tmp\frontend.err.log' -ForegroundColor Red
    exit 1
}
Write-Host '全部就绪：' -ForegroundColor Green
Write-Host '  界面 http://127.0.0.1:4173     护士 13800000001 / nurse123'
Write-Host '  接口 http://127.0.0.1:8081/api/v1/health'
Write-Host '  日志 .tmp\backend.log、.tmp\frontend.log'
Write-Host '  停止：双击「停止.cmd」'
Write-Host ''
