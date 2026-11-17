<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import DischargeEditor from '@/components/DischargeEditor.vue'
import {
  assessRisk,
  createElder,
  extractDischarge,
  generatePlan,
  getDischarge,
  getElder,
  latestRisk,
  listDischarge,
  saveDischargeStructured,
  type DischargeData,
  type DischargeRecord,
  type Elder,
  type ExtractResult,
  type MedicationItem,
  type RiskAssessment,
} from '@/api'
import { genderLabel, levelLabel, levelTone, modeLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const elderId = computed(() => (route.params.id ? Number(route.params.id) : null))
const isNew = computed(() => elderId.value === null)

const busy = ref('')
const error = ref('')
const elder = ref<Elder | null>(null)
const result = ref<ExtractResult | null>(null)
const record = ref<DischargeRecord | null>(null)
const risk = ref<RiskAssessment | null>(null)
const text = ref('')
const editing = ref(false)
const saved = ref('')

/** 头部那枚状态标签：说清这份档案现在是"模型抽的""人填的"还是"人复核过"。 */
const statusText = computed(() => {
  if (result.value?.mode === 'MANUAL') return '人工填写'
  if (result.value?.mode === 'MODEL') return '模型抽取'
  if (result.value?.mode === 'LOCAL') return '本地规则'
  const s = editorRecord.value?.extractStatus
  if (s === 'REVIEWED') return '已人工复核'
  if (s === 'MANUAL') return '待人工填写'
  return s === 'PARSED' ? '模型抽取' : '待复核'
})
const statusTone = computed(() => (statusText.value === '模型抽取' ? '' : 'warn'))

const form = ref({
  name: '',
  gender: 'FEMALE',
  birthDate: '',
  phone: '',
  address: '',
  livingStatus: 'CHILDREN',
  caregiverName: '',
  caregiverPhone: '',
  chronic: '',
  allergy: '',
})

// ---------- 建档校验 ----------
// 护士一边录一边问，最容易漏的是电话和住址；出生日期还容易手滑选成未来。
// 所以这里不只在提交时拦，输入一次之后就边输边提示。
type FormErrors = Record<string, string>

const FIELD_LABELS: Record<string, string> = {
  name: '姓名',
  birthDate: '出生日期',
  phone: '联系电话',
  address: '住址',
  caregiverPhone: '照护人电话',
  chronic: '基础病',
  allergy: '过敏',
}

const PHONE_RE = /^1[3-9]\d{9}$/
// 按本地时区算"今天"，避免用 toISOString 在晚上差一天
const todayIso = (() => {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
})()

const errors = ref<FormErrors>({})
const attempted = ref(false)

const errorList = computed(() =>
  Object.keys(FIELD_LABELS)
    .filter((k) => errors.value[k])
    .map((k) => FIELD_LABELS[k]),
)

function parseTags(raw: string): string[] {
  return raw.split(/[，,、\s]+/).filter(Boolean)
}

function todayStart(): Date {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d
}

function validate(): boolean {
  const e: FormErrors = {}
  const f = form.value

  const name = f.name.trim()
  if (!name) e.name = '请填写老人姓名'
  else if (/^\d+$/.test(name)) e.name = '姓名不能是一串数字'
  else if (name.length < 2 || name.length > 20) e.name = '姓名应为 2–20 个字'

  if (!f.birthDate) {
    e.birthDate = '请选择出生日期（风险评分要用年龄）'
  } else {
    const d = new Date(`${f.birthDate}T00:00:00`)
    if (Number.isNaN(d.getTime())) e.birthDate = '日期格式不正确'
    else if (d > todayStart()) e.birthDate = '出生日期不能晚于今天'
    else if (d.getFullYear() < 1900) e.birthDate = '出生日期太早，请核对年份'
    else if (todayStart().getFullYear() - d.getFullYear() > 120) e.birthDate = '年龄超过 120 岁，请核对'
  }

  const phone = f.phone.trim()
  if (!phone) e.phone = '请填写联系电话（随访要打这个号）'
  else if (!PHONE_RE.test(phone)) e.phone = '请填 11 位手机号，例如 13800000001'

  const address = f.address.trim()
  if (!address) e.address = '请填写住址'
  else if (address.length < 4) e.address = '住址请写到街道门牌，至少 4 个字'

  const caregiverPhone = f.caregiverPhone.trim()
  if (caregiverPhone && !PHONE_RE.test(caregiverPhone)) {
    e.caregiverPhone = '照护人电话请填 11 位手机号'
  }

  const chronic = parseTags(f.chronic)
  if (chronic.some((t) => /^\d+$/.test(t))) e.chronic = '基础病请写名称，例如：高血压、糖尿病'
  const allergy = parseTags(f.allergy)
  if (allergy.some((t) => /^\d+$/.test(t))) e.allergy = '过敏请写名称，例如：青霉素'

  errors.value = e
  return Object.keys(e).length === 0
}

watch(
  form,
  () => {
    if (attempted.value) validate()
  },
  { deep: true },
)

function focusFirstError() {
  const first = Object.keys(FIELD_LABELS).find((k) => errors.value[k])
  if (!first) return
  const el = document.querySelector<HTMLElement>(`[data-field="${first}"] input, [data-field="${first}"] select`)
  el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  el?.focus()
}

const SAMPLE = `某某市第二人民医院　神经内科　出院记录
患者王秀兰，女，78岁，因"突发右侧肢体无力3小时"入院。
入院日期：2026-10-24　出院日期：2026-11-03
入院诊断：急性脑梗死（左侧基底节区）；高血压3级（很高危）；2型糖尿病。
住院经过：予阿司匹林抗血小板、阿托伐他汀调脂稳定斑块，住院期间血压控制可，无发热，无抽搐。
出院诊断：1. 急性脑梗死 2. 高血压3级 3. 2型糖尿病
出院医嘱：
1. 阿司匹林肠溶片 100mg 每日一次 口服
2. 阿托伐他汀钙片 20mg 每晚一次 口服
3. 苯磺酸氨氯地平片 5mg 每日一次 口服
4. 二甲双胍缓释片 0.5g 每日两次 口服
留置导尿管，10天后门诊拔除，期间保持会阴清洁，观察尿色。
饮食：低盐低脂糖尿病饮食，每日饮水1500ml左右。
活动：助行器辅助行走，每日2次，每次10分钟，需家属陪同。
复诊：2周后神经内科门诊复诊，携带出院小结，联系电话 0731-88886666；如出现言语不清、肢体无力加重、意识改变立即就诊。`

const data = computed<DischargeData | null>(() => result.value?.data ?? record.value?.data ?? null)
const medications = computed<MedicationItem[]>(() => data.value?.medications ?? [])
// 缺失字段在后端是英文键名，给护士看必须说人话
const MISSING_LABELS: Record<string, string> = {
  hospital: '出院医院',
  department: '科室',
  admit_date: '入院日期',
  discharge_date: '出院日期',
  diagnosis_primary: '主要诊断',
  diagnoses: '其他诊断',
  surgeries: '手术记录',
  medications: '出院带药',
  tubes: '管路',
  wound: '伤口情况',
  diet: '饮食要求',
  activity_limit: '活动限制',
  followup: '复诊安排',
  'followup.date': '复诊日期',
  'followup.department': '复诊科室',
  'followup.items': '复诊项目',
  'followup.contact': '复诊联系方式',
  red_flags: '红线症状',
  confidence: '字段置信度',
  'medications.dose': '剂量',
  'medications.frequency': '频次',
  'medications.route': '途径',
  'medications.duration': '疗程',
  'medications.note': '备注',
}
// 刷新页面后 result 为空，但"还差哪几项"依然要告诉护士，所以这里按当前档案再推一遍
function inferMissing(d: DischargeData | null, rec?: DischargeRecord | null): string[] {
  const out: string[] = []
  if (!rec?.hospital) out.push('hospital')
  if (!rec?.department) out.push('department')
  if (!rec?.admitDate) out.push('admit_date')
  if (!rec?.dischargeDate) out.push('discharge_date')
  if (!rec?.diagnosisPrimary) out.push('diagnosis_primary')
  if (!(d?.medications ?? []).length) out.push('medications')
  if (!d?.followup?.date) out.push('followup.date')
  const redFlags = Array.isArray(d?.redFlags) ? d.redFlags : []
  if (!redFlags.length) out.push('red_flags')
  return out
}
const missingText = computed(() => {
  const keys = result.value?.missingFields?.length
    ? result.value.missingFields
    : inferMissing(data.value, record.value)
  // 模型报缺是按字段路径逐条报的，直接显示会变成"medications[0].duration、wound.site…"。
  // 这里翻译成人话，并把同类合并；"本来就可以没有"的项（手术、无伤口时的伤口细节）直接不提示。
  const woundPresent = data.value?.wound?.present === true
  const labels: string[] = []
  for (const raw of keys) {
    // 旧数据里后端会把说明塞进同一个字符串（"medications（出院带药为空…）"），先把括号里的说明剥掉
    const k = raw.replace(/（[^）]*）|\([^)]*\)/g, '').trim()
    const hint = raw.slice(k.length).replace(/^[（(]|[）)]$/g, '').trim()
    const push = (label: string) => {
      if (hint) {
        labels.push(`${label}（${hint}）`)
      } else {
        labels.push(label)
      }
    }
    const med = /^medications\[\d+]\.(\w+)$/.exec(k)
    if (med) {
      // 备注本来就是选填，缺了不算问题；其余（疗程/剂量/频次/途径）合并成一句
      if (med[1] === 'note') continue
      push(`用药${MISSING_LABELS[`medications.${med[1]}`] ?? '信息'}`)
      continue
    }
    if (/^medications\[\d+]$/.test(k)) {
      push('出院带药')
      continue
    }
    if (/^wound\./.test(k)) {
      if (woundPresent) push('伤口护理细节')
      continue
    }
    // 复诊项目里的单个时间点不是必填项，属于"多抽到的好信息"，不该当成缺失
    if (/^followup\.items\[\d+]/.test(k)) continue
    if (k === 'surgeries') continue // 没做手术是常态，不该当成"缺"
    push(MISSING_LABELS[k] ?? k)
  }
  return [...new Set(labels)]
})
const lowConfidence = computed(() => {
  const conf = result.value?.confidences ?? data.value?.confidence ?? {}
  return Object.entries(conf)
    .filter(([, v]) => typeof v === 'number' && v < 0.6)
    .map(([k]) => MISSING_LABELS[k] ?? k)
})

