/**
 * 医养接力站 · API 封装
 *
 * 后端统一返回 { code, message, data }，code === 0 表示成功。
 * 这一层只做两件事：把信封拆干净、把错误翻成人话；
 * 业务字段的"翻译"（比如 MEDIUM → 中风险）放在 utils/format.ts。
 */

const BASE = '/api/v1'
const TOKEN_KEY = 'cr.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

interface Envelope<T> {
  code: number
  message: string
  data: T | null
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function parseBody<T>(resp: Response): Promise<T> {
  const text = await resp.text()
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = null
    }
  }
  const envelope = body as Envelope<T> | null
  if (envelope && typeof envelope.code === 'number') {
    if (envelope.code !== 0) {
      throw new ApiError(envelope.code, envelope.message || '请求失败')
    }
    return envelope.data as T
  }
  if (!resp.ok) {
    throw new ApiError(resp.status, resp.statusText || '请求失败')
  }
  return body as T
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...((init.headers as Record<string, string> | undefined) ?? {}),
    },
  })
  return parseBody<T>(resp)
}

export const apiGet = <T>(path: string) => request<T>(path, { method: 'GET' })

export const apiPost = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export const apiPut = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })

export const apiPatch = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) })

/* ---------------------------------------------------------------- 账号 */

export type Role = 'ADMIN' | 'NURSE' | 'DOCTOR' | 'FAMILY' | string

export interface UserView {
  id: number
  orgName?: string | null
  nickname: string
  phone: string
  role: Role
}

export interface AuthResponse {
  token: string
  expiresInHours: number
  user: UserView
}

export const authLogin = (payload: { phone: string; password: string }) =>
  apiPost<AuthResponse>('/auth/login', payload)
export const authMe = () => apiGet<UserView>('/auth/me')

/* ---------------------------------------------------------------- 档案 */

export interface Admission {
  id: number
  elderId: number
  hospital?: string | null
  department?: string | null
  bedNo?: string | null
  admitDate: string
  dischargeDate?: string | null
  reason?: string | null
  status: 'IN_HOSPITAL' | 'DISCHARGED' | 'VOID'
  days?: number | null
  /** 对应的出院小结 id（住院记录 ↔ 出院小结的关联） */
  dischargeRecordId?: number | null
  voidReason?: string | null
  voidedAt?: string | null
  createdAt?: string
}

export interface AdmissionStat {
  elderId: number
  count: number
  days: number
}

export interface Elder {
  id: number
  code: string
  name: string
  gender: string
  age: number | null
  birthDate?: string | null
  phone?: string
  address?: string
  livingStatus: string
  caregiverName?: string
  caregiverPhone?: string
  chronic: string[]
  allergy: string[]
  nurseId?: number | null
  status: string
  /** HOME 居家随访 / HOSPITALIZED 住院中 / CLOSED 已结案 */
  careStatus?: string | null
  bedNo?: string | null
  hospital?: string | null
  department?: string | null
  admitDate?: string | null
}

/** 改档案：只传要改的字段（后端按字段打补丁） */
export interface UpdateElder {
  name?: string
  gender?: string
  birthDate?: string
  phone?: string
  address?: string
  livingStatus?: string
  caregiverName?: string
  caregiverPhone?: string
  chronic?: string[]
  allergy?: string[]
  nurseId?: number
  careStatus?: string
  bedNo?: string
  hospital?: string
  department?: string
  admitDate?: string
}

export const updateElder = (id: number, payload: UpdateElder) => apiPatch<Elder>(`/elders/${id}`, payload)

export const listAdmissions = (elderId: number) => apiGet<Admission[]>(`/elders/${elderId}/admissions`)
export const addAdmission = (elderId: number, payload: Record<string, unknown>) =>
  apiPost<Admission>(`/elders/${elderId}/admissions`, payload)
export const dischargeAdmission = (id: number, dischargeDate: string) =>
  apiPost<Admission>(`/admissions/${id}/discharge`, { dischargeDate })
export const updateAdmission = (id: number, payload: Record<string, unknown>) =>
  apiPatch<Admission>(`/admissions/${id}`, payload)
export const voidAdmission = (id: number, reason: string) =>
  apiPost<Admission>(`/admissions/${id}/void`, { reason })
