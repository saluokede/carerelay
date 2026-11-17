<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  finishFollowup,
  getElder,
  listFollowups,
  scheduleFollowup,
  sendFollowup,
  startFollowup,
  type Elder,
  type FollowupSession,
} from '@/api'
import { channelLabel, formatDate, formatDateTime, followupStatusLabel, timeAgo } from '@/utils/format'

interface Turn {
  role: string
  text: string
  at?: string
}

const route = useRoute()
const router = useRouter()
const elderId = computed(() => Number(route.params.id))

const elder = ref<Elder | null>(null)
const sessions = ref<FollowupSession[]>([])
const activeId = ref<number | null>(null)
const turns = ref<Turn[]>([])
const input = ref('')
const loading = ref(true)
const busy = ref('')
const error = ref('')
const finished = ref<{ redFlag: boolean; summary: string } | null>(null)
const chatBox = ref<HTMLElement | null>(null)

const active = computed(() => sessions.value.find((s) => s.id === activeId.value) ?? null)
const canChat = computed(() => active.value?.status === 'PLANNED' && turns.value.length > 0)

interface RawTurn {
  role?: string
  text?: string
  content?: string
  at?: string
}

/** 后端每轮存的是 {at, role:"assistant"|"elder", content}，这里统一成 {role,text,at} */
function toTurn(raw: RawTurn): Turn {
  return {
    // 后端存的是 'assistant' | 'user'（早期版本存 'elder'），两种都要认
    role: raw.role === 'elder' || raw.role === 'user' ? 'elder' : 'agent',
    text: raw.text ?? raw.content ?? '',
    at: raw.at,
  }
}

/** 后端可能返回 transcript 数组或 transcriptJson 字符串，这里统一成数组 */
function normalizeTurns(session: FollowupSession & { transcriptJson?: string }): Turn[] {
  const fromArray = Array.isArray(session.transcript) ? (session.transcript as RawTurn[]) : null
  if (fromArray) {
    return fromArray.map(toTurn)
  }
  const raw = session.transcriptJson
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? (parsed as RawTurn[]).map(toTurn) : []
    } catch {
      return []
    }
  }
  return []
}