function fillSample() {
  text.value = SAMPLE
}

async function createAndContinue() {
  attempted.value = true
  if (!validate()) {
    error.value = ''
    focusFirstError()
    return
  }
  busy.value = 'create'
  error.value = ''
  try {
    const created = await createElder({
      name: form.value.name.trim(),
      gender: form.value.gender,
      birthDate: form.value.birthDate || undefined,
      phone: form.value.phone || undefined,
      address: form.value.address || undefined,
      livingStatus: form.value.livingStatus,
      caregiverName: form.value.caregiverName || undefined,
      caregiverPhone: form.value.caregiverPhone || undefined,
      chronic: form.value.chronic ? form.value.chronic.split(/[，,、\s]+/).filter(Boolean) : [],
      allergy: form.value.allergy ? form.value.allergy.split(/[，,、\s]+/).filter(Boolean) : [],
    })
    elder.value = created
    router.replace({ name: 'discharge-import', params: { id: created.id } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '建档失败'
  } finally {
    busy.value = ''
  }
}

async function runExtract() {
  if (!elder.value || text.value.trim().length < 30) {
    error.value = '出院小结内容太短，至少 30 字'
    return
  }
  busy.value = 'extract'
  error.value = ''
  result.value = null
  saved.value = ''
  risk.value = null
  try {
    result.value = await extractDischarge({ elderId: elder.value.id, text: text.value })
    risk.value = await latestRisk(elder.value.id)
    // 没有 Key 时会降级成 MANUAL：直接把人带到手填表单，别让护士再去找入口
    if (result.value.extractStatus === 'MANUAL') {
      editing.value = true
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '抽取失败'
  } finally {
    busy.value = ''
  }
}

/** 打开手填面板时给编辑器的"当前档案"：优先用刚抽取的结果，否则用库里最新一条。 */
const editorRecord = computed<DischargeRecord | null>(() => {
  const structured = result.value?.data
  if (!structured && !record.value) return null
  const d = (structured ?? record.value?.data ?? {}) as DischargeData & {
    hospital?: string
    department?: string
    admit_date?: string
    discharge_date?: string
    diagnosis_primary?: string
  }
  return {
    id: result.value?.recordId ?? record.value?.id ?? 0,
    elderId: elder.value?.id ?? 0,
    hospital: d.hospital ?? record.value?.hospital ?? '',
    department: d.department ?? record.value?.department ?? '',
    admitDate: d.admit_date ?? record.value?.admitDate ?? '',
    dischargeDate: d.discharge_date ?? record.value?.dischargeDate ?? '',
    diagnosisPrimary: d.diagnosis_primary ?? record.value?.diagnosisPrimary ?? '',
    extractStatus: result.value?.extractStatus ?? record.value?.extractStatus ?? 'PARSED',
    data: (structured ?? record.value?.data ?? {}) as DischargeData,
  }
})

async function saveManual(payload: Record<string, unknown>) {
  const id = editorRecord.value?.id
  if (!id || !elder.value) return
  busy.value = 'save'
  error.value = ''
  try {
    const savedResult = await saveDischargeStructured(id, payload)
    result.value = savedResult
    record.value = await getDischarge(id)
    editing.value = false
    risk.value = await assessRisk({ elderId: elder.value.id, recordId: id })
    saved.value = '已保存人工填写的档案，并按最新内容重算了风险评分卡'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = ''
  }
}

async function assessAndPlan() {
  if (!elder.value) {
    return
  }
  busy.value = 'plan'
  error.value = ''
  try {
    risk.value = await assessRisk({ elderId: elder.value.id, recordId: result.value?.recordId ?? null })
    const plan = await generatePlan({ elderId: elder.value.id, recordId: result.value?.recordId ?? null })
    router.push({ name: 'plan-review', params: { id: elder.value.id, planId: plan.id } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成计划失败'
  } finally {
    busy.value = ''
  }
}

onMounted(async () => {
  if (elderId.value) {
    try {
      elder.value = await getElder(elderId.value)
      risk.value = await latestRisk(elderId.value)
      const records = await listDischarge(elderId.value)
      if (records.length) {
        record.value = records[0]
        // 从详情页点进来时也要能看到/继续填上一次的档案
        if (records[0].extractStatus === 'MANUAL') {
          editing.value = true
        }
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载失败'
    }
  }
})
</script>

<template>
  <div class="page fade-up">
    <header class="page-head">
      <div>
        <div class="kicker">HANDOVER · 交接环第一步</div>
        <h1>{{ isNew ? '接收一位新出院老人' : `接收出院小结 · ${elder?.name ?? ''}` }}</h1>
        <p class="sub">
          把出院小结原文贴进来，AI 负责把散落的医嘱整理成字段；字段是否正确，由护士复核。
        </p>
      </div>
    </header>

    <p v-if="error" class="note danger">{{ error }}</p>

    <!-- 第一步：建档 -->
    <section v-if="isNew" class="card">
      <div class="card-head">
        <h3>第一步 · 建档</h3>
        <span class="hint">编号由系统生成（CR-0001…）</span>
      </div>
      <div class="grid cols-3">
        <div class="field" data-field="name">
          <label>姓名 <span class="req">*</span></label>
          <input v-model="form.name" class="input" :class="{ invalid: errors.name }" placeholder="如：王秀兰" />
          <p v-if="errors.name" class="field-error">{{ errors.name }}</p>
        </div>
        <div class="field">
          <label>性别</label>
          <select v-model="form.gender" class="select">
            <option value="FEMALE">女</option>
            <option value="MALE">男</option>
          </select>
        </div>
        <div class="field" data-field="birthDate">
          <label>出生日期 <span class="req">*</span></label>
          <input
            v-model="form.birthDate"
            class="input"
            type="date"
            :max="todayIso"
            :class="{ invalid: errors.birthDate }"
          />
          <p v-if="errors.birthDate" class="field-error">{{ errors.birthDate }}</p>
        </div>
        <div class="field" data-field="phone">
          <label>联系电话 <span class="req">*</span></label>
          <input
            v-model="form.phone"
            class="input"
            inputmode="numeric"
            placeholder="11 位手机号，随访要打这个号"
            :class="{ invalid: errors.phone }"
          />
          <p v-if="errors.phone" class="field-error">{{ errors.phone }}</p>
        </div>
        <div class="field">
          <label>居住情况</label>
          <select v-model="form.livingStatus" class="select">
            <option value="CHILDREN">与子女同住</option>
            <option value="SPOUSE">与配偶同住</option>
            <option value="ALONE">独居</option>
            <option value="FACILITY">养老机构</option>
          </select>
        </div>
        <div class="field" data-field="address">
          <label>住址 <span class="req">*</span></label>
          <input
            v-model="form.address"
            class="input"
            placeholder="街道 + 门牌，如：阳光街道 7 号楼 1 单元 202"
            :class="{ invalid: errors.address }"
          />
          <p v-if="errors.address" class="field-error">{{ errors.address }}</p>
        </div>
        <div class="field">
          <label>照护人</label>
          <input v-model="form.caregiverName" class="input" placeholder="如：张秀英（女儿）" />
        </div>
        <div class="field" data-field="caregiverPhone">
          <label>照护人电话</label>
          <input
            v-model="form.caregiverPhone"
            class="input"
            inputmode="numeric"
            placeholder="选填"
            :class="{ invalid: errors.caregiverPhone }"
          />
          <p v-if="errors.caregiverPhone" class="field-error">{{ errors.caregiverPhone }}</p>
        </div>
        <div class="field" data-field="chronic">
          <label>基础病（逗号分隔）</label>
          <input v-model="form.chronic" class="input" placeholder="高血压，糖尿病" :class="{ invalid: errors.chronic }" />
          <p v-if="errors.chronic" class="field-error">{{ errors.chronic }}</p>
        </div>
        <div class="field" data-field="allergy">
          <label>过敏（逗号分隔）</label>
          <input
            v-model="form.allergy"
            class="input"
            placeholder="没有就留空；如：青霉素"
            :class="{ invalid: errors.allergy }"
          />
          <p v-if="errors.allergy" class="field-error">{{ errors.allergy }}</p>
        </div>
      </div>

      <p v-if="attempted && errorList.length" class="form-alert">
        <span class="dot" />
        还有 {{ errorList.length }} 处需要补上：{{ errorList.join('、') }}
      </p>

      <div class="row" style="margin-top: 16px">
        <span class="muted">带 <span class="req">*</span> 的四项必填，其余可以稍后补；建完档会直接进入粘贴出院小结这一步。</span>
        <span class="spacer" />
        <button class="btn primary" :disabled="busy === 'create'" @click="createAndContinue">
          {{ busy === 'create' ? '建档中…' : '建档并继续' }}
        </button>
      </div>
    </section>

    <!-- 第二步：粘贴与抽取 -->
    <section v-else class="card">
      <div class="card-head">
        <h3>粘贴出院小结原文</h3>
        <div class="row">
          <button class="btn sm" @click="fillSample">填入示例</button>
          <button class="btn sm" :disabled="!text" @click="text = ''">清空</button>
        </div>
      </div>
      <textarea
        v-model="text"
        class="textarea"
        placeholder="把出院记录 / 出院医嘱整段贴进来，包含用药、管路、伤口、饮食活动、复诊要求即可。"
      />
      <div class="row" style="margin-top: 12px">
        <span class="muted">{{ text.length }} 字 · 少于 30 字无法抽取</span>
        <span class="spacer" />
        <button class="btn primary" :disabled="busy === 'extract' || text.trim().length < 30" @click="runExtract">
          <span v-if="busy === 'extract'" class="spin" />
          {{ busy === 'extract' ? 'AI 正在整理…' : '抽取结构化档案' }}
        </button>
      </div>
    </section>

    <!-- 结果 -->
    <template v-if="result || record">
      <section class="card" style="margin-top: 16px">
        <div class="card-head">
          <h3>抽取结果（待护士复核）</h3>
          <div class="row">
            <span class="pill" :class="statusTone">{{ statusText }}</span>
            <span class="muted">{{ result?.latencyMs ? `${result.latencyMs} ms` : '' }}</span>
            <button v-if="!editing" class="btn sm" @click="editing = true">手工填写 / 修订</button>
          </div>
        </div>
        <p v-if="saved" class="note ok">{{ saved }}</p>
        <p v-if="result?.notice && !saved" class="note warn">{{ result.notice }}</p>
        <p v-if="missingText.length && !editing" class="note warn">
          这几项还是空的：{{ missingText.join('、') }}。为空会影响评分与任务生成，可以点右上角「手工填写 / 修订」补齐。
        </p>
        <p v-if="missingText.length && editing" class="note warn">
          还在空着的：{{ missingText.join('、') }}。在下面表单里补上就行——用药的"疗程"在「出院带药」表格的「疗程」列。
        </p>

        <DischargeEditor
          v-if="editing && editorRecord"
          :record="editorRecord"
          style="margin-top: 12px"
          @save="saveManual"
          @cancel="editing = false"
        />

        <div v-else class="grid cols-2" style="margin-top: 12px">
          <div>
            <div class="kicker">诊断</div>
            <ul class="plain">
              <li v-for="(d, i) in (data?.diagnoses as string[]) ?? []" :key="i">{{ d }}</li>
            </ul>
            <div class="kicker" style="margin-top: 14px">出院带药 {{ medications.length }} 种</div>
            <table class="table">
              <thead><tr><th>药品</th><th>剂量</th><th>频次</th><th>途径</th><th>疗程</th></tr></thead>
              <tbody>
                <tr v-for="(m, i) in medications" :key="i">
                  <td>{{ m.name }}</td>
                  <td>{{ m.dose || '—' }}</td>
                  <td>{{ m.frequency || '—' }}</td>
                  <td>{{ m.route || '—' }}</td>
                  <td>{{ m.duration || '—' }}</td>
                </tr>
                <tr v-if="!medications.length"><td colspan="5" class="muted">没有识别到用药，请人工补充</td></tr>
              </tbody>
            </table>
          </div>
          <div>
            <div class="kicker">管路与伤口</div>
            <ul class="plain">
              <li v-for="(t, i) in (data?.tubes as Array<{ type?: string; note?: string }>) ?? []" :key="i">
                {{ t.type }} <span class="muted">{{ t.note }}</span>
              </li>
              <li v-if="!((data?.tubes as unknown[]) ?? []).length" class="muted">无管路</li>
              <li>
                伤口：{{ data?.wound?.present ? `${data?.wound?.site ?? '有伤口'}（${data?.wound?.care ?? '护理要求待补'}）` : '无' }}
              </li>
            </ul>

            <div class="kicker" style="margin-top: 14px">复诊与红线</div>
            <ul class="plain">
              <li v-for="(f, i) in (data?.followup?.items ?? [])" :key="i">复诊：{{ f }}</li>
              <li v-if="data?.followup?.date">指定日期：{{ data?.followup?.date }}</li>
              <li v-for="(r, i) in (data?.redFlags as Array<{ symptom?: string; action?: string }>) ?? []" :key="`rf-${i}`">
                <span class="pill danger" style="margin-right: 6px">红线</span>{{ r.symptom }} → {{ r.action }}
              </li>
            </ul>

            <div class="kicker" style="margin-top: 14px">饮食与活动</div>
            <ul class="plain">
              <li v-for="(d, i) in (data?.diet as string[]) ?? []" :key="`d-${i}`">{{ d }}</li>
              <li v-for="(a, i) in (data?.activityLimit as string[]) ?? []" :key="`a-${i}`">{{ a }}</li>
            </ul>
          </div>
        </div>

        <div v-if="lowConfidence.length" class="note warn" style="margin-top: 8px">
          置信度偏低（&lt; 0.6）：{{ lowConfidence.map((k) => MISSING_LABELS[k] ?? k).join('、') }}。建议逐条对照原文。
        </div>

        <div class="divider" />
        <div class="row">
          <div v-if="risk" class="row">
            <span class="pill" :class="levelTone(risk.level) === 'ok' ? '' : levelTone(risk.level)">
              {{ levelLabel(risk.level) }} · {{ risk.score }} 分
            </span>
            <span class="muted">评分卡已按最新档案计算</span>
          </div>
          <span class="spacer" />
          <button class="btn" @click="router.push({ name: 'elder-detail', params: { id: elder?.id } })">先去核对档案</button>
          <button class="btn primary" :disabled="busy === 'plan'" @click="assessAndPlan">
            {{ busy === 'plan' ? '生成中…' : '评估风险并生成 30 天计划' }}
          </button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.plain { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; font-size: 13.5px; }
</style>
