# 把演示数据恢复到"刚跑完 demo-seed 的样子"，录屏前跑一次即可
#
# 保留：账号、老人档案、出院小结、风险评分、照护计划与任务
# 清空：打卡、预警、工单、随访会话与记录、周报
# 然后重新执行 scripts/demo-seed.mjs 把故事线走一遍
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\demo-reset.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\demo-reset.ps1 -SkipSeed   # 只清空

param(
  [switch]$SkipSeed
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$mysql = 'C:\carerelay-mysql-root\cr-mysql\bin\mysql.exe'
$node = 'D:\学习\tools\_dl\package\bin\node.exe'
if (-not (Test-Path $node)) { $node = (Get-Command node).Source }

if (-not (Test-Path $mysql)) {
  throw "找不到 mysql 客户端：$mysql（先跑 scripts/db-start.ps1）"
}

$sql = @'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE ticket;
TRUNCATE TABLE alert_event;
TRUNCATE TABLE checkin;
TRUNCATE TABLE followup_record;
TRUNCATE TABLE followup_session;
TRUNCATE TABLE report;
-- 验收脚本每次会建一位"测试老人"，这里连它的从属行一起清掉，保证看板只有 4 位演示老人
DELETE ct FROM care_task ct JOIN elder_profile e ON ct.elder_id = e.id WHERE e.name LIKE '测试老人%';
DELETE p  FROM care_plan p  JOIN elder_profile e ON p.elder_id  = e.id WHERE e.name LIKE '测试老人%';
DELETE ra FROM risk_assessment ra JOIN elder_profile e ON ra.elder_id = e.id WHERE e.name LIKE '测试老人%';
DELETE d  FROM discharge_record d JOIN elder_profile e ON d.elder_id  = e.id WHERE e.name LIKE '测试老人%';
DELETE FROM elder_profile WHERE name LIKE '测试老人%';
SET FOREIGN_KEY_CHECKS = 1;
'@

Write-Host '[1/3] 清空打卡 / 预警 / 工单 / 随访 / 周报 …' -ForegroundColor Cyan
$sql | & $mysql --host=127.0.0.1 --port=3307 --user=root --password=root `
  --default-character-set=utf8mb4 carerelay 2>&1 | Where-Object { $_ -notmatch 'Using a password' }
if ($LASTEXITCODE -ne 0) { throw "清空失败（mysql 退出码 $LASTEXITCODE）" }

if ($SkipSeed) {
  Write-Host '[2/3] 已按要求跳过重建演示数据。' -ForegroundColor Yellow
} else {
  Write-Host '[2/3] 重建演示数据（真实接口调用）…' -ForegroundColor Cyan
  Push-Location (Join-Path $root 'frontend')
  try {
    & $node (Join-Path $root 'scripts\demo-seed.mjs')
    if ($LASTEXITCODE -ne 0) { throw "demo-seed 失败（退出码 $LASTEXITCODE）" }
  } finally {
    Pop-Location
  }
}

Write-Host '[3/3] 当前看板数字：' -ForegroundColor Cyan
$body = @{ phone = '13800000001'; password = 'nurse123' } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/api/v1/auth/login' -Method Post `
  -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$overview = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/api/v1/reports/overview' -Headers $headers -TimeoutSec 10
$o = $overview.data
Write-Host ("   在管老人 {0} 位 | 今日待随访 {1} | 未关闭预警 {2} | 待处置工单 {3} | 平均完成度 {4}%" -f `
  $o.elderCount, $o.dueFollowups, $o.openAlerts, $o.pendingTickets, $o.avgCompliance) -ForegroundColor Green
Write-Host ("   风险分布：高 {0} / 中 {1} / 低 {2}" -f $o.riskLevels.HIGH, $o.riskLevels.MEDIUM, $o.riskLevels.LOW) -ForegroundColor Green