export const admissionStats = () => apiGet<AdmissionStat[]>('/admissions/stats')
export const listElders = () => apiGet<Elder[]>('/elders')
export const getElder = (id: number) => apiGet<Elder>(`/elders/${id}`)

export interface CreateElder {
  name: string
  gender?: string
  birthDate?: string
  phone?: string
  address?: string
  livingStatus?: string
  caregiverName?: string
  caregiverPhone?: string
  chronic?: string[]
  allergy?: string[]
}

export const createElder = (payload: CreateElder) => apiPost<Elder>('/elders', payload)

/* ---------------------------------------------------------- 交接环 · 抽取 */

export interface MedicationItem {
  name: string
  dose?: string | null
  frequency?: string | null
  route?: string | null
  duration?: string | null
  note?: string | null
}

export interface DischargeData {
  diagnoses?: string[] | string | null
  surgeries?: unknown
  medications?: MedicationItem[]
  tubes?: unknown
  wound?: { present?: boolean; site?: string | null; care?: string | null; dressing_change?: string | null } | null
  diet?: string[] | string | null
  activityLimit?: string[] | string | null
  followup?: { department?: string | null; items?: string[]; date?: string | null; contact?: string | null } | null
  redFlags?: Array<{ symptom?: string; action?: string }> | unknown
  confidence?: Record<string, number>
}

export interface DischargeRecord {
  id: number
  elderId: number
  hospital?: string
  department?: string
  admitDate?: string
  dischargeDate?: string
  diagnosisPrimary?: string
  extractStatus: 'PENDING' | 'PARSED' | 'MANUAL' | string
  sourceUrl?: string
  createdAt?: string
  data?: DischargeData
}

export interface ExtractResult {
  recordId: number
  elderId: number
  extractStatus: string
  mode: 'MODEL' | 'LOCAL' | string
  /** 后端字段名就是 data（ExtractResult.data），这里保持同名，别再翻译成 structured */
  data?: DischargeData | null
  missingFields?: string[]
  confidences?: Record<string, number>
  notice?: string
  latencyMs?: number
}

/**
 * 后端结构化档案用的是 snake_case（提示词里就是这么写的，方便对照原文），
 * 前端模板用 camelCase。只在这一层翻译一次，页面里不再两套键名混用。
 */
export function normalizeDischargeData(raw: DischargeData | null | undefined): DischargeData {
  const d = (raw ?? {}) as DischargeData & { activity_limit?: unknown; red_flags?: unknown }
  return {
    ...d,
    activityLimit: (d.activityLimit ?? d.activity_limit ?? []) as string[],
    redFlags: (d.redFlags ?? d.red_flags ?? []) as DischargeData['redFlags'],
  }
}

function normalizeRecord(record: DischargeRecord): DischargeRecord {
  return { ...record, data: normalizeDischargeData(record.data) }
}

export const extractDischarge = async (payload: { elderId: number; text: string; sourceUrl?: string }) => {
  const result = await apiPost<ExtractResult>('/discharge/extract', payload)
  return { ...result, data: normalizeDischargeData(result.data) }
}

export const listDischarge = async (elderId: number) =>
  (await apiGet<DischargeRecord[]>(`/discharge?elderId=${elderId}`)).map(normalizeRecord)

export const getDischarge = async (id: number) => normalizeRecord(await apiGet<DischargeRecord>(`/discharge/${id}`))

/** 人工填写 / 修订结构化档案（本地模式没有模型时的正道） */
export const saveDischargeStructured = async (id: number, data: Record<string, unknown>) => {
  const result = await apiPut<ExtractResult>(`/discharge/${id}/structured`, data)
  return { ...result, data: normalizeDischargeData(result.data) }
}

/* ------------------------------------------------------- 交接环 · 评分卡 */

export interface RiskFactor {
  code: string
  label: string
  score: number
  evidence: string
}

export interface RiskAssessment {
  id: number
  elderId: number
  recordId?: number | null
  score: number
  level: 'HIGH' | 'MEDIUM' | 'LOW' | string
  levelLabel: string
  factors: RiskFactor[]
  explanation: string
  assessor: 'RULE' | 'MODEL' | string
  createdAt: string
}

