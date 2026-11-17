<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import {
  listDischarge,
  listElders,
  listReports,
  submitCheckin,
  todayTasks,
  type DischargeRecord,
  type Elder,
  type Report,
  type TodayTask,
  type TodayView,
} from '@/api'
import { formatDate, formatFriendlyDate, timeSlotLabel } from '@/utils/format'

const elders = ref<Elder[]>([])
const code = ref('')
const elder = ref<Elder | null>(null)
const discharges = ref<DischargeRecord[]>([])
const view = ref<TodayView | null>(null)
const reports = ref<Report[]>([])
const loading = ref(false)
const error = ref('')
const feedback = ref('')
const sent = ref('')
const doneMsg = ref('')

/* ---------- 到点提醒 ----------
   家属最容易漏的是"几点该做什么"。任务只带时间段，这里把时间段换算成时刻：
   到点后页面顶部弹出提醒，浏览器允许的话再发一条系统通知。
   同一个任务同一天只提醒一次，点「10 分钟后再提醒」会把这次延后。 */
const SLOT_MINUTES: Record<string, number> = { MORNING: 8 * 60, NOON: 12 * 60, EVENING: 18 * 60 }
const SLOT_TEXT: Record<string, string> = { MORNING: '上午 8:00', NOON: '中午 12:00', EVENING: '晚上 6:00' }
const nowTs = ref(Date.now())
const notifyPermission = ref<NotificationPermission | 'unsupported'>(
  typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
)
const notified = new Set<number>()
const snoozedUntil = ref<Record<number, number>>({})
let clock = 0
let sync = 0

function minutesOfDay(ts: number): number {
  const d = new Date(ts)
  return d.getHours() * 60 + d.getMinutes()
}

const timedPending = computed(() =>
  (view.value?.tasks ?? [])
    .filter((t) => !t.checked && SLOT_MINUTES[t.timeSlot] !== undefined)
    .map((t) => ({ task: t, at: SLOT_MINUTES[t.timeSlot] })),
)

const nextUp = computed(() => [...timedPending.value].sort((a, b) => a.at - b.at)[0] ?? null)

const isDue = computed(() => {
  const n = nextUp.value
  if (!n) return false
  const snooze = snoozedUntil.value[n.task.taskId] ?? 0
  return minutesOfDay(nowTs.value) >= n.at && nowTs.value >= snooze
})

const minutesUntil = computed(() => {
  const n = nextUp.value
  if (!n) return 0
  return Math.max(n.at - minutesOfDay(nowTs.value), 0)
})

function snooze(task: TodayTask) {
  snoozedUntil.value = { ...snoozedUntil.value, [task.taskId]: Date.now() + 10 * 60 * 1000 }
  notified.delete(task.taskId)
}

async function enableNotify() {
  if (typeof Notification === 'undefined') return
  try {
    notifyPermission.value = await Notification.requestPermission()
  } catch {
    notifyPermission.value = 'denied'
  }
}

watch(isDue, (due) => {
  const n = nextUp.value
  if (!due || !n || notified.has(n.task.taskId)) return
  notified.add(n.task.taskId)
  if (notifyPermission.value === 'granted') {
    try {
      new Notification('医养接力站 · 该做了', { body: `${SLOT_TEXT[n.task.timeSlot]}：${n.task.title}` })
    } catch {
      /* 通知失败不影响页面提示 */
    }
  }
})


const latestReport = computed(() => reports.value.find((r) => r.type === 'WEEKLY') ?? null)
const daysHome = computed(() => {
  const d = discharges.value[0]?.dischargeDate
  if (!d) return null
  const diff = Math.floor((Date.now() - new Date(`${d}T00:00:00`).getTime()) / 86400000)
  return diff >= 0 ? diff + 1 : null
})

async function resolve() {
  const target = code.value.trim().toUpperCase()
  if (!target) {
    error.value = '请先输入出院卡上的编号（例如 CR-0001）'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const list = elders.value.length ? elders.value : await listElders()
    elders.value = list
    const found = list.find((e) => e.code.toUpperCase() === target || e.name === code.value.trim())
    if (!found) {
      error.value = '没找到这个编号，请核对出院时发的卡片'
      elder.value = null
      return
    }
    elder.value = found
    const [tasks, ds, rs] = await Promise.all([todayTasks(found.id), listDischarge(found.id), listReports(found.id)])
    view.value = tasks
    discharges.value = ds
    reports.value = rs
  } catch (e) {
    error.value = e instanceof Error ? e.message : '查询失败'
  } finally {
    loading.value = false
  }
}

