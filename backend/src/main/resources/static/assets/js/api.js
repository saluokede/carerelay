/* API 封装与会话管理 */
const TOKEN_KEY = 'ylz_token';
const USER_KEY = 'ylz_user';

const Session = {
  get token() { return localStorage.getItem(TOKEN_KEY); },
  get user() { try { return JSON.parse(localStorage.getItem(USER_KEY)) || null; } catch { return null; } },
  setToken(token) { localStorage.setItem(TOKEN_KEY, token); },
  setUser(user) { localStorage.setItem(USER_KEY, JSON.stringify(user)); },
  clear() { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY); },
};

async function request(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (Session.token) headers.Authorization = `Bearer ${Session.token}`;
  const resp = await fetch(`/api${path}`, {
    ...options,
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  let json = {};
  try { json = await resp.json(); } catch { /* 非 JSON 响应 */ }
  if (resp.status === 401) { Session.clear(); location.href = 'index.html'; throw new Error('登录已失效'); }
  if (json.code !== 0) throw new Error(json.message || '请求失败');
  return json.data;
}

function qs(params) {
  const s = new URLSearchParams(Object.entries(params || {}).filter(([, v]) => v != null && v !== ''));
  const str = s.toString();
  return str ? `?${str}` : '';
}

const API = {
  login: (username, password) => request('/auth/login', { method: 'POST', body: { username, password } }),
  register: (payload) => request('/auth/register', { method: 'POST', body: payload }),
  updateProfile: (payload) => request('/auth/profile', { method: 'PUT', body: payload }),

  elders: () => request('/elders'),
  elder: (id) => request(`/elders/${id}`),
  elderRecords: (id) => request(`/elders/${id}/records`),
  addRecord: (elder_id, payload) => request(`/elders/${elder_id}/records`, { method: 'POST', body: payload }),
  elderCreate: (payload) => request('/elders', { method: 'POST', body: payload }),
  elderUpdate: (id, payload) => request(`/elders/${id}`, { method: 'PUT', body: payload }),
  elderDelete: (id) => request(`/elders/${id}`, { method: 'DELETE' }),

  plans: () => request('/plans'),
  planDraft: (elder_id) => request('/plans/ai-draft', { method: 'POST', body: { elder_id } }),
  planUpdate: (id, payload) => request(`/plans/${id}`, { method: 'PUT', body: payload }),
  issuePlan: (id) => request(`/plans/${id}/issue`, { method: 'POST' }),

  followups: (filter) => request('/followups' + (filter ? qs(typeof filter === 'string' ? { status: filter } : filter) : '')),
  completeFollowup: (id, note) => request(`/followups/${id}/complete`, { method: 'POST', body: { note } }),
  reviewFollowup: (id, pass, note) => request(`/followups/${id}/review`, { method: 'POST', body: { pass, note } }),

  alerts: (status) => request('/alerts' + qs({ status })),
  closeAlert: (id, conclusion) => request(`/alerts/${id}/close`, { method: 'POST', body: { conclusion } }),

  messages: (elder_id) => request('/messages' + qs({ elder_id })),
  sendMessage: (elder_id, content) => request('/messages', { method: 'POST', body: { elder_id, content } }),

  revisits: () => request('/revisits'),
  addRevisit: (payload) => request('/revisits', { method: 'POST', body: payload }),
  completeRevisit: (id, conclusion) => request(`/revisits/${id}/complete`, { method: 'POST', body: { conclusion } }),

  adminUsers: () => request('/admin/users'),
  toggleUser: (id) => request(`/admin/users/${id}/toggle`, { method: 'POST' }),
  announcements: () => request('/admin/announcements'),
  publishAnnouncement: (payload) => request('/admin/announcements', { method: 'POST', body: payload }),
  aiSettings: () => request('/admin/ai-settings'),
  saveAiSettings: (payload) => request('/admin/ai-settings', { method: 'PUT', body: payload }),
  testAiSettings: (payload) => request('/admin/ai-settings/test', { method: 'POST', body: payload }),

  aiRisk: (elder_id) => request('/ai/risk-score', { method: 'POST', body: { elder_id } }),
  aiSummary: (elder_id) => request('/ai/summary', { method: 'POST', body: { elder_id } }),
  aiTriage: (text, elder_id) => request('/ai/triage', { method: 'POST', body: { text, elder_id } }),
  aiChat: (elder_id, text) => request('/ai/chat', { method: 'POST', body: { elder_id, text } }),
  aiExercise: (elder_id) => request('/ai/exercise', { method: 'POST', body: { elder_id } }),
  agentRun: (elder_id, goal) => request('/ai/agent/run', { method: 'POST', body: { elder_id, goal } }),
  agentApply: (elder_id, actions) => request('/ai/agent/apply', { method: 'POST', body: { elder_id, actions } }),
  stats: () => request('/stats'),
};

/* 带鉴权头下载 CSV 报表 */
async function downloadCsv(url, filename) {
  const headers = {};
  if (Session.token) headers.Authorization = `Bearer ${Session.token}`;
  const resp = await fetch(url, { headers });
  if (resp.status === 401) { Session.clear(); location.href = 'index.html'; throw new Error('登录已失效'); }
  const blob = await resp.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}

/* 简单 HTML 转义，防止数据注入 */
function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* toast 提示 */
let __toastTimer;
function toast(msg) {
  let t = document.getElementById('__toast');
  if (!t) {
    t = document.createElement('div');
    t.id = '__toast';
    t.style.cssText = 'position:fixed;left:50%;bottom:32px;transform:translateX(-50%);background:#1C2B28;color:#fff;padding:10px 18px;border-radius:8px;font-size:13px;z-index:99;max-width:80%';
    document.body.appendChild(t);
  }
  t.textContent = msg;
  clearTimeout(__toastTimer);
  __toastTimer = setTimeout(() => t.remove(), 1800);
}
