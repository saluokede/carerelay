<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getElder, listReports, outcomeReport, weeklyReport, type Elder, type Report } from '@/api'
import { formatDate, formatDateTime, renderMarkdown, reportTypeLabel } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const elderId = computed(() => Number(route.params.id))

const elder = ref<Elder | null>(null)
const reports = ref<Report[]>([])
const selected = ref<Report | null>(null)
const auth = useAuthStore()
// 医生默认看临床版，护士默认看家属版：同一份数据，两种语言
const tab = ref<'family' | 'clinical'>(auth.isDoctor ? 'clinical' : 'family')
const tabPicked = ref(false)

/**
 * 身份是异步回填的：刷新页面时 auth.user 往往还是 null，
 * 医生会先看到家属版。等身份到了再定默认版本——只要用户没自己点过就不覆盖。
 */
watch(
  () => auth.user,
  () => {
    if (!tabPicked.value && auth.isDoctor) {
      tab.value = 'clinical'
    }
  },
  { immediate: true },
)

function pickTab(next: 'family' | 'clinical') {
  tab.value = next
  tabPicked.value = true
}
const loading = ref(true)
const busy = ref('')
const error = ref('')

async function load() {
  loading.value = true
  try {
    const [e, list] = await Promise.all([getElder(elderId.value), listReports(elderId.value)])
    elder.value = e
    reports.value = list
    selected.value = list[0] ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function generate(kind: 'weekly' | 'outcome') {
  busy.value = kind
  error.value = ''
  try {
    if (kind === 'weekly') {
      const report = await weeklyReport({ elderId: elderId.value })
      reports.value = await listReports(elderId.value)
      selected.value = reports.value.find((r) => r.id === report.id) ?? report
      tab.value = 'family'
    } else {
      const res = await outcomeReport(elderId.value)
      const markdown = typeof res === 'string' ? res : (res.markdown ?? res.content ?? '')
      const today = new Date().toISOString().slice(0, 10)
      selected.value = {
        id: -1,
        elderId: elderId.value,
        type: 'CLOSURE',
        periodStart: today,
        periodEnd: today,
        clinicalMd: markdown,
        familyMd: markdown,
      }
      tab.value = 'clinical'
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成失败'
  } finally {
    busy.value = ''
  }
}

const clinicalHtml = computed(() => renderMarkdown(selected.value?.clinicalMd ?? ''))
const familyParagraphs = computed(() =>
  (selected.value?.familyMd ?? '')
    .split(/\n+/)
    .map((p) => p.trim())
    .filter(Boolean),
)

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <div v-if="loading" class="empty"><span class="spin" /> 正在读取复盘记录…</div>
    <template v-else-if="elder">
      <header class="page-head">
        <div>
          <div class="kicker">REVIEW · 复盘环</div>
          <h1>复盘 · {{ elder.name }}</h1>
          <p class="sub">同一份数据写两版：给家属的白话版（3–4 句），给家庭医生的临床版（四段 markdown）。数字全部来自数据库，模型只负责组织语言。</p>
        </div>
        <div class="actions">
          <button class="btn" @click="router.push({ name: 'elder-detail', params: { id: elderId } })">返回老人</button>
          <button class="btn primary" :disabled="busy === 'weekly'" @click="generate('weekly')">
            {{ busy === 'weekly' ? '生成中…' : '生成本周复盘' }}
          </button>
        </div>
      </header>

      <p v-if="error" class="note danger">{{ error }}</p>

      <div class="layout">
        <aside class="card list">
          <div class="card-head"><h3>报告历史</h3><span class="hint">{{ reports.length }} 份</span></div>
          <ul class="report-list">
            <li
              v-for="r in reports"
              :key="r.id"
              :class="{ on: r.id === selected?.id }"
              @click="selected = r"
            >
              <div class="row" style="gap: 6px">
                <span class="pill" :class="r.type === 'CLOSURE' ? 'warn' : 'line'">{{ reportTypeLabel(r.type) }}</span>
                <span class="muted small">{{ formatDate(r.periodStart) }}{{ r.type === 'WEEKLY' ? ' – ' + formatDate(r.periodEnd) : '' }}</span>
              </div>
              <div class="muted small">{{ formatDateTime(r.createdAt) }}</div>
            </li>
            <li v-if="!reports.length" class="muted">还没有报告。点右上角生成本周复盘。</li>
          </ul>
          <button class="btn block" style="margin-top: 10px" :disabled="busy === 'outcome'" @click="generate('outcome')">
            {{ busy === 'outcome' ? '生成中…' : '生成 30 天结局' }}
          </button>
        </aside>

        <section v-if="selected" class="card report">
          <div class="card-head">
            <h3>{{ reportTypeLabel(selected.type) }} · {{ formatDate(selected.periodStart) }}</h3>
            <div class="tabs">
              <button class="tab" :class="{ on: tab === 'family' }" @click="pickTab('family')">家属版</button>
              <button class="tab" :class="{ on: tab === 'clinical' }" @click="pickTab('clinical')">临床版</button>
            </div>
          </div>

          <div v-if="tab === 'family'" class="family">
            <p v-for="(p, i) in familyParagraphs" :key="i">{{ p }}</p>
            <p v-if="!familyParagraphs.length" class="muted">这份报告没有家属版正文。</p>
          </div>
          <article v-else class="md" v-html="clinicalHtml" />
        </section>
        <p v-else class="empty">左侧还没有报告</p>
      </div>
    </template>
    <p v-else class="empty">{{ error || '没有找到这位老人' }}</p>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 16px; align-items: start; }
.list { padding: 16px; }
.report-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.report-list li { padding: 10px; border-radius: 10px; cursor: pointer; display: flex; flex-direction: column; gap: 3px; border: 1px solid transparent; }
.report-list li:hover { background: #f8fbf9; }
.report-list li.on { background: var(--primary-soft); border-color: #cfe3dd; }

.tabs { display: flex; gap: 4px; background: #f1f5f2; padding: 3px; border-radius: 9px; }
.tab { border: 0; background: transparent; padding: 5px 12px; border-radius: 7px; font-size: 12.5px; cursor: pointer; color: var(--ink-soft); }
.tab.on { background: #fff; color: var(--primary-deep); box-shadow: 0 1px 2px rgba(16, 40, 34, 0.08); font-weight: 500; }

.family { max-width: 720px; font-size: 16px; line-height: 2; color: #2a3a33; }
.family p { margin: 0 0 12px; }

@media (max-width: 960px) {
  .layout { grid-template-columns: 1fr; }
}
</style>