async function markDone(task: TodayTask) {
  if (!elder.value) return
  try {
    await submitCheckin({ elderId: elder.value.id, taskId: task.taskId, text: '已完成，无不适' })
    doneMsg.value = `已记录：${task.title}`
    view.value = await todayTasks(elder.value.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '记录失败'
  }
}

async function sendFeedback() {
  if (!elder.value || !feedback.value.trim()) return
  try {
    const res = await submitCheckin({ elderId: elder.value.id, text: feedback.value.trim() })
    sent.value = res.status === 'ABNORMAL' ? '已记录，护士会尽快联系您' : '已记录，护士会看到'
    feedback.value = ''
    view.value = await todayTasks(elder.value.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发送失败'
  }
}

watch(code, (v) => {
  if (!v.trim()) {
    elder.value = null
  }
})

onMounted(async () => {
  try {
    elders.value = await listElders()
  } catch {
    /* 忽略，输入后再拉 */
  }
  // 每 20 秒走一次表：到点就把提醒顶出来；顺手每 3 分钟重拉一次任务，别让完成状态过期
  clock = window.setInterval(() => {
    nowTs.value = Date.now()
  }, 20000)
  sync = window.setInterval(async () => {
    if (!elder.value) return
    try {
      view.value = await todayTasks(elder.value.id)
    } catch {
      /* 网络抖动忽略 */
    }
  }, 180000)
})

onUnmounted(() => {
  if (clock) window.clearInterval(clock)
  if (sync) window.clearInterval(sync)
})
</script>

<template>
  <div class="family-page">
    <section v-if="!elder" class="intro card">
      <div class="kicker">FAMILY · 家属端</div>
      <h1>老人回家后，今天要做什么</h1>
      <p class="sub">
        输入出院时那张卡片上的编号（例如 CR-0001），就能看到今天的三餐用药、测量和康复任务，做完点一下就行。
      </p>
      <div class="row wrap" style="margin-top: 18px">
        <input v-model="code" class="input code" placeholder="CR-0001" @keyup.enter="resolve" />
        <button class="btn primary lg" :disabled="loading" @click="resolve">{{ loading ? '查询中…' : '查看今天的任务' }}</button>
      </div>
      <p v-if="error" class="note danger" style="margin-top: 14px">{{ error }}</p>
      <p class="muted small" style="margin-top: 14px">
        演示环境可用的编号：<span class="mono">CR-0001 王秀兰</span> · <span class="mono">CR-0002 李国强</span> ·
        <span class="mono">CR-0003 陈美英</span> · <span class="mono">CR-0004 赵德海</span>
      </p>
    </section>

    <template v-else>
      <section class="card hello">
        <div class="kicker">{{ formatFriendlyDate(view?.date ?? '') }}</div>
        <h1 class="hello-name">{{ elder.name }}，回家第 <b class="num">{{ daysHome ?? '—' }}</b> 天</h1>
        <p class="sub">
          {{ formatDate(discharges[0]?.dischargeDate) }} 出院 · 今天 {{ view?.totalTasks ?? 0 }} 项任务，已完成
          {{ view?.doneTasks ?? 0 }} 项
        </p>
        <div class="progress">
          <div class="progress-bar" :style="{ width: `${Math.round(((view?.doneTasks ?? 0) / Math.max(view?.totalTasks ?? 1, 1)) * 100)}%` }" />
        </div>
        <button class="btn sm ghost switch" @click="elder = null; code = ''; error = ''">换一位</button>
      </section>

      <p v-if="doneMsg" class="note ok">✓ {{ doneMsg }}</p>

      <!-- 到点提醒：常驻一条"下一件"，到点后变红并说明已过点 -->
            <section v-if="elder.careStatus === 'HOSPITALIZED'" class="card pause">
        <h3>老人在住院中</h3>
        <p class="muted">出院时定的任务先放一放——人在医院，这些不用家里做。等接回家、护士重新确认后，任务会自动恢复。</p>
      </section>
<section v-if="nextUp && elder.careStatus !== 'HOSPITALIZED'" class="card remind" :class="{ due: isDue }">
        <div class="remind-body">
          <div class="kicker">{{ isDue ? '现在该做了' : '下一件' }}</div>
          <h3 class="remind-title">
            <span class="remind-time">{{ SLOT_TEXT[nextUp.task.timeSlot] }}</span>
            {{ nextUp.task.title }}
          </h3>
          <p class="remind-sub">
            <template v-if="isDue">已经到点了，做完点右边「完成了」。</template>
            <template v-else-if="minutesUntil <= 60">还有 {{ minutesUntil }} 分钟。</template>
            <template v-else>离现在还有 {{ Math.floor(minutesUntil / 60) }} 小时 {{ minutesUntil % 60 }} 分。</template>
          </p>
        </div>
        <div class="remind-actions">
          <button class="btn primary" @click="markDone(nextUp.task)">完成了</button>
          <button class="btn sm ghost" @click="snooze(nextUp.task)">10 分钟后再提醒</button>
          <button v-if="notifyPermission === 'default'" class="btn sm ghost" @click="enableNotify">开启到点通知</button>
          <span v-else-if="notifyPermission === 'granted'" class="remind-on">到点通知已开</span>
        </div>
      </section>

      <section v-if="elder.careStatus !== 'HOSPITALIZED'" class="card">
        <div class="card-head"><h3>照着做</h3><span class="hint">做完点"完成了"</span></div>
        <ul class="tasks">
          <li v-for="t in view?.tasks ?? []" :key="t.taskId" :class="{ done: t.checked }">
            <div class="task-main">
              <div class="t-title">{{ t.title }}<span class="pill line">{{ timeSlotLabel(t.timeSlot) }}</span></div>
              <div class="muted small">{{ t.detail || '按医嘱执行' }}</div>
            </div>
            <span v-if="t.checked" class="pill">已完成</span>
            <button v-else class="btn sm primary" @click="markDone(t)">完成了</button>
          </li>
          <li v-if="!(view?.tasks ?? []).length" class="muted">今天暂时没有任务安排。</li>
        </ul>
      </section>

      <section class="card danger-card">
        <div class="card-head"><h3>出现这些，先打电话</h3></div>
        <ul class="alarm">
          <li>胸口闷、胸痛，或者喘不上气</li>
          <li>说话不清、一侧手脚突然没劲</li>
          <li>摔倒、出血止不住、发高烧</li>
        </ul>
        <p class="muted small">先打 120；不紧急的情况写在下面，护士会看到。</p>
      </section>

      <section class="card">
        <div class="card-head"><h3>有不舒服，记一句</h3></div>
        <p class="muted small" style="margin-bottom: 10px">
          用大白话写就行，例如"今天胸口闷，出了很多汗"。
        </p>
        <textarea v-model="feedback" class="input" rows="3" placeholder="今天的情况…" style="min-height: 90px; resize: vertical" />
        <div class="row" style="margin-top: 10px">
          <button class="btn primary" :disabled="!feedback.trim()" @click="sendFeedback">发给护士</button>
        </div>
        <p v-if="sent" class="note ok" style="margin-top: 10px">{{ sent }}</p>
      </section>

      <section v-if="latestReport" class="card">
        <div class="card-head"><h3>这周的复盘</h3><span class="hint">{{ formatDate(latestReport.periodStart) }}</span></div>
        <p class="family">{{ latestReport.familyMd }}</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* 家属端是另一套语言：陶土橙 + 更大字号 + 更少信息。
   这一屏的使用者是六十岁上下的子女，手机、光线差、手指不精准。 */
.family-page {
  --f-ink: #3b2a20;
  --f-ink-soft: #6b5546;
  --f-accent: #b8542c;
  --f-accent-soft: #fbe9dc;
  --f-green: #1d6b57;
  color: var(--f-ink);
  font-size: 16px;
}

.card {
  background: #fffdf7;
  border: 1px solid #eadbc8;
  border-radius: 20px;
  box-shadow: 0 16px 30px -26px rgba(90, 54, 30, 0.55);
  margin-bottom: 14px;
}
.card h3::before { background: var(--f-accent); }

.intro { padding: 30px 26px; }
.intro h1 {
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: 30px;
  line-height: 1.3;
  margin: 10px 0 10px;
  color: #5b3a24;
}
.intro .sub { color: var(--f-ink-soft); font-size: 15px; line-height: 1.85; max-width: 46ch; }
.intro .kicker { color: var(--f-accent); }
.code {
  max-width: 220px;
  text-transform: uppercase;
  font-family: var(--mono);
  letter-spacing: 0.1em;
  font-size: 18px;
  padding: 12px 16px;
  border-color: #e6d4bf;
}

/* 今天的"第几天"是这一屏的主角 */
.hello { position: relative; padding: 26px 26px 22px; }
.hello .kicker { color: var(--f-accent); }
.hello-name {
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: 27px;
  line-height: 1.35;
  margin: 8px 0 6px;
  color: #4a3122;
  padding-right: 76px;
}
.hello-name b { font-size: 34px; color: var(--f-accent); font-weight: 700; padding: 0 2px; }
.hello .sub { color: var(--f-ink-soft); font-size: 14px; }
.progress { height: 10px; border-radius: 999px; background: #f2e3d2; margin-top: 16px; overflow: hidden; }
.progress-bar { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #d9764a, #b8542c); transition: width 0.4s ease; }
.switch { position: absolute; top: 22px; right: 20px; border-color: #e6d4bf; color: var(--f-ink-soft); }

.tasks { list-style: none; margin: 0; padding: 0; }
.tasks li {
  display: flex; align-items: center; gap: 14px;
  padding: 15px 2px; border-bottom: 1px dashed #ecdfcd;
}
.tasks li:last-child { border-bottom: none; }
.tasks li.done .t-title { color: #a99a8a; text-decoration: line-through; }
.task-main { flex: 1; }
.t-title { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 16.5px; font-weight: 550; line-height: 1.5; }
.t-title .pill { background: #f4e6d6; color: #8a5c34; }
.tasks .btn { height: 38px; padding: 0 18px; font-size: 15px; }

/* 红旗单独一张，视觉上必须"重" */
.danger-card { background: #fdf3ec; border-color: #f0d3c2; }
.danger-card h3 { color: #8f3a1c; }
.alarm { list-style: none; margin: 0 0 10px; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.alarm li { position: relative; padding-left: 18px; font-size: 15.5px; line-height: 1.6; color: #7a3a20; }
.alarm li::before { content: ""; position: absolute; left: 2px; top: 9px; width: 7px; height: 7px; border-radius: 999px; background: var(--f-accent); }

.family { font-size: 16.5px; line-height: 2; color: #4a3a2c; white-space: pre-wrap; margin: 0; }

/* 到点提醒条：平时是浅橙的"下一件"，到点后整条加深、加左边粗线 */
.remind {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 16px 20px;
  background: #fff6ec;
  border-color: #f0d9c2;
  border-left: 6px solid #e0a06a;
}
.remind.due {
  background: #fdeee5;
  border-color: #eec9b4;
  border-left-color: var(--f-accent);
}
.remind .kicker { color: #a8622f; }
.remind.due .kicker { color: #8f3a1c; }
.remind-title { margin: 6px 0 4px; font-size: 18px; line-height: 1.45; color: #4a3122; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.remind.due .remind-title { font-size: 20px; }
.remind-time { color: var(--f-accent); font-weight: 650; margin-right: 6px; }
.remind-sub { font-size: 13.5px; color: var(--f-ink-soft); }
.remind-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: flex-end; }
.remind-on { font-size: 12.5px; color: #2c6a55; }

@media (max-width: 640px) {
  .remind { flex-direction: column; align-items: stretch; }
  .remind-actions { justify-content: flex-start; }
}

@media (max-width: 560px) {
  .hello-name { font-size: 23px; padding-right: 0; }
  .hello-name b { font-size: 29px; }
  .switch { position: static; margin-top: 12px; }
  .intro h1 { font-size: 25px; }
}

/* 家属端的主按钮用陶土橙：这是"我现在要做的事"，不该是医护端的墨绿 */
.family-page .btn.primary {
  background: var(--f-accent);
  border-color: var(--f-accent);
  color: #fffaf3;
  font-weight: 600;
}
.family-page .btn.primary:hover { background: #9e4523; border-color: #9e4523; }
.family-page .btn.primary:disabled { background: #d9b6a4; border-color: #d9b6a4; }
.family-page .input:focus,
.family-page .textarea:focus { border-color: var(--f-accent); box-shadow: 0 0 0 3px rgba(184, 84, 44, 0.14); }
.family-page .note.ok { border-color: var(--f-green); background: #eaf2ec; color: #1b4a3d; }
.family-page .progress + .switch { color: var(--f-ink-soft); }
.pause { border-left: 5px solid var(--amber); background: #fdf6e8; }
.pause h3 { margin: 0 0 6px; font-size: 17px; color: #7a4a10; }
.pause p { margin: 0; font-size: 15px; line-height: 1.7; }
</style>
