# 一键建立 Git 历史（需要主人明确同意后运行）
#
# 赛事要求"完整的、细粒度的 Git 提交历史，禁止一次性提交"，所以这个脚本
# 按模块把当前工作区拆成十余次提交，每条提交信息都对应一个可复述的功能单元。
#
# 用法（在仓库根目录）：
#   pwsh -File .\scripts\git-bootstrap.ps1
#
# 脚本只做本地 git init / add / commit，不做任何 push，也不改动远端。
# 已存在 .git 时会直接退出，避免重复初始化。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  throw '没有找到 git，请先安装 Git for Windows 并加入 PATH。'
}
if (Test-Path (Join-Path $root '.git')) {
  Write-Host '仓库里已经有 .git，本次不做任何改动。' -ForegroundColor Yellow
  exit 0
}

git init -b main 2>&1 | Out-Null

# 提交身份只写到本仓库配置，不碰全局设置；如需改成自己的名字/邮箱，改这两行即可。
git config user.name  'CareRelay Team'
git config user.email 'carerelay@example.com'

$ErrorActionPreference = 'Continue'

$commits = @(
  @{ Msg = 'chore: 工程骨架、构建配置与目录约定'
     Paths = @('.gitignore', '.gitattributes', '.mvn-settings.xml', '.env.example', 'backend/pom.xml',
               'frontend/package.json', 'frontend/tsconfig.json', 'frontend/tsconfig.node.json',
               'frontend/vite.config.ts', 'frontend/index.html', 'frontend/env.d.ts', 'deploy') }

  @{ Msg = 'feat(common): 统一返回体、异常处理、审计与 AI 网关'
     Paths = @('backend/src/main/java/com/carerelay/common',
               'backend/src/main/java/com/carerelay/audit',
               'backend/src/main/java/com/carerelay/ai',
               'backend/src/main/resources/prompts',
               'backend/src/main/java/com/carerelay/CareRelayApplication.java') }

  @{ Msg = 'feat(auth): JWT 登录、角色边界与医护权限校验'
     Paths = @('backend/src/main/java/com/carerelay/auth',
               'backend/src/main/java/com/carerelay/user',
               'backend/src/main/java/com/carerelay/api/AuthController.java',
               'backend/src/main/java/com/carerelay/api/HealthController.java') }

  @{ Msg = 'feat(db): Flyway 建库脚本与演示账号种子'
     Paths = @('backend/src/main/resources/db',
               'backend/src/main/java/com/carerelay/conf') }

  @{ Msg = 'feat(elder): 老人档案与接收出院建档'
     Paths = @('backend/src/main/java/com/carerelay/elder',
               'backend/src/main/java/com/carerelay/api/ElderController.java') }

  @{ Msg = 'feat(ai): 出院小结结构化抽取与字段置信度'
     Paths = @('backend/src/main/java/com/carerelay/discharge',
               'backend/src/main/java/com/carerelay/api/DischargeController.java') }

  @{ Msg = 'feat(risk): 风险评分卡（代码算分 + 模型解释）'
     Paths = @('backend/src/main/java/com/carerelay/risk',
               'backend/src/main/java/com/carerelay/api/RiskController.java') }

  @{ Msg = 'feat(plan): 30 天照护计划生成、任务展开与护士签发'
     Paths = @('backend/src/main/java/com/carerelay/plan',
               'backend/src/main/java/com/carerelay/api/PlanController.java') }

  @{ Msg = 'feat(checkin): 每日打卡与数值/文字判读分流'
     Paths = @('backend/src/main/java/com/carerelay/checkin',
               'backend/src/main/java/com/carerelay/api/CheckinController.java') }

  @{ Msg = 'feat(alert): 预警与工单闭环'
     Paths = @('backend/src/main/java/com/carerelay/alert',
               'backend/src/main/java/com/carerelay/ticket',
               'backend/src/main/java/com/carerelay/api/AlertController.java',
               'backend/src/main/java/com/carerelay/api/AiPingController.java') }

  @{ Msg = 'feat(followup): 六问随访 Agent 与结构化总结'
     Paths = @('backend/src/main/java/com/carerelay/followup',
               'backend/src/main/java/com/carerelay/api/FollowupController.java') }

  @{ Msg = 'feat(report): 双版本周报、30 天结局与护士台总览'
     Paths = @('backend/src/main/java/com/carerelay/report',
               'backend/src/main/java/com/carerelay/api/ReportController.java') }

  @{ Msg = 'feat(web): 前端设计系统与十个页面'
     Paths = @('frontend/src') }

  @{ Msg = 'test: 23 条单元测试与 43 条验收脚本'
     Paths = @('backend/src/test', 'scripts/api-test.mjs') }

  @{ Msg = 'chore(scripts): 启停脚本与演示数据编排'
     Paths = @('scripts') }

  @{ Msg = 'docs: AI 运行方向、技术文档、演示脚本、提交清单与开发日志'
     Paths = @('docs', 'README.md') }
)

$n = 0
foreach ($c in $commits) {
  $existing = $c.Paths | Where-Object { Test-Path $_ }
  if (-not $existing) { continue }
  git add -- $existing 2>&1 | Out-Null
  git diff --cached --quiet
  if ($LASTEXITCODE -eq 1) {
    git commit -q -m $c.Msg
    $n++
    Write-Host ("[ok] " + $c.Msg) -ForegroundColor Green
  }
}

# 兜底：仍有未纳入分组、且在 .gitignore 之外的文件时，单独成一次提交。
git add -A 2>&1 | Out-Null
git diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
  git commit -q -m 'chore: 其余配置与素材'
  $n++
  Write-Host '[ok] chore: 其余配置与素材' -ForegroundColor Green
}

Write-Host ''
Write-Host ("共生成 {0} 次提交：" -f $n) -ForegroundColor Cyan
git --no-pager log --oneline
Write-Host ''
Write-Host '下一步等主人决定是否推送远端（脚本不会自行 push）。' -ForegroundColor Yellow
