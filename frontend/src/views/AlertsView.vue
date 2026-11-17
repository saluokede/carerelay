<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { closeAlert, handleTicket, listAlerts, listElders, listTickets, type Alert, type Elder, type Ticket } from '@/api'
import { alertLevelLabel, formatDateTime, sourceLabel, ticketStatusLabel, timeAgo } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

/** 高危预警只有家庭医生能关闭：护士一线处置，判断升级交给医生 */
const canClose = (level: string) => level !== 'HIGH' || auth.isDoctor
const alerts = ref<Alert[]>([])
const tickets = ref<Ticket[]>([])
const elders = ref<Elder[]>([])
const tab = ref<'alerts' | 'tickets'>('alerts')
const onlyOpen = ref(true)
const loading = ref(true)
const error = ref('')
const busy = ref(0)
const draft = ref<Record<number, string>>({})

const elderName = (id: number) => elders.value.find((e) => e.id === id)?.name ?? `老人 #${id}`

const openAlerts = computed(() => alerts.value.filter((a) => a.status === 'OPEN'))
const shownAlerts = computed(() => (onlyOpen.value ? openAlerts.value : alerts.value))
const pendingTickets = computed(() => tickets.value.filter((t) => t.status === 'PENDING' || t.status === 'PROCESSING'))
const shownTickets = computed(() => (onlyOpen.value ? pendingTickets.value : tickets.value))

async function load() {
  loading.value = true
  try {
    const [a, t, e] = await Promise.all([listAlerts(), listTickets(), listElders()])
    alerts.value = a.sort((x, y) => (x.status === y.status ? y.id - x.id : x.status === 'OPEN' ? -1 : 1))
    tickets.value = t.sort((x, y) => y.id - x.id)
    elders.value = e
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function close(alert: Alert) {
  busy.value = alert.id
  try {
    await closeAlert(alert.id, draft.value[alert.id] || '社区护士电话核实，症状已缓解，继续观察')
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '关闭失败'
  } finally {
    busy.value = 0
  }
}

async function handle(ticket: Ticket) {
  busy.value = ticket.id
  try {
    await handleTicket(ticket.id, draft.value[ticket.id] || '已电话核实并按流程处理')
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '处置失败'
  } finally {
    busy.value = 0
  }
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <header class="page-head">
      <div>
        <div class="kicker">ALERTS & TICKETS · 有人在管</div>
        <h1>预警与工单</h1>
        <p class="sub">数值越界由代码判定，文字红旗由模型识别；高危直接生成工单，需要人处理并留痕。</p>
      </div>
      <div class="actions">
        <label class="switch">
          <input v-model="onlyOpen" type="checkbox" />
          只看未处理
        </label>
        <button class="btn" @click="load">刷新</button>
      </div>
    </header>

    <p v-if="error" class="note danger">{{ error }}</p>

    <div class="tabs">
      <button class="tab" :class="{ on: tab === 'alerts' }" @click="tab = 'alerts'">
        预警 <span class="count">{{ openAlerts.length }}</span>
      </button>
      <button class="tab" :class="{ on: tab === 'tickets' }" @click="tab = 'tickets'">
        工单 <span class="count">{{ pendingTickets.length }}</span>
      </button>
    </div>

    <div v-if="loading" class="empty"><span class="spin" /> 加载中…</div>

    <template v-else-if="tab === 'alerts'">
      <div v-if="!shownAlerts.length" class="empty">没有需要处理的预警。</div>
      <section v-for="a in shownAlerts" :key="a.id" class="card alert" :class="a.level.toLowerCase()">
        <div class="alert-head">
          <div class="row" style="gap: 8px; flex-wrap: wrap">
            <span class="pill" :class="a.level === 'HIGH' ? 'danger' : a.level === 'MEDIUM' ? 'warn' : 'quiet'">
              {{ alertLevelLabel(a.level) }}
            </span>
            <span class="pill line">{{ sourceLabel(a.source) }}</span>
            <strong>{{ a.title }}</strong>
          </div>
          <span class="muted small" :title="formatDateTime(a.createdAt)">{{ timeAgo(a.createdAt) }}</span>
        </div>
        <p class="detail">{{ a.detail }}</p>
        <div class="alert-foot">
          <button class="link" @click="router.push({ name: 'elder-detail', params: { id: a.elderId } })">{{ elderName(a.elderId) }}</button>
          <span v-if="a.ruleCode" class="mono muted small">{{ a.ruleCode }}</span>
          <div class="spacer" />
          <template v-if="a.status === 'OPEN'">
            <input v-model="draft[a.id]" class="input sm" placeholder="处置说明（可留空）" />
            <button
              v-if="canClose(a.level)"
              class="btn sm primary"
              :disabled="busy === a.id"
              @click="close(a)"
            >
              {{ busy === a.id ? '处理中…' : '已核实，关闭' }}
            </button>
            <span v-else class="pill warn" title="高危预警需要家庭医生确认后才能关闭">
              待医生确认
            </span>
          </template>
          <span v-else class="pill quiet">已关闭</span>
        </div>
      </section>
    </template>

    <template v-else>
      <div v-if="!shownTickets.length" class="empty">没有待处置的工单。</div>
      <section v-for="t in shownTickets" :key="t.id" class="card alert" :class="t.level.toLowerCase()">
        <div class="alert-head">
          <div class="row" style="gap: 8px; flex-wrap: wrap">
            <span class="pill" :class="t.status === 'DONE' ? 'quiet' : 'danger'">{{ ticketStatusLabel(t.status) }}</span>
            <span class="pill" :class="t.level === 'HIGH' ? 'danger' : 'warn'">{{ alertLevelLabel(t.level) }}</span>
            <strong>工单 #{{ t.id }}</strong>
            <button class="link" @click="router.push({ name: 'elder-detail', params: { id: t.elderId } })">{{ elderName(t.elderId) }}</button>
          </div>
          <span class="muted small">{{ formatDateTime(t.createdAt) }}</span>
        </div>
        <p class="detail">{{ t.reason }}</p>
        <p v-if="t.note" class="muted small">处置记录：{{ t.note }}</p>
        <div class="alert-foot">
          <div class="spacer" />
          <template v-if="t.status !== 'DONE'">
            <input v-model="draft[t.id]" class="input sm" placeholder="处置说明" />
            <button class="btn sm primary" :disabled="busy === t.id" @click="handle(t)">标记已处置</button>
          </template>
          <span v-else class="pill quiet">已完成</span>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 6px; margin-bottom: 14px; }
.tab {
  border: 1px solid var(--line); background: #fff; border-radius: 9px;
  padding: 7px 14px; font-size: 13px; cursor: pointer; color: var(--ink-soft);
  display: inline-flex; align-items: center; gap: 7px;
}
.tab.on { background: var(--primary); color: #fff; border-color: var(--primary); }
.count { font-size: 11px; background: rgba(255, 255, 255, 0.2); border-radius: 999px; padding: 1px 6px; }
.tab:not(.on) .count { background: #eef2ef; color: var(--ink-mute); }

.alert { margin-bottom: 12px; padding: 16px 18px; border-left: 3px solid var(--line); }
.alert.high { border-left-color: var(--danger); }
.alert.medium { border-left-color: var(--amber); }

.alert-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.detail { margin: 10px 0 0; font-size: 14px; line-height: 1.8; color: var(--ink-soft); }
.alert-foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.alert-foot .input.sm { max-width: 280px; height: 32px; font-size: 12.5px; }

.switch { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ink-soft); cursor: pointer; }
</style>
