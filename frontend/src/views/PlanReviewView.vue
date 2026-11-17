<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { addPlanComment, getPlan, issuePlan, listPlanComments, listTasks, type CarePlan, type CareTask, type PlanComment } from '@/api'
import { channelLabel, formatDate, formatDateTime, planStatusLabel, roleLabel, taskTypeLabel, timeSlotLabel, TASK_TYPE_LABELS } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const elderId = computed(() => Number(route.params.id))
const planId = computed(() => Number(route.params.planId))

const plan = ref<CarePlan | null>(null)
const tasks = ref<CareTask[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const toast = ref('')
const comments = ref<PlanComment[]>([])
const commentDraft = ref('')
const commentBusy = ref(false)

const grouped = computed(() => {
  const map = new Map<string, CareTask[]>()
  for (const t of tasks.value) {
    const list = map.get(t.type) ?? []
    list.push(t)
    map.set(t.type, list)
  }
  const order = Object.keys(TASK_TYPE_LABELS)
  return [...map.entries()].sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
})

const firstDay = computed(() => tasks.value.slice(0, 10))

async function load() {
  loading.value = true
  try {
    const [p, t, c] = await Promise.all([getPlan(planId.value), listTasks(planId.value), listPlanComments(planId.value)])
    plan.value = p
    tasks.value = t
    comments.value = c
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function sign() {
  if (!plan.value) {
    return
  }
  busy.value = true
  try {
    plan.value = await issuePlan(plan.value.id)
    toast.value = '已签发。任务从今天起出现在家属端的“今天要做什么”。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '签发失败'
  } finally {
    busy.value = false
  }
}

async function submitComment() {
  if (!commentDraft.value.trim()) return
  commentBusy.value = true
  try {
    const created = await addPlanComment(planId.value, commentDraft.value.trim())
    comments.value = [...comments.value, created]
    commentDraft.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '留言失败'
  } finally {
    commentBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <div v-if="loading" class="empty"><span class="spin" /> 正在读取计划…</div>
    <template v-else-if="plan">
      <header class="page-head">
        <div>
          <div class="kicker">PLAN REVIEW · 交接环第三步</div>
          <h1>30 天照护计划 · v{{ plan.version }}</h1>
          <p class="sub">
            {{ formatDate(plan.startDate) }} 至 {{ formatDate(plan.endDate) }} ·
            <span class="pill" :class="plan.status === 'ISSUED' ? '' : 'warn'">{{ planStatusLabel(plan.status) }}</span>
            <template v-if="plan.issuedAt"> · 签发于 {{ formatDateTime(plan.issuedAt) }}</template>
          </p>
        </div>
        <div class="actions">
          <button class="btn" @click="router.push({ name: 'elder-detail', params: { id: elderId } })">返回老人</button>
          <button v-if="plan.status === 'DRAFT'" class="btn primary" :disabled="busy" @click="sign">
            {{ busy ? '签发中…' : '护士签发计划' }}
          </button>
        </div>
      </header>

      <p v-if="error" class="note danger">{{ error }}</p>
      <p v-if="toast" class="note">{{ toast }}</p>

      <div class="note" style="margin-bottom: 16px">
        AI 生成的是草案：目标、红线、随访间隔由模型组织语言；<strong>具体任务由代码按出院带药用逐条展开</strong>，
        模型无法增加或删减任何一种药。签发这一步只能由护士完成。
      </div>

      <div class="grid cols-2">
        <div class="stack">
          <section class="card">
            <div class="card-head"><h3>这 30 天要做到的事</h3></div>
            <ol class="goals">
              <li v-for="(g, i) in plan.goals" :key="i">
                <div class="g-title">{{ g.title }}</div>
                <div class="muted small">{{ g.metric || '衡量方式待补' }}</div>
              </li>
            </ol>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>红线症状（家属要背下来）</h3>
              <span class="hint">出现任意一条，立即联系社区或 120</span>
            </div>
            <ul class="flags">
              <li v-for="(f, i) in plan.redFlags" :key="i">{{ f }}</li>
            </ul>
          </section>

          <section class="card">
            <div class="card-head"><h3>AI 写给药师与家属的话</h3></div>
            <p class="summary">{{ plan.aiSummary }}</p>
            <div class="divider" />
            <div class="row">
              <span class="pill quiet">{{ channelLabel(plan.followup?.channel) }}</span>
              <span class="muted">每 {{ plan.followup?.interval_days ?? 7 }} 天一次</span>
            </div>
            <p class="muted small" style="margin-top: 6px">{{ plan.followup?.reason }}</p>
          </section>
        </div>

        <div class="stack">
          <section class="card">
            <div class="card-head">
              <h3>任务将这样展开</h3>
              <span class="hint">共 {{ tasks.length }} 条 · 前 7 天</span>
            </div>
            <div v-for="[type, list] in grouped" :key="type" class="group">
              <div class="group-head">
                <span class="pill line">{{ taskTypeLabel(type) }}</span>
                <span class="muted">{{ list.length }} 条</span>
              </div>
              <ul class="task-list">
                <li v-for="t in list.slice(0, 4)" :key="t.id">
                  <strong>{{ t.title }}</strong>
                  <span class="muted small"> · {{ timeSlotLabel(t.timeSlot) }}{{ t.detail ? ` · ${t.detail}` : '' }}</span>
                </li>
                <li v-if="list.length > 4" class="muted small">…还有 {{ list.length - 4 }} 条</li>
              </ul>
            </div>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>第一天长这样</h3>
              <span class="hint">签发后家属端看到的就是这些</span>
            </div>
            <ul class="day-list">
              <li v-for="t in firstDay" :key="t.id">
                <span class="check" />
                <div>
                  <div>{{ t.title }}</div>
                  <div class="muted small">{{ timeSlotLabel(t.timeSlot) }} · {{ taskTypeLabel(t.type) }}</div>
                </div>
              </li>
              <li v-if="!firstDay.length" class="muted">暂无任务</li>
            </ul>
          </section>
          <section class="card">
            <div class="card-head">
              <h3>会诊意见</h3>
              <span class="hint">医生不能签发，但可以在这里提出调整建议</span>
            </div>
            <ul v-if="comments.length" class="comments">
              <li v-for="c in comments" :key="c.id">
                <div class="c-head">
                  <strong>{{ c.authorName || '成员' }}</strong>
                  <span class="pill line small">{{ roleLabel(c.authorRole) }}</span>
                  <span class="muted small">{{ formatDateTime(c.createdAt) }}</span>
                </div>
                <p class="c-body">{{ c.content }}</p>
              </li>
            </ul>
            <p v-else class="empty small">还没有人会诊留言。</p>
            <div class="comment-form">
              <textarea v-model="commentDraft" class="textarea" rows="2" placeholder="例如：康复活动强度偏大，建议前两周改成每天一次。" />
              <button class="btn primary sm" :disabled="commentBusy || !commentDraft.trim()" @click="submitComment">
                {{ commentBusy ? '提交中…' : '留下意见' }}
              </button>
            </div>
          </section>
        </div>
      </div>
    </template>
    <p v-else class="empty">{{ error || '没有找到计划' }}</p>
  </div>
</template>

<style scoped>
.goals { margin: 0; padding-left: 20px; display: flex; flex-direction: column; gap: 12px; }
.g-title { font-size: 14.5px; font-weight: 500; }
.flags { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.flags li {
  padding: 9px 12px; border-radius: 10px;
  background: var(--danger-soft); color: #73271e; font-size: 13.5px;
}
.summary { font-size: 14.5px; line-height: 1.9; color: var(--ink-soft); white-space: pre-wrap; }

.group { padding: 10px 0; border-bottom: 1px dashed var(--line); }
.group:last-child { border-bottom: none; padding-bottom: 0; }
.group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.task-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; font-size: 13.5px; }

.day-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 9px; }
.day-list li { display: flex; gap: 10px; align-items: flex-start; font-size: 13.5px; }
.check { width: 16px; height: 16px; border: 1.5px solid #c6d3cd; border-radius: 5px; margin-top: 4px; flex: none; }

.comments { list-style: none; margin: 0 0 12px; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.comments li { border-left: 3px solid var(--line); padding-left: 10px; }
.c-head { display: flex; gap: 8px; align-items: center; margin-bottom: 3px; }
.c-body { margin: 0; font-size: 14px; line-height: 1.7; color: var(--ink); white-space: pre-wrap; }
.comment-form { display: flex; gap: 10px; align-items: flex-end; }
.comment-form .textarea { flex: 1; }
</style>
