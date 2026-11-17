<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { listAiRecords, type AiCallRecord } from '@/api'
import { formatDateTime } from '@/utils/format'

const records = ref<AiCallRecord[]>([])
const loading = ref(true)
const error = ref('')
const scene = ref('')

const SCENE_TEXT: Record<string, string> = {
  EXTRACT: '出院小结抽取',
  RISK_EXPLAIN: '风险解释',
  PLAN_GENERATE: '计划草案',
  CHECKIN_JUDGE: '打卡判读',
  FOLLOWUP_CHAT: '随访对话',
  FOLLOWUP_SUMMARY: '随访结构化',
  REPORT_FAMILY: '家属版周报',
  REPORT_CLINICAL: '临床版周报',
}

const sceneText = (s: string) => SCENE_TEXT[s] ?? s
const modeText = (m: string) => (m === 'MODEL' ? '真实模型' : '本地规则')

const scenes = computed(() => {
  const set = new Set(records.value.map((r) => r.scene))
  return [...set].sort()
})

const stats = computed(() => {
  const total = records.value.length
  const model = records.value.filter((r) => r.mode === 'MODEL')
  const failed = records.value.filter((r) => r.status !== 'SUCCESS').length
  const latencies = model.map((r) => r.latencyMs ?? 0).filter((n) => n > 0).sort((a, b) => a - b)
  const p50 = latencies.length ? latencies[Math.floor(latencies.length / 2)] : 0
  const avg = latencies.length ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : 0
  return { total, model: model.length, failed, p50, avg }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    records.value = await listAiRecords({ scene: scene.value || undefined, limit: 100 })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <p class="kicker">AI AUDIT · 谁调的、什么时候、用没用上</p>
        <h1>AI 调用记录</h1>
        <p class="sub">每一次模型调用都写进 <code>ai_request</code>：场景、模式、模型、耗时、成败、是否被采纳。这一页是"可追溯"的自证材料。</p>
      </div>
    </header>

    <p v-if="error" class="note danger">{{ error }}</p>

    <div class="stat-row">
      <div class="stat"><span class="num">{{ stats.total }}</span><span class="lbl">近 100 次调用</span></div>
      <div class="stat"><span class="num">{{ stats.model }}</span><span class="lbl">真实模型调用</span></div>
      <div class="stat"><span class="num">{{ stats.p50 }}<small>ms</small></span><span class="lbl">耗时中位数</span></div>
      <div class="stat"><span class="num" :class="{ bad: stats.failed > 0 }">{{ stats.failed }}</span><span class="lbl">失败</span></div>
    </div>

    <section class="card">
      <div class="card-head">
        <h2>调用明细</h2>
        <div class="filters">
          <select v-model="scene" class="select sm" @change="load">
            <option value="">全部场景</option>
            <option v-for="s in scenes" :key="s" :value="s">{{ sceneText(s) }}</option>
          </select>
          <button class="btn sm" :disabled="loading" @click="load">{{ loading ? '加载中…' : '刷新' }}</button>
        </div>
      </div>

      <p v-if="loading" class="empty">加载中…</p>
      <p v-else-if="!records.length" class="empty">还没有调用记录。配好模型 Key 后做一次抽取就会出现在这里。</p>

      <table v-else class="table">
        <thead>
          <tr>
            <th>场景</th>
            <th>模式</th>
            <th>模型</th>
            <th class="num-col">耗时</th>
            <th class="num-col">输入/输出</th>
            <th>结果</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in records" :key="r.id">
            <td><strong>{{ sceneText(r.scene) }}</strong></td>
            <td><span class="pill" :class="r.mode === 'MODEL' ? 'ok' : ''">{{ modeText(r.mode) }}</span></td>
            <td class="dim">{{ r.model || '—' }}</td>
            <td class="num-col">{{ r.latencyMs ?? 0 }} ms</td>
            <td class="num-col dim">{{ r.inputChars }} / {{ r.outputChars }} 字</td>
            <td>
              <span v-if="r.status === 'SUCCESS'" class="pill ok">成功</span>
              <span v-else class="pill danger" :title="r.error || ''">失败</span>
            </td>
            <td class="dim">{{ formatDateTime(r.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin: 18px 0 16px; }
.stat { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 16px 18px; display: flex; flex-direction: column; gap: 4px; }
.stat .num { font-size: 32px; font-weight: 700; color: var(--ink); }
.stat .num small { font-size: 14px; font-weight: 500; color: var(--ink-soft); margin-left: 2px; }
.stat .num.bad { color: var(--danger); }
.stat .lbl { font-size: 13px; color: var(--ink-soft); }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.filters { display: flex; gap: 8px; align-items: center; }
.filters .btn { white-space: nowrap; }
.num-col { text-align: right; white-space: nowrap; }
.dim { color: var(--ink-soft); }
code { background: #f0ece3; padding: 1px 5px; border-radius: 4px; font-size: 12.5px; }
@media (max-width: 980px) { .stat-row { grid-template-columns: repeat(2, 1fr); } }
</style>
