/**
 * 日期与业务状态文案的集中处理。
 * 页面、报告、家属端对同一个状态必须用同一套说法，所以只在这里定义一次。
 */

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function parseDate(value?: string | null): Date | null {
  if (!value) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 2026-11-04 */
export function formatDate(value?: string | null): string {
  const date = parseDate(value)
  if (!date) {
    return '—'
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 11月4日 周三 */
export function formatFriendlyDate(value?: string | null): string {
  const date = parseDate(value)
  if (!date) {
    return '—'
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${WEEKDAYS[date.getDay()]}`
}

/** 11月4日 19:41 */
export function formatDateTime(value?: string | null): string {
  const date = parseDate(value)
  if (!date) {
    return '—'
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 19:41 */
export function formatClock(value?: string | null): string {
  const date = parseDate(value)
  if (!date) {
    return ''
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function todayString(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export function daysAgoString(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() - days)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 相对时间：刚刚 / 12 分钟前 / 3 小时前 / 11月4日 */
export function timeAgo(value?: string | null): string {
  const date = parseDate(value)
  if (!date) {
    return '—'
  }
  const diff = Date.now() - date.getTime()
  const minute = 60 * 1000
  if (diff < minute) {
    return '刚刚'
  }
  if (diff < 60 * minute) {
    return `${Math.floor(diff / minute)} 分钟前`
  }
  if (diff < 24 * 60 * minute) {
    return `${Math.floor(diff / (60 * minute))} 小时前`
  }
  return formatDateTime(value)
}

/* ------------------------------------------------------------ 业务标签 */

export const LEVEL_LABELS: Record<string, string> = {
  HIGH: '高风险',
  MEDIUM: '中风险',
  LOW: '低风险',
  NONE: '未评估',
}

export function levelLabel(level?: string | null): string {
  return LEVEL_LABELS[level ?? ''] ?? '未评估'
}

/** 风险等级 → 视觉基调：高=警示、中=提醒、低=平稳 */
export function levelTone(level?: string | null): 'danger' | 'warn' | 'quiet' | 'ok' {
  switch (level) {
    case 'HIGH':
      return 'danger'
    case 'MEDIUM':
      return 'warn'
    case 'LOW':
      return 'ok'
    default:
      return 'quiet'
  }
}

export const LIVING_LABELS: Record<string, string> = {
  ALONE: '独居',
  CHILDREN: '与子女同住',
  SPOUSE: '与配偶同住',
  FACILITY: '养老机构',
  UNKNOWN: '居住情况待补',
}

export const GENDER_LABELS: Record<string, string> = {
  MALE: '男',
  FEMALE: '女',
  UNKNOWN: '未填',
}

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: '管理员',
  NURSE: '社区护士',
  DOCTOR: '家庭医生',
  FAMILY: '家属',
}

export const TASK_TYPE_LABELS: Record<string, string> = {
  MED: '用药',
  VITAL: '体征测量',
  REHAB: '康复活动',
  DIET: '饮食',
  WOUND: '伤口/管路',
  SYMPTOM: '症状观察',
}

export const TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '上午',
  NOON: '中午',
  EVENING: '晚上',
  ANYTIME: '任意时间',
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: '待完成',
  DONE: '已完成',
  MISSED: '已漏做',
  SKIPPED: '已跳过',
}

export const PLAN_STATUS_LABELS: Record<string, string> = {
  DRAFT: '待签发',
  ISSUED: '已签发',
  CLOSED: '已结束',
}

export const FOLLOWUP_STATUS_LABELS: Record<string, string> = {
  PLANNED: '待随访',
  DONE: '已完成',
  MISSED: '已逾期',
}

export const CHANNEL_LABELS: Record<string, string> = {
  PHONE: '电话随访',
  WECHAT: '微信随访',
  VISIT: '上门随访',
  AI_CALL: 'AI 电话随访',
}

export const MODE_LABELS: Record<string, string> = {
  MODEL: '模型生成',
  LOCAL: '本地规则',
  RULE: '规则计算',
  MANUAL: '人工填写',
}

export const ALERT_SOURCE_LABELS: Record<string, string> = {
  CHECKIN: '打卡触发',
  FOLLOWUP: '随访触发',
  RULE: '规则判定',
  MODEL: '模型识别',
  MANUAL: '人工登记',
  SYSTEM: '系统',
}

export const TICKET_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处置',
  PROCESSING: '处理中',
  DONE: '已处置',
  CLOSED: '已关闭',
}

export const REPORT_TYPE_LABELS: Record<string, string> = {
  WEEKLY: '周复盘',
  MONTHLY: '月复盘',
  CLOSURE: '30 天结局',
}

export function labelOf(map: Record<string, string>, key?: string | null): string {
  if (!key) {
    return '—'
  }
  return map[key] ?? key
}

export const genderLabel = (v?: string | null) => labelOf(GENDER_LABELS, v)
export const livingLabel = (v?: string | null) => labelOf(LIVING_LABELS, v)
export const roleLabel = (v?: string | null) => labelOf(ROLE_LABELS, v)
export const taskTypeLabel = (v?: string | null) => labelOf(TASK_TYPE_LABELS, v)
export const timeSlotLabel = (v?: string | null) => labelOf(TIME_SLOT_LABELS, v)
export const taskStatusLabel = (v?: string | null) => labelOf(TASK_STATUS_LABELS, v)
export const planStatusLabel = (v?: string | null) => labelOf(PLAN_STATUS_LABELS, v)
export const followupStatusLabel = (v?: string | null) => labelOf(FOLLOWUP_STATUS_LABELS, v)
export const channelLabel = (v?: string | null) => labelOf(CHANNEL_LABELS, v)
export const modeLabel = (v?: string | null) => labelOf(MODE_LABELS, v)
export const sourceLabel = (v?: string | null) => labelOf(ALERT_SOURCE_LABELS, v)
export const ticketStatusLabel = (v?: string | null) => labelOf(TICKET_STATUS_LABELS, v)
export const reportTypeLabel = (v?: string | null) => labelOf(REPORT_TYPE_LABELS, v)
export const alertLevelLabel = (v?: string | null) => labelOf(LEVEL_LABELS, v)

/** 完成率小数（0-1 或 0-100 都兼容）→ 百分数文案 */
export function percentText(value?: number | null): string {
  if (value === null || value === undefined) {
    return '—'
  }
  const n = value <= 1 ? value * 100 : value
  return `${Math.round(n)}%`
}

/** 简单的 markdown → HTML（只支持标题/列表/表格/加粗，够报告展示用） */
export function renderMarkdown(md?: string | null): string {
  if (!md) {
    return ''
  }
  const escape = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const lines = escape(md).split(/\r?\n/)
  const out: string[] = []
  let inList = false
  let inTable = false

  const inline = (s: string) =>
    s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/`(.+?)`/g, '<code>$1</code>')

  const closeList = () => {
    if (inList) {
      out.push('</ul>')
      inList = false
    }
  }
  const closeTable = () => {
    if (inTable) {
      out.push('</tbody></table>')
      inTable = false
    }
  }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      closeList()
      closeTable()
      continue
    }
    if (/^\|.*\|$/.test(line)) {
      const cells = line.slice(1, -1).split('|').map((c) => c.trim())
      if (cells.every((c) => /^:?-{2,}:?$/.test(c))) {
        continue
      }
      if (!inTable) {
        out.push('<table><tbody>')
        inTable = true
      }
      out.push(`<tr>${cells.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`)
      continue
    }
    closeTable()
    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    if (heading) {
      closeList()
      const level = Math.min(heading[1].length + 2, 6)
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      continue
    }
    const bullet = /^[-*]\s+(.*)$/.exec(line)
    if (bullet) {
      if (!inList) {
        out.push('<ul>')
        inList = true
      }
      out.push(`<li>${inline(bullet[1])}</li>`)
      continue
    }
    closeList()
    out.push(`<p>${inline(line)}</p>`)
  }
  closeList()
  closeTable()
  return out.join('')
}
