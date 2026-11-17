/**
 * 演示数据编排：把四个环跑成一条完整故事，供录屏与截图使用。
 *
 * 为什么用脚本而不是直接改库：
 * 1) 所有动作都走真实接口，数据库里的预警、工单、审计、ai_request 都是一次真实调用的产物；
 * 2) 评审复核时只要 `node scripts/demo-seed.mjs` 就能复现同样的看板；
 * 3) 不会污染迁移脚本，V1/V2 仍然只放结构。
 *
 * 用法：node scripts/demo-seed.mjs          （默认 http://127.0.0.1:8081）
 *       API=http://127.0.0.1:4173 node scripts/demo-seed.mjs
 */
const API = (process.env.API || 'http://127.0.0.1:8081').replace(/\/$/, '')
const PHONE = process.env.DEMO_PHONE || '13800000001'
const PASSWORD = process.env.DEMO_PASSWORD || 'nurse123'

let token = ''

async function call(method, path, body) {
  const resp = await fetch(`${API}/api/v1${path}`, {
    method,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await resp.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    payload = { code: resp.status, message: text }
  }
  if (payload && typeof payload.code === 'number' && payload.code !== 0) {
    throw new Error(`${method} ${path} → ${payload.code} ${payload.message}`)
  }
  return payload?.data ?? null
}

const today = () => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function login() {
  const auth = await call('POST', '/auth/login', { phone: PHONE, password: PASSWORD })
  token = auth.token
  console.log(`✓ 登录 ${auth.user.nickname}（${auth.user.role}）`)
}

/** 执行环：按任务类型打卡，血压给数值，其他给"已完成"或一句描述 */
async function checkinFor(elderId, picks) {
  const view = await call('GET', `/checkins/today?elderId=${elderId}`)
  if (!view.tasks.length) {
    console.log(`· 老人 ${elderId} 今天没有任务，跳过`)
    return
  }
  for (const pick of picks) {
    const task = view.tasks.find((t) => t.title.includes(pick.match) && !t.checked)
    if (!task) continue
    const payload = { elderId, taskId: task.taskId }
    if (pick.bp) payload.valueJson = JSON.stringify({ kind: 'BP', systolic: pick.bp[0], diastolic: pick.bp[1] })
    if (pick.text) payload.text = pick.text
    if (!payload.valueJson && !payload.text) payload.text = '已完成，无不适'
    const result = await call('POST', '/checkins', payload)
    console.log(
      `  · ${view.elderName}｜${task.title} → ${result.status}${result.level ? `/${result.level}` : ''}` +
        `${result.alertId ? ` 预警#${result.alertId}` : ''}${result.ticketId ? ` 工单#${result.ticketId}` : ''}`,
    )
  }
}

/** 随访环：六问六答，最后一问故意说胸闷出汗，让规则/模型把它升级成工单 */
async function followupStory(elderId, answers) {
  const session = await call('POST', '/followups/schedule', { elderId, plannedDate: today() })
  const started = await call('POST', `/followups/${session.id}/start`)
  console.log(`✓ 随访 #${session.id} 开始（${started.transcript?.length ?? 0} 条开场）`)
  for (const text of answers) {
    const res = await call('POST', `/followups/${session.id}/messages`, { text })
    console.log(`  · 老人说：${text}`)
    if (res.reply) console.log(`  · 助手说：${res.reply}`)
  }
  const done = await call('POST', `/followups/${session.id}/finish`)
  console.log(`✓ 随访 #${session.id} 结束，红旗=${done.redFlag}`)
  console.log(done.summary.split('\n').map((l) => `    ${l}`).join('\n'))
  return done
}

async function weeklyReport(elderId) {
  const report = await call('POST', '/reports/weekly', { elderId })
  console.log(`✓ 老人 ${elderId} 周复盘 #${report.id}`)
  console.log(`  家属版：${(report.familyMd || '').replace(/\n/g, ' ').slice(0, 90)}…`)
  return report
}

async function main() {
  console.log(`演示数据编排 → ${API}\n`)
  await login()

  const elders = await call('GET', '/elders')
  const byCode = Object.fromEntries(elders.map((e) => [e.code, e]))
  const wang = byCode['CR-0001']
  const li = byCode['CR-0002']
  const chen = byCode['CR-0003']
  const zhao = byCode['CR-0004']
  console.log(`✓ 在管老人 ${elders.length} 位：${elders.map((e) => `${e.code} ${e.name}`).join(' / ')}\n`)

  console.log('【执行环】')
  if (wang) {
    await checkinFor(wang.id, [
      { match: '血压', bp: [138, 86] },
      { match: '服药' },
      { match: '活动' },
    ])
  }
  if (chen) {
    await checkinFor(chen.id, [{ match: '血压', bp: [148, 92] }, { match: '服药' }])
  }
  if (li) {
    // 李国强是高风险独居老人：血压高 + 自述头晕，正好演示"数值+文字"两条判读通道
    await checkinFor(li.id, [
      { match: '血压', bp: [172, 104] },
      { match: '服药', text: '药按时吃了，就是今天起床有点头晕，走路发飘' },
    ])
  }
  console.log()

  console.log('【随访环】')
  if (wang) {
    await followupStory(wang.id, [
      '这两天没有觉得哪里疼，就是晚上睡得浅',
      '药按时吃了，没有停',
      '没有摔倒，走路还稳',
      '伤口那边换药的时候有点疼，没有红肿',
      '胃口一般，情绪还行',
      '希望下次能有人上门看看血压',
    ])
  }
  if (li) {
    await followupStory(li.id, [
      '今天胸口闷得厉害，喘不上气，还出了一身冷汗',
      '药吃了',
      '没有摔倒',
      '伤口有一点点渗液',
      '晚上睡不着，心里发慌',
      '想让医生尽快来看看',
    ])
  }
  // 留一条"还没做"的随访，工作台上的「今日待随访」才不是 0——录屏讲排期时需要它。
  if (chen) {
    const pending = await call('POST', '/followups/schedule', { elderId: chen.id, plannedDate: today() })
    console.log(`✓ 给陈美英排了今天待做的随访（#${pending.id}），护士台上「今日待随访」= 1`)
  }
  console.log()

  console.log('【复盘环】')
  if (wang) await weeklyReport(wang.id)
  if (li) await weeklyReport(li.id)
  if (zhao) await weeklyReport(zhao.id)
  console.log()

  const overview = await call('GET', '/reports/overview')
  console.log('【看板】')
  console.log(`  在管老人 ${overview.elderCount} 位；今日待随访 ${overview.dueFollowups} 次`)
  console.log(`  未关闭预警 ${overview.openAlerts} 条；待处置工单 ${overview.pendingTickets} 张；平均完成度 ${overview.avgCompliance}%`)
  console.log(`  风险分布：高 ${overview.riskLevels.HIGH ?? 0} / 中 ${overview.riskLevels.MEDIUM ?? 0} / 低 ${overview.riskLevels.LOW ?? 0} / 未评估 ${overview.riskLevels.NONE ?? 0}`)
  for (const row of overview.elders) {
    console.log(
      `  · ${row.code} ${row.name} ${row.level}/${row.score} 完成度 ${row.compliance}%` +
        `${row.openAlerts ? ` 未关闭预警 ${row.openAlerts}` : ''}${row.redFollowups ? ` 随访红旗 ${row.redFollowups}` : ''}`,
    )
  }
  console.log('\n完成。打开 http://127.0.0.1:4173 用 13800000001 / nurse123 查看护士台；家属端 http://127.0.0.1:4173/family 用 13800000003 / family123。')
}

main().catch((err) => {
  console.error(`\n✗ ${err.message}`)
  process.exit(1)
})
