<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

import {
  addAdmission,
  assessRisk,
  generatePlan,
  getElder,
  updateElder,
  issuePlan,
  latestRisk,
  dischargeAdmission,
  listAdmissions,
  updateAdmission,
  voidAdmission,
  getDischarge,
  listAlerts,
  listDischarge,
  listFollowups,
  listPlans,
  scheduleFollowup,
  type AlertEvent,
  type CarePlan,
  type DischargeRecord,
  type Admission,
  type Elder,
  type FollowupSession,
  type RiskAssessment,
} from '@/api'
import {
  channelLabel,
  formatDate,
  formatDateTime,
  genderLabel,
  levelLabel,
  levelTone,
  livingLabel,
  modeLabel,
  planStatusLabel,
  followupStatusLabel,
} from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const elderId = computed(() => Number(route.params.id))
const loading = ref(true)
const busy = ref('')
const error = ref('')
const toast = ref('')
const admissions = ref<Admission[]>([])
const admOpen = ref(false)
const admBusy = ref(false)
const admDraft = ref({
  hospital: '',
  department: '',
  bedNo: '',
  admitDate: '',
  dischargeDate: '',
  reason: '',
})
const currentAdmission = computed(() => admissions.value.find((a) => a.status === 'IN_HOSPITAL') ?? null)
/** 正在编辑的住院记录 id；null 表示"新登记" */
const admEditing = ref<number | null>(null)
/** 点住院记录里的"出院小结"时，把左边那张卡切到对应的那一份 */
const previewRecord = ref<DischargeRecord | null>(null)

function openAdmission() {
  admEditing.value = null
  admDraft.value = {
    hospital: elder.value?.hospital ?? '',
    department: elder.value?.department ?? '',
    bedNo: '',
    admitDate: new Date().toISOString().slice(0, 10),
    dischargeDate: '',
    reason: '',
  }
  admOpen.value = true
}

async function submitAdmission() {
  if (!elder.value) return
  if (!admDraft.value.admitDate) {
    toast.value = '请填写入院日期'
    return
  }
  admBusy.value = true
  try {
    const payload: Record<string, unknown> = {
      hospital: admDraft.value.hospital,
      department: admDraft.value.department,
      bedNo: admDraft.value.bedNo,
      admitDate: admDraft.value.admitDate,
      reason: admDraft.value.reason,
    }
    if (admDraft.value.dischargeDate) {
      payload.dischargeDate = admDraft.value.dischargeDate
    }
    if (admEditing.value) {
      await updateAdmission(admEditing.value, payload)
    } else {
      await addAdmission(elder.value.id, payload)
    }
    const wasEditing = admEditing.value !== null
    admOpen.value = false
    admEditing.value = null
    admissions.value = await listAdmissions(elder.value.id)
    elder.value = await getElder(elder.value.id)
    toast.value = wasEditing
      ? '已更新这条住院记录'
      : admDraft.value.dischargeDate
        ? '已补登这次住院'
        : '已登记住院，逾期催办会自动暂停'
  } catch (e) {
    toast.value = e instanceof Error ? e.message : '登记失败'
  } finally {
    admBusy.value = false
  }
}

/** 编辑一条已存在的住院记录（录错了改这里） */
function openEditAdmission(a: Admission) {
  admEditing.value = a.id
  admDraft.value = {
    hospital: a.hospital ?? '',
    department: a.department ?? '',
    bedNo: a.bedNo ?? '',
    admitDate: a.admitDate,
    dischargeDate: a.dischargeDate ?? '',
    reason: a.reason ?? '',
  }
  admOpen.value = true
}

/** 看这次住院对应的出院小结（没有关联就提示一句） */
async function viewAdmissionRecord(a: Admission) {
  if (!a.dischargeRecordId) {
    toast.value = '这次住院还没有对应的出院小结；可以在「导入出院小结」里补上'
    return
  }
  try {
    previewRecord.value = await getDischarge(a.dischargeRecordId)
  } catch (e) {
    toast.value = e instanceof Error ? e.message : '出院小结读取失败'
  }
}

/** 作废：录错了不能删，改成作废并留原因 */
async function voidCurrent(a: Admission) {
  if (!elder.value) return
  const reason = window.prompt('作废原因（会记进审计）', '录入有误')
  if (reason === null) return
  admBusy.value = true
  try {
    await voidAdmission(a.id, reason || '录入有误')
    admissions.value = await listAdmissions(elder.value.id)
    elder.value = await getElder(elder.value.id)
    toast.value = '这一笔已作废，记录仍保留可查'
  } catch (e) {
    toast.value = e instanceof Error ? e.message : '作废失败'
  } finally {
    admBusy.value = false
  }
}