export const assessRisk = (payload: { elderId: number; recordId?: number | null }) =>
  apiPost<RiskAssessment>('/risk/assess', payload)
export const latestRisk = (elderId: number) => apiGet<RiskAssessment | null>(`/risk/latest?elderId=${elderId}`)

/* --------------------------------------------------------- 交接环 · 计划 */

export interface PlanGoal {
  title: string
  metric?: string
  priority?: string
}

export interface CarePlan {
  id: number
  elderId: number
  assessmentId?: number | null
  version: number
  status: 'DRAFT' | 'ISSUED' | 'CLOSED' | string
  startDate: string
  endDate: string
  goals: PlanGoal[]
  redFlags: string[]
  followup: { interval_days?: number; channel?: string; reason?: string }
  aiSummary?: string
  issuedBy?: number | null
  issuedAt?: string | null
}

export interface CareTask {
  id: number
  taskDate: string
  type: string
  title: string
  detail?: string
  timeSlot: string
  evidence: string
  status: string
  source: string
}

export const generatePlan = (payload: { elderId: number; recordId?: number | null }) =>
  apiPost<CarePlan & { taskCount?: number }>('/plans/generate', payload)
export const issuePlan = (id: number) => apiPost<CarePlan>(`/plans/${id}/issue`)
export const getPlan = (id: number) => apiGet<CarePlan>(`/plans/${id}`)
export const listPlans = (elderId: number) => apiGet<CarePlan[]>(`/plans?elderId=${elderId}`)
export const listTasks = (id: number) => apiGet<CareTask[]>(`/plans/${id}/tasks`)

/* ------------------------------------------------------------- 执行环 */

export interface TodayTask {
  taskId: number
  type: string
  title: string
  detail?: string
  timeSlot: string
  evidence: string
  status: string
  checked: boolean
}

export interface TodayView {
  elderId: number
  elderName: string
  date: string
  totalTasks: number
  doneTasks: number
  tasks: TodayTask[]
}

export interface SubmitCheckin {
  elderId: number
  taskId?: number
  kind?: string
  valueJson?: string
  text?: string
  photoUrl?: string
  voiceUrl?: string
}

export interface CheckinResult {
  checkinId?: number
  status: 'NORMAL' | 'ABNORMAL' | string
  level?: string
  judgeSource?: 'RULE' | 'MODEL' | 'MANUAL' | string
  note?: string
  alertId?: number | null
  ticketId?: number | null
}

export const todayTasks = (elderId: number) => apiGet<TodayView>(`/checkins/today?elderId=${elderId}`)
export const submitCheckin = (payload: SubmitCheckin) => apiPost<CheckinResult>('/checkins', payload)

/* --------------------------------------------------------- 预警与工单 */

export interface AlertEvent {
  id: number
  elderId: number
  source: string
  ruleCode?: string
  level: 'HIGH' | 'MEDIUM' | string
  title: string
  detail: string
  status: 'OPEN' | 'CLOSED' | string
  createdAt: string
  closedAt?: string | null
}

export interface Ticket {
  id: number
  elderId: number
  alertId?: number | null
  level: string
  reason: string
  status: 'PENDING' | 'DONE' | string
  note?: string
  createdAt: string
}

export type Alert = AlertEvent

export const listAlerts = (status?: string) => apiGet<AlertEvent[]>(`/alerts${status ? `?status=${status}` : ''}`)
export const closeAlert = (id: number, note?: string) => apiPost<AlertEvent>(`/alerts/${id}/close`, { note })
export const listTickets = (status?: string) => apiGet<Ticket[]>(`/tickets${status ? `?status=${status}` : ''}`)
export const handleTicket = (id: number, note?: string) => apiPost<Ticket>(`/tickets/${id}/handle`, { note })

/* ------------------------------------------------------------- 随访环 */

export interface FollowupSession {
  id: number
  elderId: number
  elderName?: string
  elderCode?: string
  channel: string
  plannedDate: string
  status: 'PLANNED' | 'DONE' | 'MISSED' | string
  mode?: string
  questionIndex?: number
  transcript?: Array<{ role: 'agent' | 'elder' | 'nurse' | 'family'; text: string; at?: string }>
  startedAt?: string | null
  endedAt?: string | null
}

