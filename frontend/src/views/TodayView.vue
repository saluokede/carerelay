<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getElder, submitCheckin, todayTasks, type CheckinResult, type Elder, type TodayTask, type TodayView } from '@/api'
import { formatFriendlyDate, percentText, taskTypeLabel, timeSlotLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const elderId = computed(() => Number(route.params.id))

const elder = ref<Elder | null>(null)
const view = ref<TodayView | null>(null)
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const activeTask = ref<TodayTask | null>(null)
const lastResult = ref<(CheckinResult & { taskTitle?: string }) | null>(null)
const toast = ref('')

const form = reactive({
  systolic: '',
  diastolic: '',
  glucose: '',
  temperature: '',
  spo2: '',
  text: '',
})

const slots = ['MORNING', 'NOON', 'EVENING', 'ANYTIME']

const grouped = computed(() =>
  slots
    .map((slot) => ({ slot, tasks: (view.value?.tasks ?? []).filter((t) => t.timeSlot === slot) }))
    .filter((g) => g.tasks.length > 0),
)

const progress = computed(() => {
  const total = view.value?.totalTasks ?? 0
  const done = view.value?.doneTasks ?? 0
  return { total, done, pct: total ? Math.round((done / total) * 100) : 0 }
})

/** 任务标题决定打卡方式：血压要两格，血糖/体温/血氧一格，其他是"完成/不舒服" */
function kindOf(task: TodayTask): 'BP' | 'GLU' | 'TEMP' | 'SPO2' | 'NONE' {
  if (task.type !== 'VITAL') {
    return 'NONE'
  }
  if (task.title.includes('血压')) return 'BP'
  if (task.title.includes('血糖')) return 'GLU'
  if (task.title.includes('体温')) return 'TEMP'
  if (task.title.includes('血氧')) return 'SPO2'
  return 'NONE'
}

function openTask(task: TodayTask) {
  activeTask.value = task
  lastResult.value = null
  form.systolic = ''
  form.diastolic = ''
  form.glucose = ''
  form.temperature = ''
  form.spo2 = ''
  form.text = ''
}

async function load() {
  loading.value = true
  try {
    const [e, t] = await Promise.all([getElder(elderId.value), todayTasks(elderId.value)])
    elder.value = e
    view.value = t
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function submit(mode: 'done' | 'abnormal') {
  const task = activeTask.value
  if (!task) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const kind = kindOf(task)
    let valueJson: string | undefined
    if (kind === 'BP') {
      valueJson = JSON.stringify({ kind: 'BP', systolic: Number(form.systolic), diastolic: Number(form.diastolic) })
    } else if (kind === 'GLU') {
      valueJson = JSON.stringify({ kind: 'GLU', value: Number(form.glucose) })
    } else if (kind === 'TEMP') {
      valueJson = JSON.stringify({ kind: 'TEMP', value: Number(form.temperature) })
    } else if (kind === 'SPO2') {
      valueJson = JSON.stringify({ kind: 'SPO2', value: Number(form.spo2) })
    }

    const result = await submitCheckin({
      elderId: elderId.value,
      taskId: task.taskId,
      kind: kind === 'NONE' ? undefined : kind,
      valueJson,
      text: form.text.trim() || (mode === 'done' && kind === 'NONE' ? '已完成，无不适' : undefined),
    })
    lastResult.value = { ...result, taskTitle: task.title }
    toast.value = result.status === 'NORMAL' ? '已记录' : '已记录，并已通知护士'
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <div v-if="loading" class="empty"><span class="spin" /> 正在读取今天的任务…</div>
    <template v-else-if="elder && view">
      <header class="page-head">
        <div>
          <div class="kicker">DAILY CARE · 执行环</div>
          <h1>{{ elder.name }} · 今天要做什么</h1>
          <p class="sub">{{ formatFriendlyDate(view.date) }} · 共 {{ progress.total }} 项，已完成 {{ progress.done }} 项</p>
        </div>
        <div class="actions">
          <button class="btn" @click="router.push({ name: 'elder-detail', params: { id: elderId } })">返回老人</button>
          <button class="btn" @click="load">刷新</button>
        </div>
      </header>

      <p v-if="error" class="note danger">{{ error }}</p>
      <p v-if="toast && !lastResult" class="note">{{ toast }}</p>

      <section class="card progress-card">
        <div class="row">
          <div>
            <div class="kicker">今日完成度</div>
            <div class="big num">{{ progress.pct }}<span class="pct">%</span></div>
          </div>
          <div class="spacer" />
          <div class="bar-wrap">
            <div class="bar"><div class="bar-fill" :style="{ width: `${progress.pct}%` }" /></div>
            <div class="muted small">{{ progress.done }} / {{ progress.total }} 项</div>
          </div>
        </div>
      </section>

      <div class="layout">
        <div class="stack">
          <section v-for="g in grouped" :key="g.slot" class="card">
            <div class="card-head">
              <h3>{{ timeSlotLabel(g.slot) }}</h3>
              <span class="hint">{{ g.tasks.filter((t) => t.checked).length }} / {{ g.tasks.length }} 已完成</span>
            </div>
            <ul class="tasks">
              <li v-for="t in g.tasks" :key="t.taskId" :class="{ done: t.checked }">
                <div class="task-main">
                  <div class="t-title">
                    {{ t.title }}
                    <span class="pill line">{{ taskTypeLabel(t.type) }}</span>
                  </div>
                  <div class="muted small">{{ t.detail || '按医嘱执行' }}</div>
                </div>
                <span v-if="t.checked" class="pill">已完成</span>
                <button v-else class="btn sm" @click="openTask(t)">打卡</button>
              </li>
              <li v-if="!g.tasks.length" class="muted">此时段没有任务</li>
            </ul>
          </section>

          <p v-if="!grouped.length" class="empty">今天没有下发任务。可能是计划还没签发，或今天不在计划范围内。</p>
        </div>

        <aside class="stack">
          <section v-if="activeTask" class="card faded-in">
            <div class="card-head">
              <h3>打卡 · {{ activeTask.title }}</h3>
              <button class="btn sm ghost" @click="activeTask = null">收起</button>
            </div>
            <p class="muted small" style="margin-bottom: 12px">{{ activeTask.detail || '按医嘱执行后打卡' }}</p>

            <div v-if="kindOf(activeTask) === 'BP'" class="grid cols-2">
              <div class="field">
                <label>收缩压（mmHg）</label>
                <input v-model="form.systolic" class="input" inputmode="numeric" placeholder="如 138" />
              </div>
              <div class="field">
                <label>舒张压（mmHg）</label>
                <input v-model="form.diastolic" class="input" inputmode="numeric" placeholder="如 86" />
              </div>
            </div>
            <div v-else-if="kindOf(activeTask) === 'GLU'" class="field">
              <label>血糖（mmol/L）</label>
              <input v-model="form.glucose" class="input" inputmode="decimal" placeholder="如 6.4" />
            </div>
            <div v-else-if="kindOf(activeTask) === 'TEMP'" class="field">
              <label>体温（℃）</label>
              <input v-model="form.temperature" class="input" inputmode="decimal" placeholder="如 36.8" />
            </div>
            <div v-else-if="kindOf(activeTask) === 'SPO2'" class="field">
              <label>血氧（%）</label>
              <input v-model="form.spo2" class="input" inputmode="numeric" placeholder="如 96" />
            </div>

            <div class="field" :style="{ marginTop: kindOf(activeTask) === 'NONE' ? '0' : '12px' }">
              <label>补充说明（可选）</label>
              <textarea
                v-model="form.text"
                class="input"
                rows="3"
                placeholder="例如：今天胸口有点闷，还出了很多汗；或：药按时吃了，没什么不舒服"
                style="min-height: 84px; resize: vertical"
              />
            </div>

            <div class="row" style="margin-top: 14px">
              <button class="btn primary" :disabled="busy" @click="submit('done')">已完成，记录</button>
              <button class="btn danger" :disabled="busy" @click="submit('abnormal')">有异常，报给护士</button>
            </div>

            <div v-if="lastResult" class="note" :class="lastResult.status === 'ABNORMAL' ? 'danger' : ''" style="margin-top: 14px">
              <strong>{{ lastResult.status === 'NORMAL' ? '已记录' : '已标记异常' }}</strong>
              · {{ lastResult.judgeSource === 'MODEL' ? '模型判读' : lastResult.judgeSource === 'RULE' ? '规则判读' : '记录' }}
              <p style="margin-top: 4px">{{ lastResult.note }}</p>
              <p v-if="lastResult.alertId" class="small" style="margin-top: 4px">
                已生成预警 #{{ lastResult.alertId }}{{ lastResult.ticketId ? `，并升级为工单 #${lastResult.ticketId}` : '' }}。
              </p>
            </div>
          </section>

          <section v-else class="card">
            <div class="card-head"><h3>怎么用这一页</h3></div>
            <ul class="tips">
              <li>数值型任务（血压/血糖/体温/血氧）由代码判读阈值，结果可复算。</li>
              <li>文字描述交给模型，识别"胸闷、说不出话、摔倒"这类红旗。</li>
              <li>照片类任务先排队，护士目视后补判读，系统不会假装看懂了。</li>
            </ul>
          </section>

          <section class="card">
            <div class="card-head"><h3>其他入口</h3></div>
            <div class="stack">
              <button class="btn block" @click="router.push({ name: 'followup', params: { id: elderId } })">发起随访</button>
              <button class="btn block" @click="router.push({ name: 'report', params: { id: elderId } })">看这周复盘</button>
            </div>
          </section>
        </aside>
      </div>
    </template>
    <p v-else class="empty">{{ error || '没有找到这位老人' }}</p>
  </div>
</template>

<style scoped>
.progress-card { margin-bottom: 16px; }
.big { font-size: 38px; font-weight: 600; letter-spacing: -0.03em; line-height: 1.1; color: var(--primary-deep); }
.pct { font-size: 18px; margin-left: 2px; }
.bar-wrap { width: min(360px, 60%); display: flex; flex-direction: column; gap: 6px; }
.bar { height: 10px; background: #e9efec; border-radius: 999px; overflow: hidden; }
.bar-fill { height: 100%; background: linear-gradient(90deg, #14705f, #2f9a7e); border-radius: 999px; transition: width 0.4s ease; }

.layout { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 16px; align-items: start; }

.tasks { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.tasks li {
  display: flex; align-items: center; gap: 14px;
  padding: 12px 2px; border-bottom: 1px solid #eef2ef;
}
.tasks li:last-child { border-bottom: none; }
.tasks li.done .t-title { color: var(--ink-mute); text-decoration: line-through; text-decoration-thickness: 1px; }
.task-main { flex: 1; min-width: 0; }
.t-title { display: flex; align-items: center; gap: 8px; font-size: 14.5px; font-weight: 500; flex-wrap: wrap; }

.tips { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 9px; font-size: 13.5px; color: var(--ink-soft); }
.tips li { padding-left: 14px; position: relative; }
.tips li::before { content: ''; position: absolute; left: 0; top: 8px; width: 5px; height: 5px; border-radius: 50%; background: var(--primary); }

.faded-in { animation: fade-up 0.2s ease both; }

@media (max-width: 1000px) {
  .layout { grid-template-columns: 1fr; }
  .bar-wrap { width: 100%; }
}
</style>
