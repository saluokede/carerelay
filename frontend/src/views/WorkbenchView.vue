<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { admissionStats, listElders, fetchOverview, type AdmissionStat, type Elder, type Overview } from '@/api'
import { formatFriendlyDate, levelLabel, levelTone, percentText } from '@/utils/format'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const overview = ref<Overview | null>(null)
const elders = ref<Elder[]>([])
const admissionRows = ref<AdmissionStat[]>([])

const today = formatFriendlyDate(new Date().toISOString())

const riskRows = computed(() => {
  const levels = overview.value?.riskLevels ?? {}
  const total = Math.max(1, Object.values(levels).reduce((a, b) => a + b, 0))
  return [
    { key: 'HIGH', label: '高风险', tone: 'danger', value: levels.HIGH ?? 0, pct: ((levels.HIGH ?? 0) / total) * 100 },
    { key: 'MEDIUM', label: '中风险', tone: 'warn', value: levels.MEDIUM ?? 0, pct: ((levels.MEDIUM ?? 0) / total) * 100 },
    { key: 'LOW', label: '低风险', tone: 'ok', value: levels.LOW ?? 0, pct: ((levels.LOW ?? 0) / total) * 100 },
  ]
})

const admMap = computed(() => new Map(admissionRows.value.map((s) => [s.elderId, s])))
const elderMap = computed(() => new Map(elders.value.map((e) => [e.id, e])))