export const scheduleFollowup = (payload: { elderId: number; plannedDate?: string }) =>
  apiPost<FollowupSession>('/followups/schedule', payload)
export const listFollowups = (elderId: number) => apiGet<FollowupSession[]>(`/followups?elderId=${elderId}`)
export const dueFollowups = () => apiGet<FollowupSession[]>('/followups/due')
export const startFollowup = (id: number) => apiPost<FollowupSession>(`/followups/${id}/start`)
export const sendFollowup = (id: number, text: string) =>
  apiPost<FollowupSession & { reply?: string; ended?: boolean }>(`/followups/${id}/messages`, { text })
export const finishFollowup = (id: number) =>
  apiPost<{ redFlag: boolean; summary: string; payload?: unknown }>(`/followups/${id}/finish`)

/* ------------------------------------------------------------- 复盘环 */

export interface Report {
  id: number
  elderId: number
  type: string
  periodStart: string
  periodEnd: string
  familyMd?: string
  clinicalMd?: string
  createdAt?: string
}

export interface Overview {
  date: string
  elderCount: number
  riskLevels: Record<string, number>
  dueFollowups: number
  openAlerts: number
  pendingTickets: number
  avgCompliance: number
  elders: Array<{
    elderId: number
    name: string
    code: string
    level: string
    score: number
    compliance: number
    openAlerts: number
    pendingTickets: number
    redFollowups: number
  }>
}

export const weeklyReport = (payload: { elderId: number; weekEnd?: string }) =>
  apiPost<Report>('/reports/weekly', payload)
export const listReports = (elderId: number) => apiGet<Report[]>(`/reports?elderId=${elderId}`)
export const getReport = (id: number) => apiGet<Report>(`/reports/${id}`)
export const outcomeReport = (elderId: number) =>
  apiPost<{ markdown?: string; content?: string } | string>('/reports/outcome', { elderId })
export const fetchOverview = () => apiGet<Overview>('/reports/overview')

/* ------------------------------------------------------------ 系统设置 */

export interface AiSettings {
  baseUrl: string
  model: string
  timeoutSeconds: number
  configured: boolean
  maskedKey: string
  keySource: 'DATABASE' | 'ENV' | 'NONE' | string
}

export interface AiTestResult {
  ok: boolean
  message?: string
  model?: string
  latencyMs?: number
  reply?: string
}

export const getAiSettings = () => apiGet<AiSettings>('/settings/ai')

export const saveAiSettings = (payload: {
  baseUrl?: string
  apiKey?: string
  model?: string
  timeoutSeconds?: number
  clearKey?: boolean
}) => apiPut<AiSettings>('/settings/ai', payload)

export const testAiSettings = (payload?: { baseUrl?: string; apiKey?: string; model?: string; timeoutSeconds?: number }) =>
  apiPost<AiTestResult>('/settings/ai/test', payload ?? {})
/* ---------------- AI 调用记录 ---------------- */

export interface AiCallRecord {
  id: number
  scene: string
  mode: string
  model?: string | null
  status: string
  latencyMs?: number | null
  adopted?: number | null
  error?: string | null
  inputChars: number
  outputChars: number
  userId?: number | null
  createdAt: string
}

export const listAiRecords = (params?: { scene?: string; limit?: number }) => {
  const q = new URLSearchParams()
  if (params?.scene) q.set('scene', params.scene)
  q.set('limit', String(params?.limit ?? 50))
  return apiGet<AiCallRecord[]>(`/ai/records?${q.toString()}`)
}

/* ---------------- 计划会诊留言 ---------------- */

export interface PlanComment {
  id: number
  planId: number
  authorRole: string
  authorName?: string | null
  content: string
  createdAt: string
}

export const listPlanComments = (planId: number) => apiGet<PlanComment[]>(`/plans/${planId}/comments`)

export const addPlanComment = (planId: number, content: string) =>
  apiPost<PlanComment>(`/plans/${planId}/comments`, { content })

/* ---------------- 逾期扫描（演示用） ---------------- */

export const sweepTasks = () =>
  apiPost<{ checkedAt: string; pendingScanned: number; markedMissed: number; alertsRaised: number; ticketsRaised: number }>(
    '/tasks/sweep',
    {},
  )