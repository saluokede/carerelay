<script setup lang="ts">
import { reactive, watch } from 'vue'

import type { DischargeData, DischargeRecord, MedicationItem } from '@/api'

/**
 * 结构化档案的人工填写 / 修订面板。
 *
 * 为什么需要它：没有模型 Key 时抽取会降级成"待人工填写"，如果只给一句提示而没有输入界面，
 * 风险评分和计划生成就永远拿不到输入——交接环会在第一步断掉。
 *
 * 表单用逗号分隔的文本编辑数组（护士更快），保存时再拆成数组，键名转回后端的 snake_case。
 */
const props = defineProps<{
  record: DischargeRecord
}>()

const emit = defineEmits<{
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'cancel'): void
}>()

interface Draft {
  hospital: string
  department: string
  admitDate: string
  dischargeDate: string
  diagnosisPrimary: string
  diagnosesText: string
  surgeriesText: string
  medications: MedicationItem[]
  tubesText: string
  woundPresent: boolean
  woundSite: string
  woundCare: string
  woundDressing: string
  dietText: string
  activityText: string
  followupDate: string
  followupDepartment: string
  followupItemsText: string
  followupContact: string
  redFlags: Array<{ symptom: string; action: string }>
}

const draft = reactive<Draft>(blank())

function blank(): Draft {
  return {
    hospital: '',
    department: '',
    admitDate: '',
    dischargeDate: '',
    diagnosisPrimary: '',
    diagnosesText: '',
    surgeriesText: '',
    medications: [],
    tubesText: '',
    woundPresent: false,
    woundSite: '',
    woundCare: '',
    woundDressing: '',
    dietText: '',
    activityText: '',
    followupDate: '',
    followupDepartment: '',
    followupItemsText: '',
    followupContact: '',
    redFlags: [],
  }
}

function joinText(value: unknown): string {
  if (!value) return ''
  if (Array.isArray(value)) {
    return value
      .map((v) => (typeof v === 'string' ? v : (v as { name?: string })?.name ?? ''))
      .filter(Boolean)
      .join('，')
  }
  return String(value)
}

/**
 * 逗号/顿号/分号/换行分隔。**不能按空格拆**——"高血压 3 级""低盐低脂饮食 2 周"
 * 这类中文短语本身带空格，按空格拆会把一条诊断切成三条。
 */
