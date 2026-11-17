<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  getDischarge,
  getPlan,
  getElder,
  listDischarge,
  listPlans,
  type CarePlan,
  type DischargeData,
  type DischargeRecord,
  type Elder,
} from '@/api'
import { formatDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const elderId = Number(route.params.id)

const elder = ref<Elder | null>(null)
const record = ref<DischargeRecord | null>(null)
const plan = ref<CarePlan | null>(null)
const loading = ref(true)
const error = ref('')

const data = computed<DischargeData | null>(() => record.value?.data ?? null)

const meds = computed(() => data.value?.medications ?? [])
const redFlags = computed<Array<{ symptom?: string; action?: string }>>(() => (Array.isArray(data.value?.redFlags) ? (data.value?.redFlags as Array<{ symptom?: string; action?: string }>) : []))
const diagnoses = computed(() => {
  const list = data.value?.diagnoses
  if (!list) return []
  return Array.isArray(list) ? list : [list]
})

const followupText = computed(() => {
  const f = plan.value?.followup
  if (!f) return '按计划执行'
  const channel = f.channel === 'PHONE' ? '电话随访' : f.channel === 'HOME' ? '上门随访' : '门诊随访'
  return `${channel} · 每 ${f.interval_days ?? 7} 天一次`
})

const today = new Date()
const printDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

async function load() {
  loading.value = true
  try {
    elder.value = await getElder(elderId)
    const records = await listDischarge(elderId)
    if (records.length) {
      record.value = await getDischarge(records[0].id)
    }
    const plans = await listPlans(elderId)
    const issued = plans.find((p) => p.status === 'ISSUED') ?? plans[0]
    if (issued) {
      plan.value = await getPlan(issued.id)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

const doPrint = () => window.print()

onMounted(load)
</script>

<template>
  <div class="print-page">
    <div class="toolbar no-print">
      <button class="btn" @click="router.back()">返回</button>
      <button class="btn primary" @click="doPrint">打印 / 存成 PDF</button>
      <span class="hint">建议 A4 纵向、默认边距；页眉页脚可在打印对话框里关掉。</span>
    </div>

    <p v-if="error" class="note danger no-print">{{ error }}</p>
    <p v-if="loading" class="no-print">加载中…</p>

    <article v-if="elder" class="sheet">
      <header class="sheet-head">
        <div>
          <p class="brand">医养接力站 · 出院照护交接单</p>
          <h1>{{ elder.name }}</h1>
          <p class="meta">
            {{ elder.code }} · {{ elder.gender === 'MALE' ? '男' : '女' }} · {{ elder.age }} 岁 ·
            {{ elder.livingStatus === 'ALONE' ? '独居' : elder.livingStatus === 'SPOUSE' ? '与配偶同住' : '与子女同住' }}
          </p>
        </div>
        <div class="stamp">
          <span class="label">风险分层</span>
          <strong :class="['lv', plan ? '' : '']">{{ plan?.status === 'ISSUED' ? '已签发' : '待签发' }}</strong>
          <span class="date">打印日期 {{ printDate }}</span>
        </div>
      </header>

      <section class="grid2">
        <div>
          <h2>联系与照护</h2>
          <p><span class="k">联系电话</span>{{ elder.phone || '—' }}</p>
          <p><span class="k">住址</span>{{ elder.address || '—' }}</p>
          <p><span class="k">照护人</span>{{ elder.caregiverName || '—' }} {{ elder.caregiverPhone || '' }}</p>
          <p><span class="k">基础病</span>{{ (elder.chronic ?? []).join('、') || '—' }}</p>
          <p><span class="k">过敏</span><strong class="danger-text">{{ (elder.allergy ?? []).join('、') || '未登记' }}</strong></p>
        </div>
        <div>
          <h2>出院与随访</h2>
          <p><span class="k">出院日期</span>{{ record?.dischargeDate ? formatDate(record.dischargeDate) : '—' }}</p>
          <p><span class="k">出院医院</span>{{ record?.hospital || '—' }} {{ record?.department || '' }}</p>
          <p><span class="k">主要诊断</span>{{ record?.diagnosisPrimary || diagnoses[0] || '—' }}</p>
          <p><span class="k">随访安排</span>{{ followupText }}</p>
          <p><span class="k">复诊</span>{{ data?.followup?.date ? formatDate(data.followup.date) : '按医嘱' }} {{ data?.followup?.department || '' }}</p>
        </div>
      </section>

      <section>
        <h2>出院带药 <small>（不要自行停药或加量）</small></h2>
        <table class="sheet-table">
          <thead>
            <tr><th>药品</th><th>剂量</th><th>频次</th><th>途径</th><th>疗程</th><th>备注</th></tr>
          </thead>
          <tbody>
            <tr v-for="(m, i) in meds" :key="i">
              <td>{{ m.name }}</td>
              <td>{{ m.dose || '—' }}</td>
              <td>{{ m.frequency || '—' }}</td>
              <td>{{ m.route || '—' }}</td>
              <td>{{ m.duration || '—' }}</td>
              <td>{{ m.note || '' }}</td>
            </tr>
            <tr v-if="!meds.length"><td colspan="6" class="dim">未登记出院带药</td></tr>
          </tbody>
        </table>
      </section>

      <section class="grid2">
        <div>
          <h2>30 天目标</h2>
          <ul class="goals">
            <li v-for="(g, i) in plan?.goals ?? []" :key="i">{{ g.title }}<span v-if="g.metric" class="dim">（{{ g.metric }}）</span></li>
            <li v-if="!(plan?.goals ?? []).length" class="dim">待护士签发后生效</li>
          </ul>
        </div>
        <div class="red-box">
          <h2>出现这些，立刻就医</h2>
          <ul>
            <li v-for="(r, i) in redFlags" :key="i"><strong>{{ r.symptom }}</strong> → {{ r.action }}</li>
            <li v-if="!redFlags.length" class="dim">按医嘱执行，有不适联系社区护士</li>
          </ul>
        </div>
      </section>

      <footer class="sheet-foot">
        <p>本单由「医养接力站」生成，内容来自出院小结结构化档案与护士签发的 30 天计划；AI 只做整理与解释，剂量与阈值由系统规则判定。</p>
        <div class="sign">
          <span>社区护士签字：</span><span class="line"></span>
          <span>家属签字：</span><span class="line"></span>
        </div>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.print-page { padding: 18px 24px 60px; background: #f2ede3; min-height: 100vh; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; }
.toolbar .hint { font-size: 12.5px; color: #7a6a58; }
.sheet { background: #fff; width: 210mm; min-height: 297mm; margin: 0 auto; padding: 16mm 14mm; box-shadow: 0 20px 40px -30px rgba(0,0,0,.5); color: #1d1d1b; }
.sheet-head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 3px solid #12433a; padding-bottom: 10px; }
.brand { font-size: 12px; letter-spacing: .12em; color: #1d6b57; margin: 0 0 6px; }
.sheet h1 { font-family: var(--font-serif); font-size: 34px; margin: 0; letter-spacing: 1px; }
.meta { margin: 4px 0 0; font-size: 13px; color: #55504a; }
.stamp { text-align: right; display: flex; flex-direction: column; gap: 2px; }
.stamp .label { font-size: 11px; color: #7a6a58; letter-spacing: .1em; }
.stamp strong { font-size: 20px; color: #12433a; }
.stamp .date { font-size: 11.5px; color: #7a6a58; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 16px; }
.sheet h2 { font-size: 14px; margin: 16px 0 8px; color: #12433a; border-left: 4px solid #1d6b57; padding-left: 8px; }
.sheet h2 small { font-weight: 400; color: #8a7a68; font-size: 11.5px; }
.sheet p { margin: 5px 0; font-size: 13px; line-height: 1.6; }
.sheet .k { display: inline-block; width: 68px; color: #7a6a58; font-size: 12px; }
.danger-text { color: #a3342a; }
.sheet-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.sheet-table th, .sheet-table td { border: 1px solid #ddd3c2; padding: 6px 8px; text-align: left; }
.sheet-table th { background: #f2efe6; font-weight: 600; }
.goals { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.8; }
.red-box { border: 1.5px solid #d8b9a8; background: #fdf6f1; border-radius: 6px; padding: 8px 12px 12px; }
.red-box ul { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.8; }
.sheet-foot { margin-top: 22px; border-top: 1px dashed #c9bda6; padding-top: 10px; }
.sheet-foot p { font-size: 11.5px; color: #7a6a58; }
.sign { display: flex; gap: 10px; align-items: center; margin-top: 18px; font-size: 13px; }
.sign .line { display: inline-block; width: 140px; border-bottom: 1px solid #6b6154; height: 14px; }
.dim { color: #8a7a68; }

@media print {
  .no-print { display: none !important; }
  .print-page { padding: 0; background: #fff; }
  .sheet { box-shadow: none; margin: 0; width: auto; min-height: auto; padding: 10mm; }
  @page { size: A4 portrait; margin: 10mm; }
}
</style>