function openElder(id: number) {
  router.push({ name: 'elder-detail', params: { id } })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [ov, list, adm] = await Promise.all([fetchOverview(), listElders(), admissionStats().catch(() => [])])
    overview.value = ov
    elders.value = list
    admissionRows.value = adm
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <section class="hero">
      <div class="hero-copy">
        <div class="hero-kicker">NURSE WORKBENCH · {{ today }}</div>
        <h1 class="hero-title">今天要接住谁</h1>
        <p class="hero-lead">
          出院后的 30 天里，最容易断的是"没人接着"的那一下。
          今天有 <b>{{ overview?.dueFollowups ?? 0 }}</b> 场随访待打、
          <b>{{ overview?.openAlerts ?? 0 }}</b> 条预警没关、
          <b>{{ overview?.pendingTickets ?? 0 }}</b> 张工单在等人处置。
        </p>
      </div>
      <div class="hero-actions">
        <button class="hero-btn" @click="load">刷新</button>
        <button class="hero-btn solid" @click="router.push({ name: 'elder-new' })">＋ 接收新出院</button>
      </div>

      <ol class="hero-flow">
        <li><span class="node">1</span><b>交接</b><em>小结结构化 · 护士签发</em></li>
        <li><span class="node">2</span><b>执行</b><em>每日任务 · 打卡判读</em></li>
        <li><span class="node">3</span><b>随访</b><em>按风险分层排期</em></li>
        <li><span class="node">4</span><b>复盘</b><em>周报 · 30 天结局</em></li>
      </ol>

      <div class="hero-stats">
        <div class="hero-stat">
          <span class="k">在管老人</span>
          <strong class="num">{{ overview?.elderCount ?? '—' }}</strong>
          <span class="m">含出院 30 天内随访期</span>
        </div>
        <div class="hero-stat">
          <span class="k">今日待随访</span>
          <strong class="num" :class="{ hot: (overview?.dueFollowups ?? 0) > 0 }">{{ overview?.dueFollowups ?? '—' }}</strong>
          <span class="m">按风险分层自动排期</span>
        </div>
        <div class="hero-stat">
          <span class="k">未关闭预警</span>
          <strong class="num" :class="{ hot: (overview?.openAlerts ?? 0) > 0 }">{{ overview?.openAlerts ?? '—' }}</strong>
          <span class="m">数值越界 / 症状词 / 随访红旗</span>
        </div>
        <div class="hero-stat">
          <span class="k">待处置工单</span>
          <strong class="num" :class="{ hot: (overview?.pendingTickets ?? 0) > 0 }">{{ overview?.pendingTickets ?? '—' }}</strong>
          <span class="m">高危预警自动升级</span>
        </div>
      </div>
    </section>

    <p v-if="error" class="note danger">{{ error }}</p>


    <div class="grid cols-2" style="margin-top: 16px">
      <div class="card">
        <div class="card-head">
          <h3>风险分布</h3>
          <span class="hint">评分卡由代码计算，解释由模型生成</span>
        </div>
        <div class="risk-bars">
          <div v-for="row in riskRows" :key="row.key" class="risk-row">
            <span class="pill" :class="row.tone === 'ok' ? '' : row.tone">{{ row.label }}</span>
            <div class="track">
              <div class="fill" :class="row.tone" :style="{ width: `${Math.max(row.pct, 3)}%` }" />
            </div>
            <span class="num risk-count">{{ row.value }} 人</span>
          </div>
        </div>
        <div class="divider" />
        <div class="row">
          <span class="muted">本周平均任务完成率</span>
          <span class="spacer" />
          <strong class="num">{{ percentText(overview?.avgCompliance) }}</strong>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h3>接力提醒</h3>
          <span class="hint">这三件事做漏了，接力就断了</span>
        </div>
        <ul class="checklist">
          <li>
            <span class="idx">1</span>
            <div>
              <strong>出院小结结构化了吗</strong>
              <p class="muted">药名、剂量、管路、伤口、复诊时间必须落成字段，后面所有判断都靠它。</p>
            </div>
          </li>
          <li>
            <span class="idx">2</span>
            <div>
              <strong>计划签发了吗</strong>
              <p class="muted">AI 只出草案；护士签字后任务才会出现在家属端。</p>
            </div>
          </li>
          <li>
            <span class="idx">3</span>
            <div>
              <strong>预警关了吗</strong>
              <p class="muted">关闭时必须写处置结论，否则下一班看不到发生了什么。</p>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">
        <h3>在管老人</h3>
        <span class="hint">按风险分从高到低</span>
      </div>

      <div v-if="loading" class="empty"><span class="spin" /> 正在读取…</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>老人</th>
            <th>风险</th>
            <th>任务完成率</th>
            <th>未关闭预警</th>
            <th>随访红旗</th>
            <th>住院</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in overview?.elders ?? []" :key="row.elderId" class="clickable" @click="openElder(row.elderId)">
            <td>
              <div class="who-cell">
                <div class="who-name">{{ row.name }}</div>
                <div class="muted">
                  <span class="mono">{{ row.code }}</span>
                  <span v-if="elderMap.get(row.elderId)?.age"> · {{ elderMap.get(row.elderId)?.age }} 岁</span>
                  <span v-if="elderMap.get(row.elderId)?.livingStatus">
                    · {{ { ALONE: '独居', CHILDREN: '与子女同住', SPOUSE: '与配偶同住' }[elderMap.get(row.elderId)?.livingStatus ?? ''] ?? '居住待补' }}
                  </span>
                </div>
              </div>
            </td>
            <td>
              <span class="pill" :class="levelTone(row.level) === 'ok' ? '' : levelTone(row.level)">
                {{ levelLabel(row.level) }} · {{ row.score }} 分
              </span>
            </td>
            <td>
              <div class="compliance">
                <div class="track small"><div class="fill" :style="{ width: `${Math.min(row.compliance, 100)}%` }" /></div>
                <span class="num">{{ row.compliance }}%</span>
              </div>
            </td>
            <td>
              <span v-if="row.openAlerts" class="pill danger">{{ row.openAlerts }}</span>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <span v-if="row.redFollowups" class="pill warn">{{ row.redFollowups }}</span>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <span v-if="admMap.get(row.elderId)" class="num" :title="`累计 ${admMap.get(row.elderId)!.days} 天`">
                {{ admMap.get(row.elderId)!.count }} 次 · {{ admMap.get(row.elderId)!.days }} 天
              </span>
              <span v-else class="muted">—</span>
            </td>
            <td class="right"><span class="link">查看 →</span></td>
          </tr>
          <tr v-if="!(overview?.elders ?? []).length">
            <td colspan="7" class="empty">还没有建档老人，先从「接收新出院」开始。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 接力封面带：首屏先说"今天要接住谁"，再说数字 ---------- */
.hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 24px 32px;
  padding: 32px 32px 0;
  margin-bottom: 20px;
  border-radius: 22px;
  overflow: hidden;
  color: #efe9db;
  background:
    radial-gradient(620px 260px at 90% -30%, rgba(214, 198, 160, 0.16), transparent 70%),
    linear-gradient(158deg, #133d34 0%, #0b2a24 64%);
  box-shadow: 0 26px 50px -42px rgba(11, 42, 36, 0.95);
}
.hero-kicker { font-size: 11px; letter-spacing: 0.2em; text-transform: uppercase; color: #c9b892; font-weight: 600; }
.hero-title {
  margin-top: 10px;
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: clamp(30px, 3.2vw, 42px);
  line-height: 1.2;
  color: #f7f3e7;
  letter-spacing: 0.01em;
}
.hero-lead { margin-top: 14px; max-width: 54ch; color: #c6d1c9; font-size: 14.5px; line-height: 1.85; }
.hero-lead b { color: #f2e0b0; font-weight: 650; font-variant-numeric: tabular-nums; }
.hero-actions { display: flex; gap: 10px; align-items: flex-start; }
.hero-btn {
  height: 38px; padding: 0 18px; border-radius: 999px; font-size: 14px;
  border: 1px solid rgba(239, 233, 219, 0.26); background: transparent; color: #efe9db;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.hero-btn:hover { background: rgba(247, 243, 231, 0.1); border-color: rgba(239, 233, 219, 0.4); }
.hero-btn.solid { background: #f6f2e9; color: #0f312a; border-color: transparent; font-weight: 620; }
.hero-btn.solid:hover { background: #fffdf8; }

/* 四环：一条时间带，说明这套系统到底在接什么 */
.hero-flow {
  grid-column: 1 / -1;
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px;
  list-style: none; margin: 0; padding: 18px 0 17px;
  border-top: 1px solid rgba(239, 233, 219, 0.14);
}
.hero-flow li { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 3px 10px; font-size: 13.5px; }
.hero-flow .node {
  grid-row: span 2; width: 26px; height: 26px; border-radius: 999px;
  display: grid; place-items: center; font-size: 12px; font-weight: 600;
  background: rgba(247, 243, 231, 0.12); color: #f6f2e9;
}
.hero-flow b { color: #f4efe3; font-weight: 600; letter-spacing: 0.04em; }
.hero-flow em { font-style: normal; color: #9fb6ad; font-size: 12px; }

/* 数字：像报纸的报头统计，一条横线切开 */
.hero-stats {
  grid-column: 1 / -1;
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0 -32px;
  border-top: 1px solid rgba(239, 233, 219, 0.14);
}
.hero-stat { padding: 18px 32px 22px; border-right: 1px solid rgba(239, 233, 219, 0.1); display: flex; flex-direction: column; gap: 2px; }
.hero-stat:last-child { border-right: none; }
.hero-stat .k { font-size: 12px; color: #a9bdb5; letter-spacing: 0.06em; }
.hero-stat strong { font-size: 40px; line-height: 1.08; font-weight: 650; letter-spacing: -0.03em; color: #f7f3e7; }
.hero-stat strong.hot { color: #f0b6a6; }
.hero-stat .m { font-size: 11.5px; color: #8ba79d; }

@media (max-width: 980px) {
  .hero { grid-template-columns: 1fr; padding: 24px 20px 0; }
  .hero-flow { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hero-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0 -20px; }
  .hero-stat { padding: 16px 20px 18px; }
}

.risk-bars { display: flex; flex-direction: column; gap: 12px; }
.risk-row { display: grid; grid-template-columns: 76px 1fr 58px; align-items: center; gap: 12px; }
.risk-count { text-align: right; color: var(--ink-soft); font-size: 13px; }
.track { height: 9px; background: #efe7d8; border-radius: 999px; overflow: hidden; }
.track.small { height: 6px; width: 92px; }
.fill { height: 100%; border-radius: 999px; background: var(--primary); transition: width 0.4s ease; }
.fill.danger { background: #b6402f; }
.fill.warn { background: #b3822f; }
.fill.ok { background: #1d6b57; }

.checklist { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 14px; }
.checklist li { display: flex; gap: 12px; }
.checklist .idx {
  width: 22px; height: 22px; flex: none; border-radius: 7px;
  background: var(--primary-soft); color: var(--primary-deep);
  font-size: 12px; font-weight: 600; display: grid; place-items: center;
}
.checklist p { margin-top: 2px; line-height: 1.6; }

.clickable { cursor: pointer; }
.who-cell { display: flex; flex-direction: column; gap: 1px; }
.who-name { font-weight: 500; }
.compliance { display: flex; align-items: center; gap: 10px; }
.right { text-align: right; }
.link { color: var(--primary); font-size: 13px; }
</style>