function toTextArray(value: string): string[] {
  return value
    .split(/[，,、；;\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function hydrate() {
  const r = props.record
  const d: DischargeData = r.data ?? {}
  Object.assign(draft, blank())
  draft.hospital = r.hospital ?? ''
  draft.department = r.department ?? ''
  draft.admitDate = r.admitDate ?? ''
  draft.dischargeDate = r.dischargeDate ?? ''
  draft.diagnosisPrimary = r.diagnosisPrimary ?? ''
  // 主要诊断单独一栏，这里只放「其他诊断」，否则同一条诊断会出现两次
  const primary = r.diagnosisPrimary ?? ''
  const diagnoses = Array.isArray(d.diagnoses) ? d.diagnoses : d.diagnoses ? [d.diagnoses] : []
  draft.diagnosesText = joinText(diagnoses.filter((x) => x !== primary))
  draft.surgeriesText = joinText(d.surgeries)
  draft.medications = (d.medications ?? []).map((m) => ({
    name: m.name ?? '',
    dose: m.dose ?? '',
    frequency: m.frequency ?? '',
    route: m.route ?? '',
    duration: m.duration ?? '',
    note: m.note ?? '',
  }))
  draft.tubesText = joinText(d.tubes)
  draft.woundPresent = Boolean(d.wound?.present)
  draft.woundSite = d.wound?.site ?? ''
  draft.woundCare = d.wound?.care ?? ''
  draft.woundDressing = d.wound?.dressing_change ?? ''
  draft.dietText = joinText(d.diet)
  draft.activityText = joinText(d.activityLimit)
  draft.followupDate = d.followup?.date ?? ''
  draft.followupDepartment = d.followup?.department ?? ''
  draft.followupItemsText = (d.followup?.items ?? []).join('，')
  draft.followupContact = d.followup?.contact ?? ''
  draft.redFlags = Array.isArray(d.redFlags)
    ? (d.redFlags as Array<{ symptom?: string; action?: string }>).map((f) => ({
        symptom: f.symptom ?? '',
        action: f.action ?? '',
      }))
    : []
  if (!draft.redFlags.length) draft.redFlags.push({ symptom: '', action: '' })
}

watch(() => props.record, hydrate, { immediate: true })

function addMedication() {
  draft.medications.push({ name: '', dose: '', frequency: '', route: '口服' })
}

function removeMedication(index: number) {
  draft.medications.splice(index, 1)
}

function addRedFlag() {
  draft.redFlags.push({ symptom: '', action: '' })
}

function removeRedFlag(index: number) {
  draft.redFlags.splice(index, 1)
}

function save() {
  const medications = draft.medications
    .filter((m) => (m.name ?? '').trim())
    .map((m) => ({
      name: m.name?.trim() ?? '',
      dose: (m.dose ?? '').trim() || null,
      frequency: (m.frequency ?? '').trim() || null,
      route: (m.route ?? '').trim() || null,
      duration: (m.duration ?? '').trim() || null,
      note: (m.note ?? '').trim() || null,
    }))

  emit('save', {
    hospital: draft.hospital.trim(),
    department: draft.department.trim(),
    admit_date: draft.admitDate || null,
    discharge_date: draft.dischargeDate || null,
    diagnosis_primary: draft.diagnosisPrimary.trim(),
    diagnoses: (() => {
      const primary = draft.diagnosisPrimary.trim()
      const others = toTextArray(draft.diagnosesText).filter((x) => x !== primary)
      return primary ? [primary, ...others] : others
    })(),
    surgeries: toTextArray(draft.surgeriesText),
    medications,
    tubes: toTextArray(draft.tubesText),
    wound: {
      present: draft.woundPresent,
      site: draft.woundSite.trim() || null,
      care: draft.woundCare.trim() || null,
      dressing_change: draft.woundDressing.trim() || null,
    },
    diet: toTextArray(draft.dietText),
    activity_limit: toTextArray(draft.activityText),
    followup: {
      date: draft.followupDate || null,
      department: draft.followupDepartment.trim() || null,
      items: toTextArray(draft.followupItemsText),
      contact: draft.followupContact.trim() || null,
    },
    red_flags: draft.redFlags
      .filter((f) => f.symptom.trim())
      .map((f) => ({ symptom: f.symptom.trim(), action: f.action.trim() })),
  })
}
</script>

<template>
  <div class="editor">
    <div class="grid cols-4">
      <div class="field">
        <label>出院医院</label>
        <input v-model="draft.hospital" class="input" placeholder="如：某某市第二人民医院" />
      </div>
      <div class="field">
        <label>科室</label>
        <input v-model="draft.department" class="input" placeholder="如：神经内科" />
      </div>
      <div class="field">
        <label>入院日期</label>
        <input v-model="draft.admitDate" class="input" type="date" />
      </div>
      <div class="field">
        <label>出院日期</label>
        <input v-model="draft.dischargeDate" class="input" type="date" />
      </div>
    </div>

    <div class="grid cols-2">
      <div class="field">
        <label>主要诊断</label>
        <input v-model="draft.diagnosisPrimary" class="input" placeholder="如：急性脑梗死" />
      </div>
      <div class="field">
        <label>其他诊断（逗号分隔）</label>
        <input v-model="draft.diagnosesText" class="input" placeholder="高血压 3 级，2 型糖尿病" />
      </div>
    </div>

    <div class="kicker" style="margin: 16px 0 8px">出院带药</div>
    <table class="table">
      <thead>
        <tr>
          <th style="width: 24%">药品</th>
          <th style="width: 13%">剂量</th>
          <th style="width: 13%">频次</th>
          <th style="width: 12%">途径</th>
          <th style="width: 14%">疗程</th>
          <th style="width: 16%">备注</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="(m, i) in draft.medications" :key="`med-${i}`">
          <td><input v-model="m.name" class="input sm" placeholder="阿司匹林肠溶片" /></td>
          <td><input v-model="m.dose" class="input sm" placeholder="100mg" /></td>
          <td><input v-model="m.frequency" class="input sm" placeholder="qd / bid" /></td>
          <td><input v-model="m.route" class="input sm" placeholder="口服" /></td>
          <td><input v-model="m.duration" class="input sm" placeholder="如：长期" /></td>
          <td><input v-model="m.note" class="input sm" placeholder="餐后" /></td>
          <td>
            <button class="btn sm ghost" title="删除这一行" @click="removeMedication(i)">删</button>
          </td>
        </tr>
        <tr v-if="!draft.medications.length">
          <td colspan="7" class="muted">还没有药，点下面的「加一条用药」。</td>
        </tr>
      </tbody>
    </table>
    <button class="btn sm" style="margin-top: 8px" @click="addMedication">+ 加一条用药</button>

    <div class="grid cols-2" style="margin-top: 16px">
      <div class="field">
        <label>管路（逗号分隔）</label>
        <input v-model="draft.tubesText" class="input" placeholder="留置导尿管" />
      </div>
      <div class="field">
        <label>饮食</label>
        <input v-model="draft.dietText" class="input" placeholder="低盐低脂糖尿病饮食" />
      </div>
      <div class="field">
        <label>活动限制</label>
        <input v-model="draft.activityText" class="input" placeholder="助行器辅助行走，每日 2 次" />
      </div>
      <div class="field">
        <label>其他</label>
        <input v-model="draft.surgeriesText" class="input" placeholder="手术（没有就留空）" />
      </div>
    </div>

    <div class="kicker" style="margin: 16px 0 8px">伤口</div>
    <div class="row" style="gap: 16px; flex-wrap: wrap">
      <label class="check">
        <input v-model="draft.woundPresent" type="checkbox" />
        有伤口 / 需要换药
      </label>
      <input v-model="draft.woundSite" class="input" style="max-width: 180px" placeholder="部位，如：右髋" />
      <input v-model="draft.woundCare" class="input" style="max-width: 220px" placeholder="护理，如：保持干燥" />
      <input v-model="draft.woundDressing" class="input" style="max-width: 180px" placeholder="换药频率，如：隔日" />
    </div>

    <div class="kicker" style="margin: 16px 0 8px">复诊与红线</div>
    <div class="grid cols-4">
      <div class="field">
        <label>复诊日期</label>
        <input v-model="draft.followupDate" class="input" type="date" />
      </div>
      <div class="field">
        <label>复诊科室</label>
        <input v-model="draft.followupDepartment" class="input" placeholder="神经内科" />
      </div>
      <div class="field">
        <label>复诊项目（逗号分隔）</label>
        <input v-model="draft.followupItemsText" class="input" placeholder="复查血常规" />
      </div>
      <div class="field">
        <label>联系方式</label>
        <input v-model="draft.followupContact" class="input" placeholder="门诊电话" />
      </div>
    </div>

    <table class="table" style="margin-top: 10px">
      <thead>
        <tr>
          <th style="width: 45%">出现什么（症状）</th>
          <th>怎么办（处置）</th>
          <th style="width: 60px" />
        </tr>
      </thead>
      <tbody>
        <tr v-for="(f, i) in draft.redFlags" :key="`rf-${i}`">
          <td><input v-model="f.symptom" class="input sm" placeholder="言语不清 / 肢体无力加重" /></td>
          <td><input v-model="f.action" class="input sm" placeholder="立即就诊 / 拨打 120" /></td>
          <td><button class="btn sm ghost" @click="removeRedFlag(i)">删</button></td>
        </tr>
      </tbody>
    </table>
    <button class="btn sm" style="margin-top: 8px" @click="addRedFlag">+ 加一条红线</button>

    <div class="row" style="margin-top: 18px">
      <span class="muted">这些字段是风险评分卡和任务展开的输入；药品只填名称也能保存，剂量可以稍后补。</span>
      <span class="spacer" />
      <button class="btn" @click="emit('cancel')">取消</button>
      <button class="btn primary" @click="save">保存并重新评估</button>
    </div>
  </div>
</template>

<style scoped>
.check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  color: var(--ink-2);
}

.input.sm {
  padding: 7px 9px;
  font-size: 13px;
}
</style>
