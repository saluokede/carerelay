# 打一个"可以直接发给别人"的压缩包
#
# 排除：node_modules / target / dist / .tmp / .git（这些别人拿到也能自己生成）
# 保留：源码、脚本、文档（含 PDF 与技术文档）、截图、README、.env.example
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\package-zip.ps1
# 产物：D:\学习\医养接力站-交付\医养接力站-<时间戳>.zip

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$outDir = Join-Path (Split-Path -Parent $root) '医养接力站-交付'
$stage = Join-Path $env:TEMP "carerelay-package-$stamp"
$zip = Join-Path $outDir "医养接力站-$stamp.zip"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }

Write-Host '[1/3] 复制源码与文档（跳过 node_modules / target / dist / .tmp / .git）…' -ForegroundColor Cyan
robocopy $root $stage /E /XD node_modules target dist .tmp .git /NFL /NDL /NJH /NJS /NP | Out-Null

# 交付包里补一份"怎么打开"的说明，别人拿到不用问
$readme = @"
医养接力站（CareRelay）交付包 · $stamp

一、这是什么
面向出院老人的智能康复照护与社区随访系统。四个环：交接（出院小结→档案→评分→30 天计划）、
执行（每日打卡判读）、随访（六问对话 Agent）、复盘（双版本周报与 30 天结局）。

二、最快看到界面（3 步）
  1) 安装 JDK 21、Node 18+、MySQL 8（或用仓库里 scripts/db-start.ps1 的便携版方案）
  2) 按 README.md 的「本地运行」四步走：db-start → backend-run → 前端构建 + serve-frontend → demo-seed
  3) 浏览器打开 http://127.0.0.1:4173 ，用 13800000001 / nurse123 登录

三、演示账号
  管理员 13800000000 / admin123   （系统设置里可以填模型 API Key）
  社区护士 13800000001 / nurse123
  家庭医生 13800000002 / doctor123
  家属    13800000003 / family123

四、先看什么
  docs/01-AI运行方向说明.md   —— AI 在系统里怎么跑（设计说明）
  docs/03-技术文档.pdf        —— 技术文档（10 页）
  docs/04-演示与录屏脚本.md   —— 逐段讲解稿
  docs/05-提交清单与答辩要点.md
  docs/screenshots/           —— 十张真跑截图
  docs/ai-log/                —— 每一轮 AI 协作的输入、产出、人工改动与验证

五、没有 API Key 也能跑
系统会降级到本地规则模式：抽取改为手工填写、文字打卡走关键词判读、随访走六问脚本、周报走模板。
要接模型：管理员登录 → 左侧「系统设置」→ 填接口地址/模型名/Key → 测试连接 → 保存（立即生效）。
"@
[System.IO.File]::WriteAllText((Join-Path $stage '从这里开始.txt'), $readme, (New-Object System.Text.UTF8Encoding($false)))

Write-Host '[2/3] 压缩…' -ForegroundColor Cyan
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -CompressionLevel Optimal

Remove-Item $stage -Recurse -Force

$size = [math]::Round((Get-Item $zip).Length / 1MB, 2)
Write-Host '[3/3] 完成' -ForegroundColor Green
Write-Host ("  压缩包：{0}" -f $zip)
Write-Host ("  大小：{0} MB" -f $size)
Write-Host '  直接把这个 zip 发微信/邮件/网盘即可；对方解压后按「从这里开始.txt」操作。' -ForegroundColor Yellow