async function load() {
  loading.value = true
  try {
    const [e, list] = await Promise.all([getElder(elderId.value), listFollowups(elderId.value)])
    elder.value = e
    sessions.value = list
    const open = list.find((s) => s.status === 'PLANNED') ?? list[0]
    if (open) {
      select(open)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function select(session: FollowupSession) {
  activeId.value = session.id
  turns.value = normalizeTurns(session as FollowupSession & { transcriptJson?: string })
  finished.value = null
  scrollToBottom()
}

async function scrollToBottom() {
  await nextTick()
  if (chatBox.value) {
    chatBox.value.scrollTop = chatBox.value.scrollHeight
  }
}

async function addSession() {
  busy.value = 'schedule'
  try {
    const created = await scheduleFollowup({ elderId: elderId.value })
    sessions.value = await listFollowups(elderId.value)
    const full = sessions.value.find((s) => s.id === created.id) ?? created
    select(full)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '排期失败'
  } finally {
    busy.value = ''
  }
}

async function start() {
  if (!active.value) {
    return
  }
  busy.value = 'start'
  error.value = ''
  try {
    const session = await startFollowup(active.value.id)
    turns.value = normalizeTurns(session as FollowupSession & { transcriptJson?: string })
    if (!turns.value.length) {
      turns.value = [{ role: 'agent', text: '您好，我是社区护士小周。今天想跟您聊一分钟，看看回家后恢复得怎么样。' }]
    }
    sessions.value = await listFollowups(elderId.value)
    scrollToBottom()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '开始失败'
  } finally {
    busy.value = ''
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || !active.value) {
    return
  }
  turns.value.push({ role: 'elder', text })
  input.value = ''
  busy.value = 'send'
  scrollToBottom()
  try {
    const session = await sendFollowup(active.value.id, text)
    if (session.reply) {
      turns.value.push({ role: 'agent', text: session.reply })
    } else {
      const latest = normalizeTurns(session as FollowupSession & { transcriptJson?: string })
      if (latest.length > turns.value.length) {
        turns.value = latest
      }
    }
    sessions.value = await listFollowups(elderId.value)
    scrollToBottom()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发送失败'
  } finally {
    busy.value = ''
  }
}

async function finish() {
  if (!active.value) {
    return
  }
  busy.value = 'finish'
  try {
    const result = await finishFollowup(active.value.id)
    finished.value = { redFlag: result.redFlag, summary: result.summary }
    sessions.value = await listFollowups(elderId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '结束失败'
  } finally {
    busy.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <div v-if="loading" class="empty"><span class="spin" /> 正在读取随访记录…</div>
    <template v-else-if="elder">
      <header class="page-head">
        <div>
          <div class="kicker">FOLLOW-UP · 随访环</div>
          <h1>随访 · {{ elder.name }}</h1>
          <p class="sub">AI 按六个固定问题顺序提问，家属或老人回答；六问问完自动整理成结构化记录，命中红线直接升级为工单。</p>
        </div>
        <div class="actions">
          <button class="btn" @click="router.push({ name: 'elder-detail', params: { id: elderId } })">返回老人</button>
          <button class="btn primary" :disabled="busy === 'schedule'" @click="addSession">
            {{ busy === 'schedule' ? '排期中…' : '＋ 排一次随访' }}
          </button>
        </div>
      </header>

      <p v-if="error" class="note danger">{{ error }}</p>

      <div class="layout">
        <aside class="card sessions">
          <div class="card-head"><h3>随访记录</h3><span class="hint">{{ sessions.length }} 次</span></div>
          <ul class="session-list">
            <li
              v-for="s in sessions"
              :key="s.id"
              :class="{ on: s.id === activeId }"
              @click="select(s)"
            >
              <div class="row" style="gap: 6px">
                <span class="pill" :class="s.status === 'PLANNED' ? 'warn' : s.status === 'DONE' ? '' : 'quiet'">
                  {{ followupStatusLabel(s.status) }}
                </span>
                <span class="muted small">{{ formatDate(s.plannedDate) }}</span>
              </div>
              <div class="muted small">{{ channelLabel(s.channel) }}<template v-if="s.endedAt"> · {{ timeAgo(s.endedAt) }}</template></div>
            </li>
            <li v-if="!sessions.length" class="muted">还没有随访。点右上角排一次。</li>
          </ul>
        </aside>

        <section class="card chat">
          <div class="card-head">
            <h3>{{ active ? `会话 #${active.id}` : '选择一个会话' }}</h3>
            <div class="row">
              <span v-if="active" class="pill line">{{ channelLabel(active.channel) }}</span>
              <button
                v-if="active && active.status === 'PLANNED'"
                class="btn sm"
                :disabled="busy === 'start'"
                @click="start"
              >
                {{ busy === 'start' ? '接通中…' : turns.length ? '重新开始' : '开始随访' }}
              </button>
              <button
                v-if="active && active.status === 'PLANNED' && turns.length"
                class="btn sm primary"
                :disabled="busy === 'finish'"
                @click="finish"
              >
                {{ busy === 'finish' ? '整理中…' : '结束并生成记录' }}
              </button>
            </div>
          </div>

          <div ref="chatBox" class="thread">
            <div v-for="(t, i) in turns" :key="i" class="bubble" :class="t.role">
              <div class="who">{{ t.role === 'agent' ? '随访助手' : '老人 / 家属' }}</div>
              <div class="text">{{ t.text }}</div>
            </div>
            <div v-if="!turns.length" class="empty">
              点"开始随访"后，助手会先打招呼，再按六个问题一个一个问。
            </div>
            <div v-if="busy === 'send'" class="bubble agent typing"><span class="dot" /><span class="dot" /><span class="dot" /></div>
          </div>

          <div v-if="canChat" class="composer">
            <input
              v-model="input"
              class="input"
              placeholder="用老人或家属的原话回答，回车发送"
              :disabled="busy === 'send'"
              @keyup.enter="send"
            />
            <button class="btn primary" :disabled="!input.trim() || busy === 'send'" @click="send">发送</button>
          </div>

          <div v-if="finished" class="note" :class="finished.redFlag ? 'danger' : ''" style="margin-top: 12px">
            <strong>{{ finished.redFlag ? '命中红线，已生成预警与工单' : '本次随访未见红线' }}</strong>
            <p style="margin-top: 5px">{{ finished.summary }}</p>
            <button class="btn sm" style="margin-top: 8px" @click="router.push({ name: 'alerts' })">去处理预警</button>
          </div>
        </section>
      </div>
    </template>
    <p v-else class="empty">{{ error || '没有找到这位老人' }}</p>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 16px; align-items: start; }
.sessions { padding: 16px; }
.session-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.session-list li {
  padding: 10px; border-radius: 10px; cursor: pointer;
  display: flex; flex-direction: column; gap: 3px;
  border: 1px solid transparent;
}
.session-list li:hover { background: #f8fbf9; }
.session-list li.on { background: var(--primary-soft); border-color: #cfe3dd; }

.chat { display: flex; flex-direction: column; min-height: 560px; }
.thread {
  flex: 1; overflow-y: auto; max-height: 480px;
  display: flex; flex-direction: column; gap: 12px; padding: 4px 2px 12px;
}
.bubble { max-width: 78%; display: flex; flex-direction: column; gap: 3px; }
.bubble .who { font-size: 11.5px; color: var(--ink-mute); }
.bubble .text {
  padding: 10px 14px; border-radius: 12px; font-size: 14px; line-height: 1.75;
  background: #f1f5f2; color: var(--ink);
  white-space: pre-wrap;
}
.bubble.agent .text { background: var(--primary-soft); color: var(--primary-deep); border-top-left-radius: 4px; }
.bubble.elder { align-self: flex-end; align-items: flex-end; }
.bubble.elder .text { background: #fff; border: 1px solid var(--line); border-top-right-radius: 4px; }
.bubble.elder .who { text-align: right; }
.bubble.typing .text { display: flex; gap: 4px; align-items: center; }
.bubble.typing .dot { animation: blink 1s infinite; background: var(--primary); }

@keyframes blink { 0%, 100% { opacity: 0.25; } 50% { opacity: 1; } }

.composer { display: flex; gap: 10px; padding-top: 12px; border-top: 1px solid var(--line); }

@media (max-width: 960px) {
  .layout { grid-template-columns: 1fr; }
}
</style>