async function finishAdmission(a: Admission) {
  if (!elder.value) return
  admBusy.value = true
  try {
    await dischargeAdmission(a.id, new Date().toISOString().slice(0, 10))
    admissions.value = await listAdmissions(elder.value.id)
    elder.value = await getElder(elder.value.id)
    toast.value = '已办理出院，档案回到居家随访'
  } catch (e) {
    toast.value = e instanceof Error ? e.message : '出院登记失败'
  } finally {
    admBusy.value = false
  }
}

const elder = ref<Elder | null>(null)
const discharges = ref<DischargeRecord[]>([])
const risk = ref<RiskAssessment | null>(null)
const plans = ref<CarePlan[]>([])
const followups = ref<FollowupSession[]>([])
const alerts = ref<AlertEvent[]>([])

const latestRecord = computed(() => discharges.value[0] ?? null)
/** 左栏那张卡当前显示哪一份：优先显示从住院记录点进来的那份 */
const activeRecord = computed(() => previewRecord.value ?? latestRecord.value)
const issuedPlan = computed(() => plans.value.find((p) => p.status === 'ISSUED') ?? null)
const draftPlan = computed(() => plans.value.find((p) => p.status === 'DRAFT') ?? null)
const meds = computed(() => activeRecord.value?.data?.medications ?? [])
const openAlerts = computed(() => alerts.value.filter((a) => a.status === 'OPEN'))
const nextFollowup = computed(() => followups.value.find((f) => f.status === 'PLANNED') ?? null)

/* -------- 档案编辑：护士边问边补，所以做成"打补丁"而不是整份重填 -------- */
const editing = ref(false)
const saving = ref(false)
const draft = ref({
  name: '',
  gender: 'FEMALE',
  birthDate: '',
  phone: '',
  address: '',
  livingStatus: 'CHILDREN',
  caregiverName: '',
  caregiverPhone: '',
  chronicText: '',
  allergyText: '',
  careStatus: 'HOME',
  bedNo: '',
  hospital: '',
  department: '',
  admitDate: '',
})

const CARE_STATUS_TEXT: Record<string, string> = {
  HOME: '居家随访',
  HOSPITALIZED: '住院中',
  CLOSED: '已结案',
}

const careStatusText = computed(() => CARE_STATUS_TEXT[elder.value?.careStatus ?? 'HOME'] ?? '居家随访')

function openEdit() {
  const e = elder.value
  if (!e) return
  draft.value = {
    name: e.name,
    gender: e.gender ?? 'FEMALE',
    birthDate: e.birthDate ?? '',
    phone: e.phone ?? '',
    address: e.address ?? '',
    livingStatus: e.livingStatus ?? 'CHILDREN',
    caregiverName: e.caregiverName ?? '',
    caregiverPhone: e.caregiverPhone ?? '',
    chronicText: (e.chronic ?? []).join('，'),
    allergyText: (e.allergy ?? []).join('，'),
    careStatus: e.careStatus ?? 'HOME',
    bedNo: e.bedNo ?? '',
    hospital: e.hospital ?? '',
    department: e.department ?? '',
    admitDate: e.admitDate ?? '',
  }
  editing.value = true
}

function splitTags(text: string): string[] {
  return text
    .split(/[，,、；;\n]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

async function saveEdit() {
  const d = draft.value
  if (!d.name.trim()) {
    error.value = '姓名不能为空'
    return
  }
  saving.value = true
  try {
    elder.value = await updateElder(elderId.value, {
      name: d.name.trim(),
      gender: d.gender,
      birthDate: d.birthDate || undefined,
      phone: d.phone,
      address: d.address,
      livingStatus: d.livingStatus,
      caregiverName: d.caregiverName,
      caregiverPhone: d.caregiverPhone,
      chronic: splitTags(d.chronicText),
      allergy: splitTags(d.allergyText),
      careStatus: d.careStatus,
      bedNo: d.bedNo,
      hospital: d.hospital,
      department: d.department,
      admitDate: d.admitDate || undefined,
    })
    editing.value = false
    flash('档案已更新')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}


function flash(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) {
      toast.value = ''
    }
  }, 2600)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [e, d, r, p, f, a, adm] = await Promise.all([
      getElder(elderId.value),
      listDischarge(elderId.value),
      latestRisk(elderId.value),
      listPlans(elderId.value),
      listFollowups(elderId.value),
      listAlerts(),
      listAdmissions(elderId.value),
    ])
    elder.value = e
    admissions.value = adm
    discharges.value = d
    risk.value = r
    plans.value = p
    followups.value = f
    alerts.value = a.filter((item) => item.elderId === elderId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function reassess() {
  busy.value = 'assess'
  try {
    risk.value = await assessRisk({ elderId: elderId.value, recordId: latestRecord.value?.id ?? null })
    flash('评分卡已更新（规则重算，解释已重新生成）')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '评估失败'
  } finally {
    busy.value = ''
  }
}

async function makePlan() {
  busy.value = 'plan'
  try {
    const plan = await generatePlan({ elderId: elderId.value, recordId: latestRecord.value?.id ?? null })
    plans.value = await listPlans(elderId.value)
    flash('计划草案已生成，请护士核对后签发')
    router.push({ name: 'plan-review', params: { id: elderId.value, planId: plan.id } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成失败'
  } finally {
    busy.value = ''
  }
}

async function sign(plan: CarePlan) {
  busy.value = 'issue'
  try {
    await issuePlan(plan.id)
    plans.value = await listPlans(elderId.value)
    flash('已签发：家属端现在能看到今天的任务了')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '签发失败'
  } finally {
    busy.value = ''
  }
}

async function arrangeFollowup() {
  busy.value = 'followup'
  try {
    await scheduleFollowup({ elderId: elderId.value })
    followups.value = await listFollowups(elderId.value)
    flash('已按计划随访间隔排期')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '排期失败'
  } finally {
    busy.value = ''
  }
}

function go(name: string, extra: Record<string, string> = {}) {
  router.push({ name, params: { id: elderId.value, ...extra } })
}

onMounted(load)
</script>

<template>
  <div class="page fade-up">
    <div v-if="loading" class="empty"><span class="spin" /> 正在读取老人档案…</div>
    <template v-else-if="elder">
      <header class="profile">
        <div class="profile-main">
          <div class="kicker">{{ elder.code }} · HANDOVER FILE</div>
          <h1 class="profile-name">{{ elder.name }}<span class="profile-age num">{{ elder.age ?? '—' }} 岁</span></h1>
          <p class="dateline">
            {{ genderLabel(elder.gender) }} · {{ livingLabel(elder.livingStatus) }}
            <template v-if="elder.caregiverName"> · 照护人 {{ elder.caregiverName }}</template>
            <template v-if="elder.caregiverPhone"> {{ elder.caregiverPhone }}</template>
          </p>
        </div>
        <div class="profile-risk" :class="levelTone(risk?.level)">
          <span class="k">风险评分</span>
          <strong class="num">{{ risk?.score ?? 0 }}</strong>
          <span class="l">{{ levelLabel(risk?.level) }}<template v-if="risk"> · {{ modeLabel(risk.assessor) }}</template></span>
        </div>
        <div class="profile-actions">
          <button class="btn sm" @click="go('today')">今日任务</button>
          <button class="btn sm" @click="go('followup')">随访</button>
          <button class="btn sm" @click="go('report')">复盘</button>
          <button class="btn sm" @click="go('elder-print')">交接单</button>
          <button class="btn primary sm" @click="go('discharge-import')">导入出院小结</button>
        </div>
      </header>

      <p v-if="error" class="note danger">{{ error }}</p>
      <p v-if="toast" class="note">{{ toast }}</p>

      <div class="mag">
        <!-- 左：档案 + 评分卡 -->
        <div class="stack">
          <section class="card">
            <div class="card-head">
              <h3>基本情况</h3>
              <div style="display: flex; align-items: center; gap: 8px">
                <span
                  class="pill"
                  :class="elder.careStatus === 'HOSPITALIZED' ? 'danger' : elder.careStatus === 'CLOSED' ? 'quiet' : 'ok'"
                >{{ careStatusText }}</span>
                <button class="btn sm" @click="openEdit">编辑档案</button>
              </div>
            </div>
            <dl class="facts">
              <div><dt>联系电话</dt><dd>{{ elder.phone || '—' }}</dd></div>
              <div><dt>住址</dt><dd>{{ elder.address || '—' }}</dd></div>
              <div v-if="elder.careStatus === 'HOSPITALIZED' || elder.hospital || elder.bedNo">
                <dt>住院信息</dt>
                <dd>
                  {{ elder.hospital || '医院待补' }}
                  <template v-if="elder.department"> · {{ elder.department }}</template>
                  <template v-if="elder.bedNo"> · <strong>{{ elder.bedNo }} 床</strong></template>
                  <template v-if="elder.admitDate"> · 入院 {{ formatDate(elder.admitDate) }}</template>
                </dd>
              </div>
              <div><dt>照护人</dt><dd>{{ elder.caregiverName || '—' }} {{ elder.caregiverPhone ? `（${elder.caregiverPhone}）` : '' }}</dd></div>
              <div>
                <dt>基础病</dt>
                <dd>
                  <span v-for="c in elder.chronic" :key="c" class="pill quiet" style="margin-right: 6px">{{ c }}</span>
                  <span v-if="!elder.chronic.length" class="muted">未登记</span>
                </dd>
              </div>
              <div>
                <dt>过敏</dt>
                <dd>
                  <span v-for="c in elder.allergy" :key="c" class="pill danger" style="margin-right: 6px">{{ c }}</span>
                  <span v-if="!elder.allergy.length" class="muted">未登记</span>
                </dd>
              </div>
            </dl>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>住院记录</h3>
              <button class="btn sm" :disabled="admBusy" @click="openAdmission">登记住院</button>
            </div>
            <p class="muted" style="margin: 0 0 10px; font-size: 13px">
              床位只是"当前值"，这里留下每一次住院：哪家医院、哪个科、住了几天。
            </p>
            <ul class="admissions">
              <li v-for="a in admissions" :key="a.id">
                <div class="adm-main">
                  <strong>{{ a.hospital || '医院未填' }}</strong>
                  <span v-if="a.department"> · {{ a.department }}</span>
                  <span v-if="a.bedNo"> · {{ a.bedNo }} 床</span>
                  <span class="pill" :class="a.status === 'IN_HOSPITAL' ? 'danger' : 'ok'" style="margin-left: 8px">
                    {{ a.status === 'IN_HOSPITAL' ? '在院' : '已出院' }}
                  </span>
                </div>
                <div class="adm-sub">
                  {{ formatDate(a.admitDate) }} ~ {{ a.dischargeDate ? formatDate(a.dischargeDate) : '至今' }}
                  <template v-if="a.days"> · 共 {{ a.days }} 天</template>
                  <template v-if="a.reason"> · {{ a.reason }}</template>
                </div>
                <div class="row" style="gap: 8px; margin-top: 6px; flex-wrap: wrap">
                  <button class="btn sm" :disabled="admBusy" @click="openEditAdmission(a)">编辑</button>
                  <button
                    v-if="a.status === 'IN_HOSPITAL'"
                    class="btn sm primary"
                    :disabled="admBusy"
                    @click="finishAdmission(a)"
                  >办理出院</button>
                  <button class="btn sm" :disabled="admBusy" @click="viewAdmissionRecord(a)">出院小结</button>
                  <button class="btn sm ghost" :disabled="admBusy" @click="voidCurrent(a)">作废</button>
                </div>
              </li>
              <li v-if="!admissions.length" class="muted">还没有住院记录；老人再住院时点右上角登记一笔。</li>
            </ul>
          </section>
          <section class="card">
            <div class="card-head">
              <h3>{{ previewRecord ? '这条住院记录对应的出院小结' : '最近一次出院小结' }}</h3>
              <div class="row" style="gap: 8px; align-items: center">
                <span class="hint">{{ activeRecord ? formatDate(activeRecord.dischargeDate) : '还没有记录' }}</span>
                <button v-if="previewRecord" class="btn sm ghost" @click="previewRecord = null">看最近一次</button>
              </div>
            </div>
            <template v-if="activeRecord">
              <p class="dx">{{ activeRecord.diagnosisPrimary || '未填主要诊断' }}</p>
              <p class="muted" style="margin-top: 4px">
                {{ activeRecord.hospital || '医院待补' }}
                <template v-if="activeRecord.department"> · {{ activeRecord.department }}</template>
                · 入院 {{ formatDate(activeRecord.admitDate) }}
              </p>
              <div class="divider" />
              <div class="kicker">出院带药 {{ meds.length }} 种</div>
              <ul class="meds">
                <li v-for="(m, i) in meds" :key="i">
                  <strong>{{ m.name }}</strong>
                  <span class="muted">{{ [m.dose, m.frequency, m.route].filter(Boolean).join(' · ') }}</span>
                </li>
              </ul>
              <div class="row" style="margin-top: 12px">
                <span class="pill line">{{ activeRecord.extractStatus }}</span>
                <span class="muted">抽取状态</span>
                <span class="spacer" />
                <button class="btn sm" @click="go('discharge-import')">查看/重新抽取</button>
              </div>
            </template>
            <p v-else class="muted">还没有出院小结。先导入一份，后续的风险与计划才有依据。</p>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>风险评分卡</h3>
              <button class="btn sm" :disabled="busy === 'assess'" @click="reassess">
                {{ busy === 'assess' ? '重算中…' : '重新评估' }}
              </button>
            </div>
            <template v-if="risk">
              <div class="score-row">
                <div class="score num">{{ risk.score }}</div>
                <div>
                  <div class="pill" :class="levelTone(risk.level) === 'ok' ? '' : levelTone(risk.level)">{{ risk.levelLabel }}</div>
                  <div class="muted" style="margin-top: 4px">{{ formatDateTime(risk.createdAt) }} · {{ modeLabel(risk.assessor) }}</div>
                </div>
              </div>
              <ul class="factors">
                <li v-for="f in risk.factors" :key="f.code">
                  <span class="f-score num">+{{ f.score }}</span>
                  <div>
                    <strong>{{ f.label }}</strong>
                    <span class="muted"> · {{ f.evidence }}</span>
                  </div>
                </li>
              </ul>
              <div class="note" style="margin-top: 12px">{{ risk.explanation }}</div>
            </template>
            <p v-else class="muted">还没有评分。评分由代码按提取字段计算，模型只负责把它讲给家属听。</p>
          </section>
        </div>

        <!-- 右：计划 + 随访 + 预警 -->
        <div class="stack">
          <section class="card">
            <div class="card-head">
              <h3>30 天照护计划</h3>
              <span class="hint">{{ issuedPlan ? '已签发' : draftPlan ? '有草案待签发' : '未生成' }}</span>
            </div>
            <template v-if="issuedPlan || draftPlan">
              <div class="plan-box">
                <div class="row">
                  <span class="pill" :class="issuedPlan ? '' : 'warn'">
                    {{ planStatusLabel((issuedPlan ?? draftPlan)?.status) }}
                  </span>
                  <span class="muted">v{{ (issuedPlan ?? draftPlan)?.version }}</span>
                  <span class="spacer" />
                  <span class="muted">{{ formatDate((issuedPlan ?? draftPlan)?.startDate) }} 起 30 天</span>
                </div>
                <ul class="goals">
                  <li v-for="(g, i) in (issuedPlan ?? draftPlan)?.goals ?? []" :key="i">{{ g.title }}</li>
                </ul>
                <div class="followup-line">
                  {{ channelLabel((issuedPlan ?? draftPlan)?.followup?.channel) }}
                  · 每 {{ (issuedPlan ?? draftPlan)?.followup?.interval_days ?? 7 }} 天一次
                </div>
              </div>
              <div class="row" style="margin-top: 12px">
                <button class="btn sm" @click="go('plan-review', { planId: String((issuedPlan ?? draftPlan)?.id) })">
                  查看计划全文
                </button>
                <button
                  v-if="draftPlan && !issuedPlan && auth.isNurse"
                  class="btn primary sm"
                  :disabled="busy === 'issue'"
                  @click="sign(draftPlan)"
                >
                  {{ busy === 'issue' ? '签发中…' : '签发计划' }}
                </button>
                <!-- 医生没有签发权：草案归护士签，医生只给会诊意见 -->
                <span v-else-if="draftPlan && !issuedPlan" class="pill warn" title="签发是社区护士的职责">
                  待社区护士签发
                </span>
                <span class="spacer" />
                <button class="btn sm" :disabled="busy === 'plan'" @click="makePlan">
                  {{ busy === 'plan' ? '生成中…' : '重新生成草案' }}
                </button>
              </div>
            </template>
            <template v-else>
              <p class="muted">还没有计划。生成草案后需要护士签发，任务才会下发到家属端。</p>
              <button class="btn primary block" style="margin-top: 12px" :disabled="busy === 'plan'" @click="makePlan">
                {{ busy === 'plan' ? 'AI 正在整理 30 天计划…' : '生成 30 天计划草案' }}
              </button>
            </template>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>随访排期</h3>
              <button class="btn sm" :disabled="busy === 'followup'" @click="arrangeFollowup">
                {{ busy === 'followup' ? '排期中…' : '按计划排下一次' }}
              </button>
            </div>
            <div v-if="nextFollowup" class="next-followup">
              <div class="pill warn">{{ followupStatusLabel(nextFollowup.status) }}</div>
              <div>
                <strong>{{ formatDate(nextFollowup.plannedDate) }}</strong>
                <span class="muted"> · {{ channelLabel(nextFollowup.channel) }}</span>
              </div>
            </div>
            <ul class="timeline">
              <li v-for="f in followups" :key="f.id">
                <span class="t-dot" :class="f.status.toLowerCase()" />
                <div>
                  <div>{{ formatDate(f.plannedDate) }} · {{ followupStatusLabel(f.status) }}</div>
                  <div class="muted small">
                    {{ channelLabel(f.channel) }}
                    <template v-if="f.endedAt"> · 结束于 {{ formatDateTime(f.endedAt) }}</template>
                  </div>
                </div>
              </li>
              <li v-if="!followups.length" class="muted">还没有随访会话。</li>
            </ul>
            <button class="btn block" style="margin-top: 10px" @click="go('followup')">进入随访会话</button>
          </section>

          <section class="card">
            <div class="card-head">
              <h3>这位老人的预警</h3>
              <span class="hint">{{ openAlerts.length }} 条未关闭</span>
            </div>
            <ul class="alert-list">
              <li v-for="a in alerts.slice(0, 4)" :key="a.id">
                <span class="dot" :class="a.status === 'OPEN' ? 'hot' : 'cool'" />
                <div>
                  <div class="row" style="gap: 6px">
                    <strong class="small">{{ a.title }}</strong>
                    <span class="pill" :class="a.level === 'HIGH' ? 'danger' : 'warn'">{{ a.level === 'HIGH' ? '高危' : '提醒' }}</span>
                  </div>
                  <p class="muted small">{{ a.detail }}</p>
                  <p class="muted small">{{ formatDateTime(a.createdAt) }}</p>
                </div>
              </li>
              <li v-if="!alerts.length" class="muted">没有预警，保持观察。</li>
            </ul>
            <button class="btn block" style="margin-top: 10px" @click="router.push({ name: 'alerts' })">去处理预警</button>
          </section>
        </div>
      </div>
    </template>
    <p v-else class="empty">{{ error || '没有找到这位老人' }}</p>
  </div>

  <!-- 档案编辑：护士边问边补，只改填过的字段 -->
  <div v-if="editing" class="sheet-mask" @click.self="editing = false">
    <div class="sheet">
      <div class="sheet-head">
        <h3>编辑档案 · {{ elder?.name }}</h3>
        <button class="btn sm" @click="editing = false">关闭</button>
      </div>
      <div class="sheet-body">
        <div class="grid2">
          <label class="field"><span>姓名</span><input v-model="draft.name" class="input" /></label>
          <label class="field">
            <span>性别</span>
            <select v-model="draft.gender" class="select">
              <option value="FEMALE">女</option>
              <option value="MALE">男</option>
              <option value="UNKNOWN">不详</option>
            </select>
          </label>
          <label class="field"><span>出生日期</span><input v-model="draft.birthDate" class="input" type="date" /></label>
          <label class="field"><span>联系电话</span><input v-model="draft.phone" class="input" /></label>
          <label class="field"><span>住址</span><input v-model="draft.address" class="input" /></label>
          <label class="field">
            <span>居住情况</span>
            <select v-model="draft.livingStatus" class="select">
              <option value="ALONE">独居</option>
              <option value="SPOUSE">与配偶同住</option>
              <option value="CHILDREN">与子女同住</option>
              <option value="FACILITY">养老机构</option>
              <option value="UNKNOWN">不详</option>
            </select>
          </label>
          <label class="field"><span>照护人</span><input v-model="draft.caregiverName" class="input" /></label>
          <label class="field"><span>照护人电话</span><input v-model="draft.caregiverPhone" class="input" /></label>
          <label class="field wide"><span>基础病（逗号分隔）</span><input v-model="draft.chronicText" class="input" /></label>
          <label class="field wide"><span>过敏（逗号分隔）</span><input v-model="draft.allergyText" class="input" /></label>
        </div>

        <div class="sheet-sub">
          <h4>在院 / 随访状态</h4>
          <p class="muted small">住院中的老人不参与"漏做"催办：家里的任务保留，但不会因为没人打卡而升级预警。</p>
        </div>
        <div class="grid2">
          <label class="field">
            <span>当前状态</span>
            <select v-model="draft.careStatus" class="select">
              <option value="HOME">居家随访</option>
              <option value="HOSPITALIZED">住院中</option>
              <option value="CLOSED">已结案</option>
            </select>
          </label>
          <label class="field"><span>床号</span><input v-model="draft.bedNo" class="input" placeholder="如 12-3" /></label>
          <label class="field"><span>医院</span><input v-model="draft.hospital" class="input" /></label>
          <label class="field"><span>科室</span><input v-model="draft.department" class="input" /></label>
          <label class="field"><span>入院日期</span><input v-model="draft.admitDate" class="input" type="date" /></label>
        </div>
      </div>
      <div class="sheet-foot">
        <button class="btn" @click="editing = false">取消</button>
        <button class="btn primary" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </div>
  </div>

  <!-- 登记一次住院：留空出院日期＝现在在院 -->
  <div v-if="admOpen" class="sheet-mask" @click.self="admOpen = false">
    <div class="sheet">
      <div class="sheet-head">
        <h3>{{ admEditing ? '编辑住院记录' : '登记住院' }} · {{ elder?.name }}</h3>
        <button class="btn sm" @click="admOpen = false">关闭</button>
      </div>
      <div class="sheet-body">
        <div class="sheet-sub">
          <h4>这一次住院</h4>
          <p class="muted small">
            {{ admEditing ? '改完保存即生效；把出院日期清空会重新标记为在院，补上出院日期即视为已出院。' : '只填入院日期＝现在在院（会暂停逾期催办）；连出院日期一起填＝补登一次已经过去的住院。' }}
          </p>
        </div>
        <div class="grid2">
          <label class="field"><span>医院</span><input v-model="admDraft.hospital" class="input" placeholder="如 市第二人民医院" /></label>
          <label class="field"><span>科室</span><input v-model="admDraft.department" class="input" placeholder="如 骨科" /></label>
          <label class="field"><span>床号</span><input v-model="admDraft.bedNo" class="input" placeholder="如 8-2" /></label>
          <label class="field"><span>入院日期</span><input v-model="admDraft.admitDate" class="input" type="date" /></label>
          <label class="field"><span>出院日期（补登历史时填）</span><input v-model="admDraft.dischargeDate" class="input" type="date" /></label>
          <label class="field wide"><span>入院原因 / 诊断（选填）</span><input v-model="admDraft.reason" class="input" /></label>
        </div>
      </div>
      <div class="sheet-foot">
        <button class="btn" @click="admOpen = false">取消</button>
        <button class="btn primary" :disabled="admBusy" @click="submitAdmission">{{ admBusy ? '保存中…' : '保存' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 杂志式报头：姓名、年龄、风险分，一条粗线收口 ---------- */
.profile {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 6px 24px;
  padding-bottom: 18px;
  margin-bottom: 22px;
  border-bottom: 3px solid var(--primary);
}
.profile-main .kicker { color: var(--ink-mute); }
.profile-name {
  display: flex; align-items: baseline; gap: 12px;
  margin-top: 8px;
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: 44px; line-height: 1.1; letter-spacing: 0.02em;
}
.profile-age { font-family: inherit; font-size: 15px; font-weight: 500; letter-spacing: 0; color: var(--ink-mute); }
.dateline { margin-top: 10px; font-size: 13.5px; color: var(--ink-soft); }

/* 风险分当"报头数字"用：一眼看见几档 */
.profile-risk { display: flex; flex-direction: column; align-items: flex-end; gap: 1px; padding-right: 4px; }
.profile-risk .k { font-size: 11px; letter-spacing: 0.16em; color: var(--ink-mute); text-transform: uppercase; }
.profile-risk strong { font-size: 48px; line-height: 1; letter-spacing: -0.045em; color: var(--primary); }
.profile-risk .l { font-size: 12.5px; color: var(--ink-soft); }
.profile-risk.danger strong { color: var(--danger); }
.profile-risk.warn strong { color: var(--amber); }

.profile-actions { grid-column: 1 / -1; display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }

/* ---------- 正文双栏：左是"这一页在讲什么"，右是"接下来怎么做" ---------- */
.mag { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 20px; align-items: start; }
.mag > .stack:last-child { position: sticky; top: 18px; }

@media (max-width: 1080px) {
  .mag { grid-template-columns: 1fr; }
  .mag > .stack:last-child { position: static; }
  .profile { grid-template-columns: 1fr; }
  .profile-risk { align-items: flex-start; flex-direction: row; gap: 10px; align-items: baseline; }
  .profile-name { font-size: 34px; }
}

.facts { margin: 0; display: grid; gap: 10px; }
.facts > div { display: grid; grid-template-columns: 88px 1fr; gap: 10px; align-items: start; }
.facts dt { color: var(--ink-mute); font-size: 13px; }
.facts dd { margin: 0; font-size: 14px; }

.dx { font-size: 15.5px; font-weight: 500; }
.meds { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 7px; }
.meds li { display: flex; flex-direction: column; gap: 1px; padding-left: 12px; position: relative; }
.meds li::before { content: ''; position: absolute; left: 0; top: 9px; width: 5px; height: 5px; border-radius: 50%; background: var(--line); }

.score-row { display: flex; align-items: center; gap: 16px; }
.score { font-size: 44px; font-weight: 600; line-height: 1; color: var(--primary-deep); letter-spacing: -0.03em; }
.factors { list-style: none; margin: 14px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.factors li { display: flex; gap: 10px; align-items: flex-start; font-size: 13.5px; }
.f-score {
  flex: none; min-width: 34px; height: 22px; border-radius: 6px;
  background: var(--amber-soft); color: var(--amber);
  display: grid; place-items: center; font-size: 12.5px; font-weight: 600;
}

.plan-box { border: 1px solid var(--line); border-radius: 12px; padding: 14px; background: #fbfdfc; }
.goals { margin: 10px 0 8px; padding-left: 18px; display: flex; flex-direction: column; gap: 5px; font-size: 13.5px; }
.followup-line { font-size: 13px; color: var(--primary-deep); }

.next-followup { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; max-height: 220px; overflow: auto; }
.timeline li { display: flex; gap: 10px; align-items: flex-start; font-size: 13.5px; }
.t-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 7px; flex: none; background: #c9d6d0; }
.t-dot.planned { background: var(--amber); }
.t-dot.done { background: #2f8f74; }
.t-dot.missed { background: var(--danger); }

.alert-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.alert-list li { display: flex; gap: 10px; align-items: flex-start; }
.dot.hot { background: var(--danger); margin-top: 6px; }
.dot.cool { background: #b9c8c2; margin-top: 6px; }
/* ---------- 档案编辑抽屉 ---------- */
.sheet-mask {
  position: fixed; inset: 0; background: rgba(16, 44, 37, 0.38);
  display: flex; align-items: flex-start; justify-content: center;
  padding: 40px 16px; z-index: 40; overflow: auto;
}
.sheet {
  width: min(760px, 100%); background: var(--card);
  border-radius: 18px; box-shadow: 0 30px 70px -30px rgba(12, 40, 32, 0.45);
  display: flex; flex-direction: column;
}
.sheet-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid var(--line); }
.sheet-head h3 { margin: 0; font-size: 17px; }
.sheet-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 6px; }
.sheet-sub { margin: 14px 0 4px; padding-top: 10px; border-top: 1px dashed var(--line); }
.sheet-sub h4 { margin: 0 0 2px; font-size: 14px; }
.sheet-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--line); }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.grid2 .wide { grid-column: 1 / -1; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field > span { font-size: 12.5px; color: var(--ink-soft); }
@media (max-width: 640px) { .grid2 { grid-template-columns: 1fr; } }
.admissions { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.admissions li {
  padding: 10px 12px; border-radius: 10px; background: #fbf9f2;
  border: 1px solid var(--line); display: flex; flex-direction: column; gap: 4px;
}
.admissions .adm-main { font-size: 14.5px; color: var(--ink); }
.admissions .adm-sub { font-size: 12.5px; color: var(--ink-soft); }
.admissions .btn.sm { align-self: flex-start; margin-top: 4px; }
</style>
