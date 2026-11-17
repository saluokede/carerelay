/* 五角色工作台：菜单驱动的多页面路由，数据来自后端 API */

const VIEWS = {};

const RISK_TAG = { 高: 'tag-red', 中: 'tag-yellow', 低: 'tag-green' };
const RISK_COLOR = { 高: '#D64545', 中: '#E8A13A', 低: '#2E9E77' };

function aiChip(label) {
  return `<span class="tag tag-ai">✦ ${esc(label)}</span>`;
}

function riskBar(label, count, total, color) {
  const pct = total ? Math.round(count / total * 100) : 0;
  return `<div class="risk-row">
    <span class="tag" style="background:${color}1A;color:${color}">${label}</span>
    <div class="track"><div class="fill" style="width:${pct}%;background:${color}"></div></div>
    <span class="count">${count} 人</span>
  </div>`;
}

function trendOf(score) {
  if (score >= 75) return { text: '↑ 改善', color: 'var(--green)' };
  if (score >= 55) return { text: '— 平稳', color: 'var(--text-3)' };
  return { text: '↓ 滞后', color: 'var(--red)' };
}

async function safe(promise, fallback = []) {
  try { return await promise; } catch (e) { toast(e.message); return fallback; }
}

/* 页面标题栏 */
function pageTopbar(title, user) {
  return `<div class="topbar">
    <h1>${esc(title)}</h1><span class="date">${new Date().toLocaleDateString('zh-CN')}</span>
    <div class="right"><div class="avatar">${esc(user.name[0])}</div></div>
  </div>`;
}

/* ---------- 公共弹窗：预警处置中心 ---------- */
async function openAlertCenter(refresh) {
  const alerts = await safe(API.alerts('未闭环'), []);
  const body = alerts.length ? alerts.map(a => `
    <div class="plan-block" data-alert="${a.id}">
      <div class="pb-title">${esc(a.elder_name)} · ${esc(a.level)}危预警</div>
      <div class="pb-desc">${esc(a.reason)}</div>
      <textarea data-conclusion placeholder="处置结论（闭环必填）…" style="margin-top:8px"></textarea>
      <div style="margin-top:8px;text-align:right"><button class="btn btn-primary" data-close-alert>闭环预警</button></div>
    </div>`).join('') : '<div class="c-desc" style="padding:16px;text-align:center">暂无未闭环预警</div>';
  const { mask } = openModal({ title: '预警处置中心', bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>关闭</button>' });
  mask.querySelectorAll('[data-alert]').forEach(block => {
    const id = block.dataset.alert;
    block.querySelector('[data-close-alert]').onclick = async () => {
      const conclusion = block.querySelector('[data-conclusion]').value.trim();
      if (!conclusion) { toast('请填写处置结论后再闭环'); return; }
      const r = await safe(API.closeAlert(id, conclusion), null);
      if (r !== null) { toast('预警已闭环'); closeModal(); refresh && refresh(); }
    };
  });
}

/* ---------- 公共弹窗：AI 方案草案 → 签发 ---------- */
async function openPlanDraft(elderId, elderName, refresh) {
  const d = await safe(API.planDraft(elderId), null);
  if (!d) return;
  const body = `
    <div class="plan-block"><div class="pb-title">运动康复</div><div class="pb-desc">${esc(d.motion)}</div></div>
    <div class="plan-block"><div class="pb-title">饮食建议</div><div class="pb-desc">${esc(d.diet)}</div></div>
    <div class="plan-block"><div class="pb-title">用药提醒</div><div class="pb-desc">${esc(d.med)}</div></div>
    <div class="c-desc">✦ AI 生成草案，医生确认后签发并同步家属端</div>`;
  const { mask } = openModal({
    title: `AI 方案草案 · ${elderName}`, bodyHTML: body,
    footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="issueBtn">确认签发</button>',
  });
  mask.querySelector('#issueBtn').onclick = async () => {
    const r = await safe(API.issuePlan(d.id), null);
    if (r !== null) { toast('方案已签发并同步家属端'); closeModal(); refresh && refresh(); }
  };
}

/* ---------- 公共弹窗：AI 评估摘要 ---------- */
async function openSummary(elderId, elderName) {
  const s = await safe(API.aiSummary(elderId), null);
  const body = s && s.summary
    ? `<div class="detail-list"><div class="detail-item"><div class="d-label">AI 摘要</div><div class="d-value">${esc(s.summary).replace(/\n/g, '<br>')}</div></div></div>`
    : '<div class="c-desc" style="padding:16px">暂无摘要记录</div>';
  openModal({ title: `评估报告 · ${elderName}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>关闭</button>' });
}

/* ---------- 公共弹窗：安排复诊 ---------- */
function openRevisitModal(elderId, elderName, refresh) {
  const body = `
    <div class="field"><label>复诊时间</label><input id="rvTime" placeholder="如：下周三 10:00"></div>
    <div class="field"><label>复诊原因 / 目的</label><textarea id="rvReason" placeholder="复查项目与关注重点…"></textarea></div>`;
  const { mask } = openModal({ title: `安排复诊 · ${elderName}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="rvSave">保存安排</button>' });
  mask.querySelector('#rvSave').onclick = async () => {
    const plan_at = mask.querySelector('#rvTime').value.trim();
    const reason = mask.querySelector('#rvReason').value.trim();
    if (!plan_at) { toast('请填写复诊时间'); return; }
    const r = await safe(API.addRevisit({ elder_id: elderId, plan_at, reason }), null);
    if (r !== null) { toast('复诊已安排并同步家属端'); closeModal(); refresh && refresh(); }
  };
}

/* ---------- 公共弹窗：体征上报 ---------- */
function openVitalModal(elderId, refresh) {
  const body = `
    <div class="field"><label>收缩压（高压）mmHg</label><input id="vSys" type="number" placeholder="如 148"></div>
    <div class="field"><label>舒张压（低压）mmHg</label><input id="vDia" type="number" placeholder="如 92"></div>
    <div class="field"><label>心率（次/分，选填）</label><input id="vHr" type="number" placeholder="如 86"></div>`;
  const { mask } = openModal({ title: '体征上报', bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="vSave">提交上报</button>' });
  mask.querySelector('#vSave').onclick = async () => {
    const sys = mask.querySelector('#vSys').value.trim();
    const dia = mask.querySelector('#vDia').value.trim();
    const hr = mask.querySelector('#vHr').value.trim();
    if (!sys || !dia) { toast('请填写血压数值'); return; }
    const value = hr ? `${sys}/${dia}，心率 ${hr}` : `${sys}/${dia}`;
    const r = await safe(API.addRecord(elderId, { category: '体征', title: `血压测量 ${value} mmHg`, detail: '家属/老人上报', value, source: Session.user.name }), null);
    if (r !== null) {
      toast(r.alert ? '体征已上报，指标异常已提醒社区医生' : '体征已上报');
      closeModal(); refresh && refresh();
    }
  };
}

/* ---------- 公共弹窗：个人资料 ---------- */
function openProfileModal(user, onSaved) {
  const body = `
    <div class="field"><label>姓名</label><input id="pfName" value="${esc(user.name || '')}"></div>
    <div class="field"><label>联系电话</label><input id="pfPhone" value="${esc(user.phone || '')}"></div>`;
  const { mask } = openModal({ title: '我的资料', bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="pfSave">保存</button>' });
  mask.querySelector('#pfSave').onclick = async () => {
    const name = mask.querySelector('#pfName').value.trim();
    const phone = mask.querySelector('#pfPhone').value.trim();
    if (!name) { toast('姓名不能为空'); return; }
    const r = await safe(API.updateProfile({ name, phone }), null);
    if (r !== null) { closeModal(); onSaved && onSaved(r); }
  };
}

/* ---------- 公共弹窗：老人档案详情 ---------- */
async function openElderDetail(elderId, opts = {}) {
  const elder = await safe(API.elder(elderId), null);
  if (!elder) return;
  const [records, plans, revisits] = await Promise.all([
    safe(API.elderRecords(elderId), []), safe(API.plans(), []), safe(API.revisits(), []),
  ]);
  const elderPlans = plans.filter(p => p.elder_id === elderId);
  const elderRvs = revisits.filter(r => r.elder_id === elderId);
  const recordRows = records.slice(0, 8).map(r => `
    <div class="tl-item"><div class="tl-time">${esc((r.created_at || '').slice(5, 16))}</div>
      <div class="tl-content"><div class="tl-title">${esc(r.category)} · ${esc(r.title)}</div>
      <div class="tl-desc">${esc(r.detail || '')}${r.source ? ' · ' + esc(r.source) : ''}</div></div></div>`).join('');
  const planRows = elderPlans.map(p => `
    <div class="plan-block"><div class="pb-title">${esc(p.type)} <span class="tag ${p.status === '已签发' ? 'tag-green' : 'tag-yellow'}">${esc(p.status)}</span></div>
      <div class="pb-desc">${esc(p.content).replace(/\n/g, '<br>')}</div></div>`).join('');
  const rvRows = elderRvs.map(r => `
    <div class="detail-item"><div class="d-label">${esc((r.plan_at || '').slice(5, 16))}</div><div class="d-value">${esc(r.reason || '复诊')} · <span class="tag ${r.status === '已完成' ? 'tag-green' : 'tag-blue'}">${esc(r.status)}</span>${r.conclusion ? '<br>' + esc(r.conclusion) : ''}</div></div>`).join('');
  const body = `
    <div class="detail-list">
      <div class="detail-item"><div class="d-label">姓名</div><div class="d-value">${esc(elder.name)} · ${elder.age || '—'} 岁 · ${esc(elder.gender || '—')}</div></div>
      <div class="detail-item"><div class="d-label">诊断</div><div class="d-value">${esc(elder.diag)}</div></div>
      <div class="detail-item"><div class="d-label">风险</div><div class="d-value"><span class="tag ${RISK_TAG[elder.risk]}">${elder.risk}危</span> · 评分 ${elder.risk_score || 0} · 任务完成率 ${elder.rate ?? 0}% · 出院第 ${elder.day ?? 0} 天</div></div>
      <div class="detail-item"><div class="d-label">居住</div><div class="d-value">${esc(elder.living || '—')} · ${esc(elder.address || '—')}</div></div>
      <div class="detail-item"><div class="d-label">联系</div><div class="d-value">${esc(elder.contact || '—')} · 紧急联系人 ${esc(elder.emergency_contact || '—')} ${esc(elder.emergency_phone || '')}</div></div>
    </div>
    <h4 style="margin:16px 0 8px">康复方案</h4>
    <div>${planRows || '<div class="c-desc">暂未签发方案</div>'}</div>
    <h4 style="margin:16px 0 8px">复诊安排</h4>
    <div class="detail-list">${rvRows || '<div class="c-desc">暂无复诊记录</div>'}</div>
    <h4 style="margin:16px 0 8px">近期记录</h4>
    <div class="timeline">${recordRows || '<div class="c-desc">暂无记录</div>'}</div>`;
  const footer = (opts.canEdit ? '<button class="btn btn-primary" id="detailEdit">编辑档案</button>' : '') + '<button class="btn btn-ghost" data-close>关闭</button>';
  const { mask } = openModal({ title: `档案 · ${elder.name}`, bodyHTML: body, footerHTML: footer });
  const editBtn = mask.querySelector('#detailEdit');
  if (editBtn) editBtn.onclick = () => openElderEdit(elder, () => { closeModal(); opts.onSaved && opts.onSaved(); });
}

/* ---------- 公共弹窗：编辑老人档案（医生 / 管理员） ---------- */
function openElderEdit(elder, onSaved) {
  const body = `
    <div class="grid-2" style="gap:12px">
      <div class="field"><label>姓名</label><input id="edName" value="${esc(elder.name)}"></div>
      <div class="field"><label>年龄</label><input id="edAge" type="number" value="${elder.age ?? ''}"></div>
      <div class="field"><label>性别</label><select id="edGender"><option ${elder.gender === '女' ? 'selected' : ''}>女</option><option ${elder.gender === '男' ? 'selected' : ''}>男</option></select></div>
      <div class="field"><label>风险等级</label><select id="edRisk"><option ${elder.risk === '高' ? 'selected' : ''}>高</option><option ${elder.risk === '中' ? 'selected' : ''}>中</option><option ${elder.risk === '低' ? 'selected' : ''}>低</option></select></div>
    </div>
    <div class="field"><label>出院诊断</label><input id="edDiag" value="${esc(elder.diag)}"></div>
    <div class="field"><label>联系电话</label><input id="edContact" value="${esc(elder.contact || '')}"></div>
    <div class="field"><label>现住址</label><input id="edAddress" value="${esc(elder.address || '')}"></div>
    <div class="field"><label>居住情况</label><select id="edLiving"><option ${elder.living === '独居' ? 'selected' : ''}>独居</option><option ${elder.living === '与子女同住' ? 'selected' : ''}>与子女同住</option></select></div>
    <div class="field"><label>紧急联系人</label><input id="edEc" value="${esc(elder.emergency_contact || '')}"></div>
    <div class="field"><label>紧急联系人电话</label><input id="edEp" value="${esc(elder.emergency_phone || '')}"></div>`;
  const { mask } = openModal({ title: `编辑档案 · ${elder.name}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="edSave">保存修改</button>' });
  mask.querySelector('#edSave').onclick = async () => {
    const payload = {
      name: mask.querySelector('#edName').value.trim(),
      age: mask.querySelector('#edAge').value,
      gender: mask.querySelector('#edGender').value,
      diag: mask.querySelector('#edDiag').value.trim(),
      risk: mask.querySelector('#edRisk').value,
      contact: mask.querySelector('#edContact').value.trim(),
      address: mask.querySelector('#edAddress').value.trim(),
      living: mask.querySelector('#edLiving').value,
      emergency_contact: mask.querySelector('#edEc').value.trim(),
      emergency_phone: mask.querySelector('#edEp').value.trim(),
    };
    if (!payload.name || !payload.diag) { toast('姓名和诊断不能为空'); return; }
    const r = await safe(API.elderUpdate(elder.id, payload), null);
    if (r !== null) { toast('档案已更新'); closeModal(); onSaved && onSaved(); }
  };
}

/* ---------- 公共弹窗：编辑 / 重新生成方案 ---------- */
function openPlanEditor(plan, onSaved) {
  const body = `
    <div class="field"><label>方案类型</label><select id="plType">
      ${['运动康复', '用药提醒', '饮食建议'].map(t => `<option ${plan.type === t ? 'selected' : ''}>${t}</option>`).join('')}
    </select></div>
    <div class="field"><label>方案内容</label><textarea id="plContent" style="min-height:160px">${esc(plan.content)}</textarea></div>
    <div class="c-desc">可直接修改 AI 草案；保存后医生可再签发。已签发方案保存后标记为“已调整”。</div>`;
  const { mask } = openModal({ title: `编辑方案 · ${plan.type}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" id="plRegen">重新生成</button><button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="plSave">保存方案</button>' });
  mask.querySelector('#plRegen').onclick = async () => {
    const d = await safe(API.planDraft(plan.elder_id), null);
    if (d) {
      mask.querySelector('#plContent').value = [d.motion, d.diet, d.med].filter(Boolean).join('\n');
      toast('已按当前档案重新生成草案');
    }
  };
  mask.querySelector('#plSave').onclick = async () => {
    const type = mask.querySelector('#plType').value;
    const content = mask.querySelector('#plContent').value.trim();
    if (!content) { toast('方案内容不能为空'); return; }
    const r = await safe(API.planUpdate(plan.id, { type, content }), null);
    if (r !== null) { toast('方案已保存'); closeModal(); onSaved && onSaved(); }
  };
}

/* ---------- 快捷短语 + 语音输入助手（浏览器不支持语音时降级为短语） ---------- */
function attachQuickReply(mask, input, onSend) {
  mask.__quickSend = onSend;
  const phrases = ['我有点头晕', '伤口有点肿', '伤口有点疼', '胸口有点闷', '想联系医生', '今天按时服药了'];
  const qr = mask.querySelector('#quickReply');
  if (qr) phrases.forEach(p => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'qr-chip'; b.textContent = p;
    b.onclick = () => { input.value = p; onSend(p); input.value = ''; };
    qr.appendChild(b);
  });
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const voiceBtn = mask.querySelector('#voiceBtn');
  if (SR && voiceBtn) {
    voiceBtn.style.display = '';
    const rec = new SR(); rec.lang = 'zh-CN'; rec.interimResults = false;
    voiceBtn.onclick = () => { try { rec.start(); } catch { toast('语音识别启动失败，请使用快捷短语'); } };
    rec.onresult = e => { input.value = e.results[0][0].transcript; };
    rec.onerror = () => toast('语音识别不可用，请使用快捷短语');
  }
}

/* ---------- 公共弹窗：AI 在线问诊 ---------- */
function openAiChat(elderId, elderName) {
  const body = `
    <div class="chat">
      <div class="chat-msgs" id="aiMsgs"></div>
      <div class="quick-reply" id="quickReply"></div>
      <div class="chat-input">
        <button class="voice-btn" id="voiceBtn" title="语音输入" style="display:none">语音</button>
        <input id="aiText" placeholder="描述您的不适或疑问，回车发送…" autocomplete="off">
        <button class="btn btn-primary" id="aiSend">发送</button>
      </div>
      <div class="chat-side"><button class="link-btn" id="toHuman">留言给医护</button></div>
    </div>`;
  const { mask } = openModal({ title: `AI 问诊 · ${elderName || ''}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>关闭</button>' });
  const box = mask.querySelector('#aiMsgs');
  const input = mask.querySelector('#aiText');
  const push = (side, who, text, level) => {
    const tag = level ? ` <span class="tag ${RISK_TAG[level] || 'tag-blue'}">${level}危</span>` : '';
    box.insertAdjacentHTML('beforeend', `<div class="chat-row ${side}"><div class="chat-bubble"><div class="chat-who">${esc(who)}${tag}</div><div class="chat-text">${esc(text)}</div></div></div>`);
    box.scrollTop = box.scrollHeight;
  };
  push('other', 'AI 健康助手', '您好，我可以先帮您判断症状轻重。请描述哪里不舒服、持续多久；我不能替代医生面诊，高危情况会提醒您尽快联系医护。');
  attachQuickReply(mask, input, async (text) => {
    push('mine', Session.user.name, text);
    const r = await safe(API.aiChat(elderId, text), null);
    if (r) push('other', 'AI 健康助手', r.reply, r.level);
  });
  const send = async () => {
    const text = input.value.trim(); if (!text) return;
    input.value = '';
    mask.__quickSend && mask.__quickSend(text);
  };
  mask.querySelector('#aiSend').onclick = send;
  input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
  mask.querySelector('#toHuman').onclick = () => openChat(elderId, elderName);
}

/* ---------- 公共弹窗：AI 康复动作指导 ---------- */
function openExerciseGuide(elderId, elderName) {
  const body = `<div id="exWrap"><div class="c-desc">正在生成康复动作指导…</div></div>`;
  const { mask } = openModal({ title: `AI 康复动作指导 · ${elderName || ''}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" id="exRegen">重新生成</button><button class="btn btn-ghost" data-close>关闭</button>' });
  async function load() {
    const wrap = mask.querySelector('#exWrap');
    const g = await safe(API.aiExercise(elderId), null);
    if (!g) { wrap.innerHTML = '<div class="c-desc">生成失败，请重试</div>'; return; }
    wrap.innerHTML = `
      <div class="plan-block"><div class="pb-title">阶段说明</div><div class="pb-desc">${esc(g.summary)}</div></div>
      ${g.steps.map((s, i) => `<div class="plan-block"><div class="pb-title">${i + 1}. ${esc(s.action)}</div><div class="pb-desc">${esc(s.detail)}</div><div class="p-meta" style="margin-top:4px">频次 / 时长：${esc(s.count)}</div></div>`).join('')}
      <div class="plan-block"><div class="pb-title">注意事项与禁忌</div><div class="pb-desc">${esc(g.cautions)}</div></div>
      <div class="plan-block"><div class="pb-title">饮食建议</div><div class="pb-desc">${esc(g.diet)}</div></div>`;
  }
  load();
  mask.querySelector('#exRegen').onclick = load;
}

/* ==================== 家庭医生 ==================== */
VIEWS.family_doctor = {
  title: '家庭医生工作台',
  nav: [
    { key: 'overview', label: '工作总览' },
    { key: 'elders', label: '在管老人' },
    { key: 'plans', label: '方案复核' },
    { key: 'messages', label: '在线问诊' },
    { key: 'followups', label: '随访报表' },
  ],
  pages: {
    async overview(main, user, go) {
      const [elders, alertsOpen, stats, plans, reviews, messages] = await Promise.all([
        safe(API.elders(), []), safe(API.alerts('未闭环'), []), safe(API.stats(), {}),
        safe(API.plans(), []), safe(API.followups({ review: '待审核' }), []), safe(API.messages(), []),
      ]);
      const highCount = elders.filter(e => e.risk === '高').length;
      const pendingPlans = plans.filter(p => p.status === '待签发').length;
      const pendingChat = messages.filter(m => ['family', 'elder'].includes(m.sender_role)).length;
      const reload = () => VIEWS.family_doctor.pages.overview(main, user, go);

      main.innerHTML = `
      ${pageTopbar('今日随访工作台', user)}
      <div class="alert-bar">
        <div>
          <div class="a-title">⚠ ${alertsOpen.length} 条高危待处置</div>
          <ul>${alertsOpen.map(a => `<li>${esc(a.elder_name)} ${esc(a.reason)}</li>`).join('') || '<li>暂无未闭环预警</li>'}</ul>
        </div>
        <button class="btn btn-danger a-action" data-act="alert-all">立即处理</button>
      </div>
      <div class="metrics">
        <div class="metric accent-red" style="cursor:pointer" data-go="elders"><div class="m-label">待复核风险</div><div class="m-num">${highCount}</div><div class="m-sub">AI 初筛，需医生终判</div></div>
        <div class="metric accent-yellow" style="cursor:pointer" data-go="plans"><div class="m-label">待签发方案</div><div class="m-num">${pendingPlans}</div><div class="m-sub">AI 草案待确认下发</div></div>
        <div class="metric" style="cursor:pointer" data-go="messages"><div class="m-label">待处理问诊</div><div class="m-num">${pendingChat}</div><div class="m-sub">患者/家属在线提问</div></div>
        <div class="metric" style="cursor:pointer" data-go="followups"><div class="m-label">待审核上报</div><div class="m-num">${reviews.length}</div><div class="m-sub">护士随访记录待确认</div></div>
      </div>
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>风险分布</h3><span class="hint">AI 评分 · 医生终判</span></div>
          ${riskBar('高风险', elders.filter(e => e.risk === '高').length, elders.length, '#D64545')}
          ${riskBar('中风险', elders.filter(e => e.risk === '中').length, elders.length, '#E8A13A')}
          ${riskBar('低风险', elders.filter(e => e.risk === '低').length, elders.length, '#2E9E77')}
        </div>
        <div class="card ai-card">
          <div class="card-head"><span class="bar"></span><h3>本端 AI 能力（点击进入）</h3><span class="hint">AI 仅辅助，临床决策由医生负责</span></div>
          <div class="ai-list">
            <div class="check-item" style="cursor:pointer" data-go="elders"><div class="idx">✦</div><div><div class="c-title">风险初筛分级 ${aiChip('AI')}</div><div class="c-desc">综合体征、打卡、用药与病史给出风险评分与分级建议，医生终判</div></div></div>
            <div class="check-item" style="cursor:pointer" data-go="plans"><div class="idx">✦</div><div><div class="c-title">个性化康复方案草案 ${aiChip('AI')}</div><div class="c-desc">按病种、阶段与体征动态生成运动、饮食、用药与禁忌草案，可编辑后签发</div></div></div>
            <div class="check-item" style="cursor:pointer" data-go="messages"><div class="idx">✦</div><div><div class="c-title">AI 在线问诊辅助 ${aiChip('AI')}</div><div class="c-desc">先由 AI 做症状风险研判，再转人工会话，辅助医生判断</div></div></div>
            <div class="check-item" style="cursor:pointer" data-go="elders"><div class="idx">✦</div><div><div class="c-title">病情摘要与出院小结 ${aiChip('AI')}</div><div class="c-desc">汇总随访、体征、用药时间线，生成结构化摘要供复诊参考</div></div></div>
          </div>
        </div>
      </div>`;

      main.querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          if (btn.dataset.act === 'alert-all') await openAlertCenter(reload);
        };
      });
    },

    async elders(main, user) {
      const elders = await safe(API.elders(), []);
      const reload = () => VIEWS.family_doctor.pages.elders(main, user);
      const rows = elders.map(e => `
        <tr>
          <td><div class="person"><div class="p-avatar">${esc(e.name[0])}</div><div><div class="p-name" style="cursor:pointer;color:var(--primary)" data-act="detail" data-id="${e.id}">${esc(e.name)}</div><div class="p-meta">${e.age}岁 · ${esc(e.living || '')} · ${esc(e.contact || '')}</div></div></div></td>
          <td>${esc(e.diag)}</td>
          <td><span class="tag ${RISK_TAG[e.risk]}">${e.risk}危</span></td>
          <td>${e.rate}%</td>
          <td>${e.alerts ? `<span style="color:var(--red);font-weight:600">${e.alerts} 条</span>` : '<span style="color:var(--text-3)">0</span>'}</td>
          <td>第 ${e.day} 天</td>
          <td><div class="row-actions" style="display:flex;gap:8px;flex-wrap:wrap">
            <button class="link-btn" data-act="detail" data-id="${e.id}" data-name="${esc(e.name)}">档案</button>
            <button class="link-btn" data-act="recheck" data-id="${e.id}" data-name="${esc(e.name)}">复核</button>
            <button class="link-btn" data-act="plan" data-id="${e.id}" data-name="${esc(e.name)}">调方案</button>
            <button class="link-btn" data-act="chat" data-id="${e.id}" data-name="${esc(e.name)}">AI 问诊</button>
          </div></td>
        </tr>`).join('');
      main.innerHTML = `
      ${pageTopbar('在管老人', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>在管老人</h3><span class="hint">点击姓名查看档案 · 医生可编辑信息与风险终判</span></div>
        <table>
          <colgroup><col style="width:24%"><col style="width:18%"><col style="width:11%"><col style="width:13%"><col style="width:14%"><col style="width:10%"><col style="width:22%"></colgroup>
          <thead><tr><th>老人</th><th>病种</th><th>风险</th><th>完成率</th><th>未闭环预警</th><th>住院</th><th>操作</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          const act = btn.dataset.act, id = btn.dataset.id, name = btn.dataset.name;
          if (act === 'detail') await openElderDetail(id, { canEdit: true, onSaved: reload });
          else if (act === 'recheck') {
            const r = await safe(API.aiRisk(id), null);
            if (r) openModal({ title: `AI 风险复核 · ${name}`, bodyHTML: `
              <div class="detail-list">
                <div class="detail-item"><div class="d-label">建议分级</div><div class="d-value"><span class="tag ${RISK_TAG[r.level]}">${r.level}危</span></div></div>
                <div class="detail-item"><div class="d-label">参考评分</div><div class="d-value">${r.score} 分</div></div>
                <div class="detail-item"><div class="d-label">依据</div><div class="d-value">${(r.reasons || []).map(esc).join('；')}</div></div>
              </div>`, footerHTML: '<button class="btn btn-ghost" data-close>关闭</button>' });
          } else if (act === 'plan') await openPlanDraft(id, name, reload);
          else if (act === 'chat') openAiChat(id, name);
        };
      });
    },

    async plans(main, user) {
      const [plans, elders] = await Promise.all([safe(API.plans(), []), safe(API.elders(), [])]);
      const elderMap = Object.fromEntries(elders.map(e => [e.id, e]));
      const reload = () => VIEWS.family_doctor.pages.plans(main, user);
      const rows = plans.map(p => {
        const e = elderMap[p.elder_id] || {};
        return `<div class="plan-block">
          <div class="pb-title">${esc(e.name || '#' + p.elder_id)} · ${esc(p.type)}方案 <span class="tag ${p.status === '已签发' ? 'tag-green' : p.status === '已调整' ? 'tag-blue' : 'tag-yellow'}">${esc(p.status)}</span></div>
          <div class="pb-desc">${esc(p.content).replace(/\n/g, '<br>')}</div>
          <div style="margin-top:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <button class="btn btn-ghost" data-act="edit" data-id="${p.id}">编辑 / 调整</button>
            ${p.status === '待签发' ? `<button class="btn btn-primary" data-act="issue" data-id="${p.id}">签发</button>` : ''}
            ${p.issued_by ? `<span class="p-meta">签发人：${esc(p.issued_by)}</span>` : ''}
          </div>
        </div>`;
      }).join('');
      main.innerHTML = `
      ${pageTopbar('方案复核', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>康复 / 用药 / 饮食方案</h3><span class="hint">可编辑 AI 草案或重新生成；签发后下发家属端</span></div>
        <div class="check-list">${rows || '<div class="c-desc">暂无方案</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          if (btn.dataset.act === 'edit') {
            const plan = plans.find(x => String(x.id) === btn.dataset.id);
            if (plan) openPlanEditor(plan, reload);
          } else if (btn.dataset.act === 'issue') {
            const r = await safe(API.issuePlan(btn.dataset.id), null);
            if (r !== null) { toast('方案已签发并同步家属端'); reload(); }
          }
        };
      });
    },

    async messages(main, user) {
      const messages = await safe(API.messages(), []);
      const groups = {};
      messages.forEach(m => { (groups[m.elder_id] = groups[m.elder_id] || []).push(m); });
      const reload = () => VIEWS.family_doctor.pages.messages(main, user);
      const cards = Object.entries(groups).map(([eid, list]) => {
        const last = list[list.length - 1];
        return `<div class="check-item">
          <div class="idx" style="background:#EAF1F8;color:var(--blue)">问</div>
          <div style="flex:1"><div class="c-title">${esc(last.elder_name || '家属')} · ${roleLabel(last.sender_role)}咨询</div><div class="c-desc">${esc(last.content)}</div></div>
          <div style="display:flex;gap:8px"><button class="link-btn" data-act="ai" data-id="${eid}" data-name="${esc(last.elder_name || '')}">AI 辅助</button><button class="link-btn" data-act="chat" data-id="${eid}" data-name="${esc(last.elder_name || '')}">去沟通（${list.length} 条）</button></div>
        </div>`;
      }).join('');
      main.innerHTML = `
      ${pageTopbar('在线问诊', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>家属 / 患者会话</span></div>
        <div class="check-list">${cards || '<div class="c-desc">暂无咨询会话</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = () => {
          if (btn.dataset.act === 'ai') openAiChat(btn.dataset.id, btn.dataset.name);
          else openChat(btn.dataset.id, btn.dataset.name).then(reload);
        };
      });
    },

    async followups(main, user) {
      const tabs = [['待审核', '待审核'], ['已通过', '已通过'], ['已驳回', '已驳回']];
      let active = '待审核';
      const reload = () => VIEWS.family_doctor.pages.followups(main, user);

      const render = async () => {
        const reviews = await safe(API.followups({ review: active }), []);
        const rows = reviews.map(f => `
          <div class="plan-block" data-fu="${f.id}">
            <div class="pb-title">${esc(f.elder_name)} · ${esc(f.type)} <span class="tag ${f.review_status === '已通过' ? 'tag-green' : f.review_status === '已驳回' ? 'tag-red' : 'tag-yellow'}">${esc(f.review_status)}</span></div>
            <div class="pb-desc">${esc(f.note || '无备注')} · 回填人：${esc(f.reporter || '护士')}</div>
            ${f.review_note ? `<div class="p-meta" style="margin-top:4px">审核意见：${esc(f.review_note)}</div>` : ''}
            <div style="margin-top:8px;display:flex;gap:8px;flex-wrap:wrap">
              <button class="link-btn" data-act="detail" data-id="${f.elder_id}" data-name="${esc(f.elder_name)}">查看档案记录</button>
              ${active === '待审核' ? `
              <button class="btn btn-primary" data-act="approve" data-id="${f.id}">通过</button>
              <button class="btn btn-ghost" data-act="reject" data-id="${f.id}">驳回</button>` : ''}
            </div>
          </div>`).join('');
        main.innerHTML = `
        ${pageTopbar('随访报表', user)}
        <div class="subtabs">${tabs.map(([k, label]) => `<button class="${active === k ? 'active' : ''}" data-tab="${k}">${label}</button>`).join('')}</div>
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>${active}随访记录</h3><span class="hint">通过后归档并同步家属端查看</span></div>
          <div class="check-list">${rows || '<div class="c-desc">暂无该状态记录</div>'}</div>
        </div>`;
        main.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => { active = b.dataset.tab; render(); });
        main.querySelectorAll('[data-act]').forEach(btn => {
          btn.onclick = async () => {
            if (btn.dataset.act === 'detail') await openElderDetail(btn.dataset.id, {});
            else {
              const pass = btn.dataset.act === 'approve';
              const r = await safe(API.reviewFollowup(btn.dataset.id, pass, pass ? '记录完整，同意归档' : '信息不足，退回补充随访'), null);
              if (r !== null) { toast(pass ? '已通过该随访记录' : '已驳回该随访记录'); render(); }
            }
          };
        });
      };
      render();
    },
  },
};

/* ==================== AI 接力台（医生 / 照护员共用） ==================== */
function agentStepHtml(s, i) {
  const hits = Array.isArray(s.hits) && s.hits.length ? `
    <div class="kb-hits">${s.hits.map(h => `
      <div class="kb-hit">
        <div class="kb-head"><span class="kb-doc">${esc(h.citation || ((h.doc || '') + ' ' + (h.section || '')))}</span><span class="kb-score">相关度 ${h.score}</span></div>
        <div class="kb-title">${esc(h.title)}</div>
        <div class="kb-snippet">${esc(h.snippet)}</div>
      </div>`).join('')}</div>` : '';
  return `<div class="agent-step">
    <div class="st-idx">${i + 1}</div>
    <div class="st-body">
      <div class="st-head"><span class="st-tool">${esc(s.label || s.tool)}</span>
        <span class="st-fn">${esc(s.tool)}</span><span class="st-ms">${s.ms} ms</span></div>
      <div class="st-sum">${esc(s.summary || '')}</div>
      ${hits}
    </div>
  </div>`;
}

function agentEndHtml(data, canApply) {
  const findings = (data.findings || []).map(f => `
    <div class="finding"><div class="f-title">${esc(f.title)}</div><div class="f-detail">${esc(f.detail)}</div></div>`).join('');
  const actions = (data.actions || []).map((a, i) => `
    <label class="action-row"><input type="checkbox" data-act-idx="${i}" checked />
      <div class="a-body"><div class="a-title">${esc(a.title)} <span class="a-type">${esc(a.type)}</span></div>
        <div class="a-detail">${esc(a.detail || '')}</div></div></label>`).join('');
  return `
  <div class="card agent-result">
    <div class="card-head"><span class="bar"></span><h3>AI 结论</h3>
      <span class="hint">${data.mode === 'LLM' ? '模型自主规划 · 结论引用本地知识库' : '本地规则模式 · 结论引用本地知识库'}</span></div>
    <div class="agent-summary">${esc(data.summary)}</div>
    ${findings ? `<div class="findings">${findings}</div>` : ''}
    ${(data.citations || []).length ? `<div class="cites">${data.citations.map(c => `<span class="cite-chip">${esc(c.citation)}</span>`).join('')}</div>` : ''}
  </div>
  ${actions ? `<div class="card agent-actions">
      <div class="card-head"><span class="bar"></span><h3>待确认动作</h3><span class="hint">勾选后写入系统；不确认不会落库</span></div>
      <div class="action-list">${actions}</div>
      <div class="agent-apply">
        <button class="btn btn-primary" id="agApply" ${canApply ? '' : 'disabled'}>确认执行所选动作</button>
        <span class="hint" id="agApplyHint">${canApply ? '写入后可到随访 / 方案页查看' : '当前角色只能查看，写入需医生确认'}</span>
      </div>
    </div>` : ''}`;
}

async function renderAgentPage(main, user) {
  const elders = await safe(API.elders(), []);
  if (!elders.length) {
    main.innerHTML = `${pageTopbar('AI 接力台', user)}<div class="card">暂无在管老人。</div>`;
    return;
  }
  const canApply = ['hospital', 'family_doctor'].includes(user.role);
  main.innerHTML = `
  ${pageTopbar('AI 接力台', user)}
  <div class="card agent-hero">
    <div class="card-head"><span class="bar"></span><h3>AI 接力台 · 多步决策</h3>
      <span class="hint">由模型自己决定调用哪些工具；医学结论必须引用本地知识库；落库需人工确认</span></div>
    <div class="agent-controls">
      <div class="field"><label>选择老人</label>
        <select id="agElder">${elders.map(e => `<option value="${e.id}">${esc(e.name)} · ${esc(e.diag)} · 出院第 ${e.day} 天</option>`).join('')}</select>
      </div>
      <div class="field"><label>本次重点（可留空，交给 AI 判断）</label>
        <input id="agGoal" placeholder="例如：伤口周围有点肿，是否需要上门复核" /></div>
      <div class="agent-run">
        <button class="btn btn-primary" id="agRun">开始 AI 接力评估</button>
        <span class="tag" id="agMode">未运行</span>
      </div>
    </div>
  </div>
  <div id="agFlow"></div>`;

  main.querySelector('#agRun').onclick = async () => {
    const btn = main.querySelector('#agRun');
    const flow = main.querySelector('#agFlow');
    const elderId = Number(main.querySelector('#agElder').value);
    const goal = main.querySelector('#agGoal').value.trim();
    btn.disabled = true;
    btn.textContent = 'AI 正在接力…';
    main.querySelector('#agMode').textContent = '规划中';
    flow.innerHTML = `<div class="card agent-loading">AI 正在读取档案、规划步骤…</div>`;
    let data;
    try {
      data = await API.agentRun(elderId, goal);
    } catch (e) {
      flow.innerHTML = `<div class="card">运行失败：${esc(e.message)}</div>`;
      btn.disabled = false;
      btn.textContent = '开始 AI 接力评估';
      return;
    }
    const modeTag = main.querySelector('#agMode');
    modeTag.textContent = data.mode === 'LLM' ? '模型模式' : '规则模式';
    modeTag.className = 'tag ' + (data.mode === 'LLM' ? 'tag-ai' : 'tag-blue');
    flow.innerHTML = `
      <div class="card agent-panel">
        <div class="card-head"><span class="bar"></span><h3>执行轨迹</h3>
          <span class="hint">${data.mode === 'LLM' ? esc(data.model || '') + ' · 模型自主规划' : '未配置模型 Key · 本地规则编排'}
            · ${data.rounds} 步 · ${data.latencyMs} ms</span></div>
        <div class="agent-timeline" id="agTimeline"></div>
      </div>
      <div id="agEnd"></div>`;
    const tl = main.querySelector('#agTimeline');
    const steps = data.steps || [];
    steps.forEach((s, i) => {
      setTimeout(() => {
        tl.insertAdjacentHTML('beforeend', agentStepHtml(s, i));
        if (i === steps.length - 1) {
          setTimeout(() => {
            const end = main.querySelector('#agEnd');
            if (!end) return;
            end.innerHTML = agentEndHtml(data, canApply);
            const applyBtn = main.querySelector('#agApply');
            if (applyBtn) applyBtn.onclick = () => applyAgentActions(main, data, elderId);
          }, 320);
        }
      }, i * 420);
    });
    btn.disabled = false;
    btn.textContent = '重新运行';
  };
}

async function applyAgentActions(main, data, elderId) {
  const btn = main.querySelector('#agApply');
  const picked = [...main.querySelectorAll('input[data-act-idx]:checked')]
    .map(el => (data.actions || [])[Number(el.dataset.actIdx)])
    .filter(Boolean);
  if (!picked.length) { toast('请至少勾选一条动作'); return; }
  btn.disabled = true;
  btn.textContent = '写入中…';
  try {
    const r = await API.agentApply(elderId, picked);
    toast(`已写入 ${r.count} 条动作`);
    btn.textContent = '已执行';
    main.querySelector('#agApplyHint').textContent = '可在方案 / 随访 / 档案页查看';
  } catch (e) {
    toast('写入失败：' + e.message);
    btn.disabled = false;
    btn.textContent = '确认执行所选动作';
  }
}

/* ==================== 医院主治医生 ==================== */
VIEWS.hospital = {
  title: '医院主治医生',
  nav: [
    { key: 'overview', label: '出院追踪' },
    { key: 'elders', label: '出院患者' },
    { key: 'agent', label: 'AI 接力台' },
    { key: 'assessment', label: 'AI 康复评估' },
    { key: 'messages', label: '远程答疑' },
    { key: 'revisits', label: '复诊安排' },
  ],
  pages: {
    async overview(main, user, go) {
      const [elders, stats, revisits, messages] = await Promise.all([
        safe(API.elders(), []), safe(API.stats(), {}), safe(API.revisits(), []), safe(API.messages(), []),
      ]);
      const familyMsgs = messages.filter(m => ['family', 'elder'].includes(m.sender_role));
      main.innerHTML = `
      ${pageTopbar('出院患者追踪', user)}
      <div class="alert-bar warn">
        <div>
          <div class="a-title">⚠ ${stats.alertsOpen || 0} 例体征异常待您复核</div>
          <ul>${familyMsgs.slice(0, 2).map(m => `<li>${esc(m.elder_name)}：${esc(m.content)}</li>`).join('') || '<li>暂无待复核事项</li>'}</ul>
        </div>
        <button class="btn btn-ghost a-action" data-act="view-all">查看</button>
      </div>
      <div class="metrics">
        <div class="metric accent-yellow" style="cursor:pointer" data-go="assessment"><div class="m-label">AI 康复报告待阅</div><div class="m-num">${elders.length}</div><div class="m-sub">模型生成，医生确认</div></div>
        <div class="metric" style="cursor:pointer" data-go="revisits"><div class="m-label">待复诊评估</div><div class="m-num">${revisits.filter(r => r.status === '待复诊').length}</div><div class="m-sub">已安排复诊节点</div></div>
        <div class="metric" style="cursor:pointer" data-go="messages"><div class="m-label">远程咨询</div><div class="m-num">${familyMsgs.length}</div><div class="m-sub">家属在线提问</div></div>
        <div class="metric"><div class="m-label">异常回传</div><div class="m-num">${stats.alertsOpen || 0}</div><div class="m-sub">体征越界自动归集</div></div>
      </div>
      <div class="card ai-card">
        <div class="card-head"><span class="bar"></span><h3>本端 AI 能力（点击进入）</h3><span class="hint">AI 仅辅助，临床决策由医生负责</span></div>
        <div class="ai-list">
          <div class="check-item" style="cursor:pointer" data-go="assessment"><div class="idx">✦</div><div><div class="c-title">康复效果评估 ${aiChip('AI')}</div><div class="c-desc">对比出院、居家阶段体征与随访记录，生成评估摘要，可重新生成或编辑后归档</div></div></div>
          <div class="check-item" style="cursor:pointer" data-go="elders"><div class="idx">✦</div><div><div class="c-title">异常回传智能归集 ${aiChip('AI')}</div><div class="c-desc">自动识别越界体征与滞后进度，按优先级推送给对应主治医生</div></div></div>
          <div class="check-item" style="cursor:pointer" data-go="assessment"><div class="idx">✦</div><div><div class="c-title">随访摘要生成 ${aiChip('AI')}</div><div class="c-desc">把护士回填的随访记录压缩成可读摘要，辅助医生快速掌握病情变化</div></div></div>
        </div>
      </div>`;
      main.querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
      main.querySelectorAll('[data-act=view-all]').forEach(b => b.onclick = () => openAlertCenter(() => VIEWS.hospital.pages.overview(main, user, go)));
    },

    async elders(main, user) {
      const elders = await safe(API.elders(), []);
      const pts = elders.map(e => ({ ...e, trend: trendOf(e.risk_score || 60) }));
      const reload = () => VIEWS.hospital.pages.elders(main, user);
      const rows = pts.map(p => `
        <tr>
          <td><div class="person"><div class="p-avatar">${esc(p.name[0])}</div><div class="p-name" style="cursor:pointer;color:var(--primary)" data-act="detail" data-id="${p.id}">${esc(p.name)}</div></div></td>
          <td>${esc(p.diag)}</td>
          <td>第 ${p.day} 天</td>
          <td><span class="tag tag-ai">✦ AI ${p.risk_score} 分</span></td>
          <td style="color:${p.trend.color};font-weight:600">${p.trend.text}</td>
          <td><div style="display:flex;gap:8px;flex-wrap:wrap">
            <button class="link-btn" data-act="detail" data-id="${p.id}" data-name="${esc(p.name)}">档案</button>
            <button class="link-btn" data-act="report" data-id="${p.id}" data-name="${esc(p.name)}">评估报告</button>
            <button class="link-btn" data-act="adjust" data-id="${p.id}" data-name="${esc(p.name)}">调方</button>
          </div></td>
        </tr>`).join('');
      main.innerHTML = `
      ${pageTopbar('出院患者', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>出院患者追踪</h3><span class="hint">点击姓名查看/编辑档案 · 评分由 AI 依据居家数据测算</span></div>
        <table>
          <colgroup><col style="width:22%"><col style="width:22%"><col style="width:12%"><col style="width:18%"><col style="width:12%"><col style="width:24%"></colgroup>
          <thead><tr><th>患者</th><th>出院诊断</th><th>居家</th><th>AI 康复评分</th><th>趋势</th><th>操作</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          const act = btn.dataset.act, id = btn.dataset.id, name = btn.dataset.name;
          if (act === 'detail') await openElderDetail(id, { canEdit: true, onSaved: reload });
          else if (act === 'report') await openSummary(id, name);
          else if (act === 'adjust') await openPlanDraft(id, name, reload);
        };
      });
    },

    async agent(main, user) {
      await renderAgentPage(main, user);
    },

    async assessment(main, user) {
      const elders = await safe(API.elders(), []);
      const reload = () => VIEWS.hospital.pages.assessment(main, user);
      let targetId = elders[0]?.id;
      let summaryText = '';

      const loadSummary = async (id) => {
        const wrap = main.querySelector('#asSummary');
        if (!wrap) return;
        wrap.value = 'AI 正在生成评估摘要…';
        const s = await safe(API.aiSummary(id), null);
        summaryText = s?.summary || '暂无可汇总的随访记录，请先补充体征与随访数据。';
        wrap.value = summaryText;
      };

      main.innerHTML = `
      ${pageTopbar('AI 康复评估', user)}
      <div class="card ai-card">
        <div class="card-head"><span class="bar"></span><h3>AI 康复效果评估</h3><span class="hint">✦ AI 生成，可编辑后归档</span></div>
        <div class="field"><label>选择患者</label>
          <select id="asElder">${elders.map(e => `<option value="${e.id}">${esc(e.name)} · ${esc(e.diag)}</option>`).join('')}</select>
        </div>
        <div class="field"><label>评估摘要（可修改）</label><textarea id="asSummary" style="min-height:150px" placeholder="AI 摘要将显示在这里…"></textarea></div>
        <div class="ai-confirm">✓ 结论由 AI 辅助生成，最终判断与调方以医生确认为准</div>
        <div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap">
          <button class="btn btn-ghost" data-act="regen">重新生成</button>
          <button class="btn btn-primary" data-act="confirm">确认结论并归档</button>
          <button class="btn btn-ghost" data-act="revisit">安排复诊</button>
        </div>
      </div>`;

      main.querySelector('#asElder').onchange = e => { targetId = Number(e.target.value); loadSummary(targetId); };
      main.querySelector('[data-act=regen]').onclick = () => loadSummary(targetId);
      main.querySelector('[data-act=confirm]').onclick = async () => {
        const elder = elders.find(e => e.id === targetId);
        const detail = main.querySelector('#asSummary').value.trim();
        if (!detail) { toast('请先生成或填写评估结论'); return; }
        const r = await safe(API.addRecord(targetId, { category: '护理', title: 'AI 康复评估结论已确认', detail, value: '', source: user.name }), null);
        if (r !== null) toast('评估结论已确认并归档');
      };
      main.querySelector('[data-act=revisit]').onclick = () => {
        const elder = elders.find(e => e.id === targetId);
        if (elder) openRevisitModal(elder.id, elder.name, reload);
      };
      if (targetId) loadSummary(targetId);
    },

    async messages(main, user) {
      const messages = await safe(API.messages(), []);
      const groups = {};
      messages.forEach(m => { (groups[m.elder_id] = groups[m.elder_id] || []).push(m); });
      const reload = () => VIEWS.hospital.pages.messages(main, user);
      const cards = Object.entries(groups).map(([eid, list]) => {
        const last = list[list.length - 1];
        return `<div class="check-item">
          <div class="idx" style="background:#EAF1F8;color:var(--blue)">问</div>
          <div style="flex:1"><div class="c-title">${esc(last.elder_name || '家属')} · ${roleLabel(last.sender_role)}咨询</div><div class="c-desc">${esc(last.content)}</div></div>
          <div style="display:flex;gap:8px"><button class="link-btn" data-act="ai" data-id="${eid}" data-name="${esc(last.elder_name || '')}">AI 辅助</button><button class="link-btn" data-act="reply" data-id="${eid}" data-name="${esc(last.elder_name || '')}">去回复（${list.length} 条）</button></div>
        </div>`;
      }).join('');
      main.innerHTML = `
      ${pageTopbar('远程答疑', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>待回复远程咨询</h3></div>
        <div class="check-list">${cards || '<div class="c-desc">暂无家属咨询</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn =>
        btn.onclick = () => {
          if (btn.dataset.act === 'ai') openAiChat(btn.dataset.id, btn.dataset.name);
          else openChat(btn.dataset.id, btn.dataset.name).then(reload);
        });
    },

    async revisits(main, user) {
      const revisits = await safe(API.revisits(), []);
      const reload = () => VIEWS.hospital.pages.revisits(main, user);
      const rows = revisits.map(r => `
        <div class="plan-block">
          <div class="pb-title">${esc(r.elder_name)} · ${esc(r.plan_at)} <span class="tag ${r.status === '待复诊' ? 'tag-yellow' : 'tag-green'}">${esc(r.status)}</span></div>
          <div class="pb-desc">${esc(r.reason || '—')}${r.doctor ? ` · 安排医生：${esc(r.doctor)}` : ''}</div>
          ${r.conclusion ? `<div class="p-meta" style="margin-top:4px">结论：${esc(r.conclusion)}</div>` : ''}
          ${r.status === '待复诊' ? `<div style="margin-top:8px"><button class="btn btn-ghost" data-act="done" data-id="${r.id}" data-name="${esc(r.elder_name)}">登记完成</button></div>` : ''}
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('复诊安排', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>复诊节点</h3><span class="hint">安排后同步家属端；完成后生成后续随访工单</span></div>
        <div style="margin-bottom:14px"><button class="btn btn-primary" data-act="add">安排复诊</button></div>
        <div class="check-list">${rows || '<div class="c-desc">暂无复诊安排</div>'}</div>
      </div>`;
      main.querySelector('[data-act=add]').onclick = async () => {
        const elders = await safe(API.elders(), []);
        const body = `
          <div class="field"><label>选择患者</label>
            <select id="rvElder">${elders.map(e => `<option value="${e.id}">${esc(e.name)} · ${esc(e.diag)}</option>`).join('')}</select>
          </div>
          <div class="field"><label>复诊时间</label><input id="rvTime" placeholder="如：下周三 10:00"></div>
          <div class="field"><label>复诊原因 / 目的</label><textarea id="rvReason" placeholder="复查项目与关注重点…"></textarea></div>`;
        const { mask } = openModal({ title: '安排复诊', bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="rvSave">保存安排</button>' });
        mask.querySelector('#rvSave').onclick = async () => {
          const elder_id = mask.querySelector('#rvElder').value;
          const plan_at = mask.querySelector('#rvTime').value.trim();
          const reason = mask.querySelector('#rvReason').value.trim();
          if (!plan_at) { toast('请填写复诊时间'); return; }
          const r = await safe(API.addRevisit({ elder_id, plan_at, reason }), null);
          if (r !== null) { toast('复诊已安排并同步家属端'); closeModal(); reload(); }
        };
      };
      main.querySelectorAll('[data-act=done]').forEach(btn =>
        btn.onclick = () => {
          const body = `<div class="field"><label>复诊结论 / 处理意见</label><textarea id="rvConclusion" placeholder="复查结果、用药与随访调整建议…"></textarea></div>`;
          const { mask } = openModal({ title: `复诊登记 · ${btn.dataset.name}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="rvDone">完成登记</button>' });
          mask.querySelector('#rvDone').onclick = async () => {
            const conclusion = mask.querySelector('#rvConclusion').value.trim();
            const r = await safe(API.completeRevisit(btn.dataset.id, conclusion), null);
            if (r !== null) { toast('复诊结果已归档并生成随访工单'); closeModal(); reload(); }
          };
        });
    },
  },
};

/* ==================== 护士 / 照护员 ==================== */
VIEWS.nurse = {
  title: '社区照护员工作台',
  nav: [
    { key: 'overview', label: '今日任务' },
    { key: 'onboard', label: '接收出院' },
    { key: 'alerts', label: '预警与工单' },
    { key: 'agent', label: 'AI 接力台' },
    { key: 'elders', label: '在管老人' },
    { key: 'records', label: '护理记录' },
  ],
  pages: {
    async agent(main, user) {
      await renderAgentPage(main, user);
    },

    async overview(main, user) {
      const [followups, alertsOpen, elderList] = await Promise.all([
        safe(API.followups(), []), safe(API.alerts('未闭环'), []), safe(API.elders(), []),
      ]);
      const riskMap = Object.fromEntries(elderList.map(e => [e.id, e.risk]));
      const pending = followups.filter(f => f.status !== '已完成');
      const done = followups.filter(f => f.status === '已完成').length;
      const collect = followups.filter(f => f.type === '体征采集').length;
      const reload = () => VIEWS.nurse.pages.overview(main, user);
      const taskRows = followups.map(t => `
        <div class="task-item">
          <div class="t-icon">📋</div>
          <div style="min-width:0">
            <div class="t-name">${esc(t.elder_name)} <span class="tag ${RISK_TAG[riskMap[t.elder_id]] || 'tag-blue'}">${riskMap[t.elder_id] || '待确认'}危</span></div>
            <div class="t-desc">${esc(t.type)} · ${esc(t.scheduled_at || '')} · ${esc(t.address || '')}<br>${esc(t.note || '')}</div>
          </div>
          <div class="t-right">
            <span class="tag tag-blue">${esc(t.status)}</span>
            ${t.status === '已完成'
              ? '<button class="btn btn-ghost" disabled>已完成</button>'
              : `<button class="btn btn-primary" data-act="exec" data-id="${t.id}" data-name="${esc(t.elder_name)}">开始执行</button>`}
          </div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('今日任务', user)}
      <div class="alert-bar warn">
        <div>
          <div class="a-title">⚠ ${alertsOpen.length} 条预警 + ${pending.length} 张工单待处理</div>
          <ul>${alertsOpen.map(a => `<li>${esc(a.elder_name)} ${esc(a.reason)}</li>`).join('') || '<li>暂无预警</li>'}</ul>
        </div>
        <button class="btn btn-ghost a-action" data-act="alerts">查看工单</button>
      </div>
      <div class="metrics">
        <div class="metric accent-yellow"><div class="m-label">今日待随访</div><div class="m-num">${pending.length}</div><div class="m-sub">✦ AI 按风险分层自动排期</div></div>
        <div class="metric"><div class="m-label">待上门</div><div class="m-num">${followups.filter(f => f.type === '上门随访').length}</div><div class="m-sub">含地址与注意事项</div></div>
        <div class="metric"><div class="m-label">待采集体征</div><div class="m-num">${collect}</div><div class="m-sub">血压 / 血糖 / 心率</div></div>
        <div class="metric"><div class="m-label">已完成</div><div class="m-num">${done}</div><div class="m-sub">今日已回填记录</div></div>
      </div>
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>任务与工单列表</h3><span class="hint">按预约时间排序</span></div>
        <div class="task-list">${taskRows || '<div class="c-desc">暂无待办工单</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          if (btn.dataset.act === 'exec') {
            const id = btn.dataset.id, name = btn.dataset.name;
            const body = `<div class="field"><label>随访 / 体征回填备注</label><textarea id="fuNote" placeholder="记录体征、伤口、服药与主诉…"></textarea></div>`;
            const { mask } = openModal({ title: `执行随访 · ${name}`, bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="fuSave">提交回填</button>' });
            mask.querySelector('#fuSave').onclick = async () => {
              const note = mask.querySelector('#fuNote').value.trim();
              const r = await safe(API.completeFollowup(id, note || '随访完成，体征已记录'), null);
              if (r !== null) { toast('随访记录已回填，待医生审核'); closeModal(); reload(); }
            };
          } else if (btn.dataset.act === 'alerts') {
            await openAlertCenter(reload);
          }
        };
      });
    },

    async onboard(main, user) {
      const [elders, plans] = await Promise.all([safe(API.elders(), []), safe(API.plans(), [])]);
      const elderMap = Object.fromEntries(elders.map(e => [e.id, e]));
      const reload = () => VIEWS.nurse.pages.onboard(main, user);
      const cards = elders.map(e => `
        <div class="plan-block">
          <div class="pb-title">${esc(e.name)} · ${esc(e.diag)} <span class="tag ${RISK_TAG[e.risk]}">${e.risk}危</span></div>
          <div class="pb-desc">${e.age} 岁 · 出院第 ${e.day} 天 · 完成率 ${e.rate}% · ${esc(e.living || '')} · ${esc(e.address || '—')}</div>
          <div style="margin-top:8px;display:flex;gap:8px"><button class="link-btn" data-act="detail" data-id="${e.id}">查看档案与方案</button></div>
        </div>`).join('');
      const planRows = plans.map(p => {
        const e = elderMap[p.elder_id] || {};
        return `<div class="plan-block">
          <div class="pb-title">${esc(e.name || '')} · ${esc(p.type)} <span class="tag ${p.status === '已签发' ? 'tag-green' : 'tag-yellow'}">${esc(p.status)}</span></div>
          <div class="pb-desc">${esc(p.content).replace(/\n/g, '<br>')}</div>
        </div>`;
      }).join('');
      main.innerHTML = `
      ${pageTopbar('接收出院', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>交接校验清单</h3><span class="hint">AI 辅助生成字段与模板</span></div>
        <div class="check-list">
          <div class="check-item"><div class="idx">1</div><div><div class="c-title">出院小结已结构化 ${aiChip('AI 解析')}</div><div class="c-desc">药名、剂量、管路、伤口、复诊时间由 AI 自动提取，护士核对落字段</div></div></div>
          <div class="check-item"><div class="idx">2</div><div><div class="c-title">随访计划已签发 ${aiChip('AI 排班')}</div><div class="c-desc">AI 按风险分层排随访频次，医生签字后任务同步到家属端</div></div></div>
          <div class="check-item"><div class="idx">3</div><div><div class="c-title">预警已闭环</div><div class="c-desc">关闭预警须填写处置结论，否则下一班不可见</div></div></div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>AI 随访 / 康复计划</h3><span class="hint">护士按计划上门并回填记录</span></div>
        <div class="check-list">${planRows || '<div class="c-desc">暂未签发方案</div>'}</div>
      </div>
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>本次交接老人档案</h3></div>
        <div class="check-list">${cards || '<div class="c-desc">暂无档案</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act=detail]').forEach(btn =>
        btn.onclick = () => openElderDetail(btn.dataset.id, { onSaved: reload }));
    },

    async alerts(main, user) {
      const [alertsOpen, followups, elderList] = await Promise.all([
        safe(API.alerts('未闭环'), []), safe(API.followups(), []), safe(API.elders(), []),
      ]);
      const riskMap = Object.fromEntries(elderList.map(e => [e.id, e.risk]));
      const reload = () => VIEWS.nurse.pages.alerts(main, user);
      const alertRows = alertsOpen.map(a => `
        <div class="plan-block" data-alert="${a.id}">
          <div class="pb-title">${esc(a.elder_name)} · ${esc(a.level)}危预警</div>
          <div class="pb-desc">${esc(a.reason)}</div>
          <textarea data-conclusion placeholder="处置结论（闭环必填）…" style="margin-top:8px"></textarea>
          <div style="margin-top:8px;text-align:right"><button class="btn btn-primary" data-close-alert>闭环预警</button></div>
        </div>`).join('');
      const pending = followups.filter(f => f.status !== '已完成');
      const fuRows = pending.map(t => `
        <div class="task-item">
          <div class="t-icon">📋</div>
          <div style="min-width:0"><div class="t-name">${esc(t.elder_name)} <span class="tag ${RISK_TAG[riskMap[t.elder_id]] || 'tag-blue'}">${riskMap[t.elder_id] || '待确认'}危</span></div>
          <div class="t-desc">${esc(t.type)} · ${esc(t.scheduled_at || '')} · ${esc(t.address || '')}</div></div>
          <div class="t-right"><span class="tag tag-blue">${esc(t.status)}</span></div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('预警与工单', user)}
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>未闭环预警</h3><span class="hint">填写结论后闭环</span></div>
          <div class="check-list">${alertRows || '<div class="c-desc">暂无未闭环预警</div>'}</div>
        </div>
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>待办工单</h3></div>
          <div class="task-list">${fuRows || '<div class="c-desc">暂无待办工单</div>'}</div>
        </div>
      </div>`;
      main.querySelectorAll('[data-alert]').forEach(block => {
        const id = block.dataset.alert;
        block.querySelector('[data-close-alert]').onclick = async () => {
          const conclusion = block.querySelector('[data-conclusion]').value.trim();
          if (!conclusion) { toast('请填写处置结论后再闭环'); return; }
          const r = await safe(API.closeAlert(id, conclusion), null);
          if (r !== null) { toast('预警已闭环'); reload(); }
        };
      });
    },

    async elders(main, user) {
      const elders = await safe(API.elders(), []);
      const reload = () => VIEWS.nurse.pages.elders(main, user);
      const cards = elders.map(e => `
        <div class="plan-block">
          <div class="pb-title">${esc(e.name)} <span class="tag ${RISK_TAG[e.risk]}">${e.risk}危</span></div>
          <div class="pb-desc">${esc(e.diag)} · ${e.age} 岁 · 出院第 ${e.day} 天</div>
          <div class="pb-desc">${esc(e.living || '')} · ${esc(e.address || '—')} · 完成率 ${e.rate}% · 未闭环预警 ${e.alerts} 条</div>
          <div style="margin-top:8px;display:flex;gap:8px"><button class="link-btn" data-act="detail" data-id="${e.id}">查看完整档案</button></div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('在管老人', user)}
      <div class="card"><div class="card-head"><span class="bar"></span><h3>在管老人档案</h3><span class="hint">点击查看档案、方案、复诊与护理记录</span></div><div class="check-list">${cards || '<div class="c-desc">暂无档案</div>'}</div></div>`;
      main.querySelectorAll('[data-act=detail]').forEach(btn =>
        btn.onclick = () => openElderDetail(btn.dataset.id, { onSaved: reload }));
    },

    async records(main, user) {
      const elders = await safe(API.elders(), []);
      const elderMap = Object.fromEntries(elders.map(e => [e.id, e]));
      const per = await Promise.all(elders.map(e => safe(API.elderRecords(e.id), [])));
      const all = per.flat().map(r => ({ ...r, elder_name: elderMap[r.elder_id]?.name || '' }))
        .sort((a, b) => (b.created_at || '').localeCompare(a.created_at || ''));
      const rows = all.map(r => `
        <div class="tl-item">
          <div class="tl-time">${esc((r.created_at || '').slice(5, 16))}</div>
          <div class="tl-content">
            <div class="tl-title">${esc(r.elder_name)} · ${esc(r.title)}</div>
            <div class="tl-desc">${esc(r.detail || '')}${r.source ? ` · 记录人：${esc(r.source)}` : ''}</div>
          </div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('护理记录', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>随访 / 护理 / 用药时间线</h3><span class="hint">按时间倒序</span></div>
        <div class="timeline">${rows || '<div class="c-desc">暂无记录</div>'}</div>
      </div>`;
    },
  },
};

/* ==================== 家属 ==================== */
VIEWS.family = {
  title: '家属端',
  nav: [
    { key: 'overview', label: '首页' },
    { key: 'plans', label: '康复计划' },
    { key: 'announcements', label: '复诊与通知' },
    { key: 'chat', label: '联系医护' },
  ],
  pages: {
    async overview(main, user, go) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const records = elder ? await safe(API.elderRecords(elder.id), []) : [];
      const latestVital = records.find(r => r.category === '体征');
      const reload = () => VIEWS.family.pages.overview(main, user, go);
      main.innerHTML = `
      <div class="c-greet">
        <div><div class="hi">${esc(user.name)}，您好</div><div class="sub">${elder ? `您关注的 ${esc(elder.name)} 老人，出院第 ${elder.day} 天` : '暂无绑定老人档案'}</div></div>
      </div>
      ${elder ? `<div class="care-banner"><div><div class="c-title">${elder.living === '独居' ? '老人目前独居' : '老人与子女同住'}</div><div class="c-desc">${elder.living === '独居' ? '请多留意每日打卡与体征上报，异常会自动提醒社区医生。' : '家属可协助完成打卡、服药与体征上报。'}</div></div></div>` : ''}
      <div class="status-card">
        <div><span class="tag ${RISK_TAG[elder?.risk] || 'tag-green'}">${elder?.risk || '低'}危 · 需关注</span><div class="big" style="margin-top:8px">恢复中</div></div>
        <div style="width:1px;height:48px;background:var(--border)"></div>
        <div class="stat-mini"><span class="v">${latestVital?.value || '—'}</span><span class="k">最新血压 mmHg</span></div>
        <div class="stat-mini"><span class="v">${elder?.rate ?? '-'}%</span><span class="k">任务完成率</span></div>
        <div class="stat-mini"><span class="v">第 ${elder?.day ?? '-'} 天</span><span class="k">居家康复</span></div>
      </div>
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>今日康复任务</h3><span class="hint">✦ AI 方案生成 · 医生签发</span></div>
        <div class="task-list">
          <div class="task-item"><div class="t-icon">🏃</div><div><div class="t-name">康复训练完成打卡 ${aiChip('AI 计划')}</div><div class="t-desc">按方案完成动作后协助打卡，进度同步医生</div></div><div class="t-right"><button class="btn btn-primary btn-lg" data-act="checkin">完成打卡</button></div></div>
          <div class="task-item"><div class="t-icon">💊</div><div><div class="t-name">服药记录</div><div class="t-desc">午饭后半小时 · 不可自行停药</div></div><div class="t-right"><button class="btn btn-ghost btn-lg" data-act="med">已服药</button></div></div>
          <div class="task-item"><div class="t-icon">⚠</div><div><div class="t-name">测量并上报血压 ${aiChip('异常解读')}</div><div class="t-desc">早晚各一次，异常会自动提醒社区医生</div></div><div class="t-right"><button class="btn btn-ghost btn-lg" data-act="report">去上报</button></div></div>
        </div>
        <div style="margin-top:14px;display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn btn-ghost" data-act="exercise">AI 康复动作指导</button>
          <button class="btn btn-ghost" data-go="announcements">查看复诊安排</button>
        </div>
      </div>
      <button class="help-fab" data-act="help">一键求助</button>`;
      main.querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          if (!elder) { toast('暂无绑定老人档案'); return; }
          const act = btn.dataset.act;
          if (act === 'checkin') {
            const r = await safe(API.addRecord(elder.id, { category: '护理', title: '康复任务打卡', detail: '家属协助完成当日康复训练', value: '', source: user.name }), null);
            if (r !== null) { toast('打卡成功，已记入康复档案'); reload(); }
          } else if (act === 'med') {
            const r = await safe(API.addRecord(elder.id, { category: '用药', title: '用药记录 · 已服药', detail: '家属协助记录服药情况', value: '', source: user.name }), null);
            if (r !== null) { toast('服药记录已保存'); reload(); }
          } else if (act === 'report') {
            openVitalModal(elder.id, reload);
          } else if (act === 'exercise') {
            openExerciseGuide(elder.id, elder.name);
          } else if (act === 'help') {
            await openChat(elder.id, elder.name);
          }
        };
      });
    },

    async plans(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const all = elder ? await safe(API.plans(), []) : [];
      const elderPlans = all.filter(p => p.elder_id === elder?.id);
      const rows = elderPlans.length ? elderPlans.map(p => `
        <div class="plan-block">
          <div class="pb-title">${esc(p.type)}方案 · <span class="tag ${p.status === '已签发' ? 'tag-green' : p.status === '已调整' ? 'tag-blue' : 'tag-yellow'}">${esc(p.status)}</span></div>
          <div class="pb-desc">${esc(p.content).replace(/\n/g, '<br>')}</div>
        </div>`).join('') : '<div class="c-desc" style="padding:16px">暂未签发康复计划</div>';
      main.innerHTML = `
      ${pageTopbar('康复计划', user)}
      <div class="card"><div class="card-head"><span class="bar"></span><h3>${esc(elder?.name || '')} 的康复计划</h3><span class="hint">医生签发后同步家属端</span></div>
        <div class="check-list">${rows}</div>
        ${elder ? `<div style="margin-top:14px"><button class="btn btn-primary" data-act="exercise">查看 AI 康复动作指导</button></div>` : ''}
      </div>`;
      const exBtn = main.querySelector('[data-act=exercise]');
      if (exBtn) exBtn.onclick = () => openExerciseGuide(elder.id, elder.name);
    },

    async announcements(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const [announcements, revisits, records] = await Promise.all([
        safe(API.announcements(), []),
        safe(API.revisits(), []),
        elder ? safe(API.elderRecords(elder.id), []) : [],
      ]);
      const annRows = announcements.map(a => `
        <div class="check-item"><div class="idx" style="background:#EAF1F8;color:var(--blue)">i</div>
          <div><div class="c-title">${esc(a.title)}</div><div class="c-desc">${esc(a.content)}</div><div class="p-meta">${esc(a.publisher || '管理员')} · ${esc((a.created_at || '').slice(5, 16))}</div></div>
        </div>`).join('');
      const rvRows = revisits.map(r => `
        <div class="plan-block">
          <div class="pb-title">复诊 · ${esc(r.plan_at || '待定')} <span class="tag ${r.status === '已完成' ? 'tag-green' : 'tag-yellow'}">${esc(r.status)}</span></div>
          <div class="pb-desc">${esc(r.reason || '—')}${r.conclusion ? `<br>结论：${esc(r.conclusion)}` : ''}</div>
        </div>`).join('');
      const recRows = records.filter(r => ['随访', '复诊'].includes(r.category)).slice(0, 8).map(r => `
        <div class="tl-item"><div class="tl-time">${esc((r.created_at || '').slice(5, 16))}</div>
          <div class="tl-content"><div class="tl-title">${esc(r.title)}</div><div class="tl-desc">${esc(r.detail || '')}</div></div></div>`).join('');
      main.innerHTML = `
      ${pageTopbar('复诊与通知', user)}
      <div class="card"><div class="card-head"><span class="bar"></span><h3>复诊安排与结果</h3><span class="hint">复诊完成后结果同步家属端</span></div><div class="check-list">${rvRows || '<div class="c-desc">暂无复诊安排</div>'}</div></div>
      <div class="card"><div class="card-head"><span class="bar"></span><h3>随访 / 复诊记录</h3></div><div class="timeline">${recRows || '<div class="c-desc">暂无归档记录</div>'}</div></div>
      <div class="card"><div class="card-head"><span class="bar"></span><h3>通知公告</h3></div><div class="check-list">${annRows || '<div class="c-desc">暂无公告</div>'}</div></div>`;
    },

    async chat(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      if (!elder) { main.innerHTML = '<div class="c-desc" style="padding:24px">暂无绑定老人档案</div>'; return; }
      const messages = await safe(API.messages(elder.id), []);
      const me = user;
      const render = (list) => {
        const box = main.querySelector('#chatMsgs');
        box.innerHTML = (list || []).map(m => {
          const mine = me && m.sender_role === me.role && m.sender_name === me.name;
          const side = mine ? 'mine' : 'other';
          return `<div class="chat-row ${side}">
            <div class="chat-bubble">
              <div class="chat-who">${esc(m.sender_name)} · ${roleLabel(m.sender_role)}</div>
              <div class="chat-text">${esc(m.content)}</div>
              <div class="chat-time">${esc((m.created_at || '').slice(5, 16))}</div>
            </div></div>`;
        }).join('') || '<div class="c-desc" style="padding:20px;text-align:center">暂无消息，先发起一条咨询</div>';
        box.scrollTop = box.scrollHeight;
      };
      const phrases = ['我有点头晕', '伤口有点肿', '伤口有点疼', '想联系医生', '今天按时服药了'];
      main.innerHTML = `
      ${pageTopbar('联系医护', user)}
      <div class="card">
        <div class="chat">
          <div class="chat-msgs" id="chatMsgs"></div>
          <div class="quick-reply" id="quickReply">${phrases.map(p => `<button type="button" class="qr-chip" data-phrase="${esc(p)}">${esc(p)}</button>`).join('')}</div>
          <div class="chat-input">
            <button class="voice-btn" id="voiceBtn" title="语音输入" style="display:none">语音</button>
            <input id="chatText" placeholder="输入消息，回车发送…" autocomplete="off">
            <button class="btn btn-primary" id="chatSend">发送</button>
          </div>
          <div class="chat-side"><button class="link-btn" id="aiAssist">先问 AI 健康助手</button></div>
        </div>
      </div>`;
      render(messages);
      const input = main.querySelector('#chatText');
      const send = async (text) => {
        const content = (text || input.value).trim();
        if (!content) return;
        const msg = await safe(API.sendMessage(elder.id, content), null);
        if (msg) { input.value = ''; render(await safe(API.messages(elder.id), [])); }
      };
      main.querySelectorAll('[data-phrase]').forEach(b => b.onclick = () => send(b.dataset.phrase));
      main.querySelector('#chatSend').onclick = () => send();
      input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
      main.querySelector('#aiAssist').onclick = () => openAiChat(elder.id, elder.name);
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      const voiceBtn = main.querySelector('#voiceBtn');
      if (SR) {
        voiceBtn.style.display = '';
        const rec = new SR(); rec.lang = 'zh-CN'; rec.interimResults = false;
        voiceBtn.onclick = () => { try { rec.start(); } catch { toast('语音识别启动失败，请使用快捷短语'); } };
        rec.onresult = e => { input.value = e.results[0][0].transcript; };
        rec.onerror = () => toast('语音识别不可用，请使用快捷短语');
      }
      input.focus();
    },
  },
};

/* ==================== 老人本人 ==================== */
VIEWS.elder = {
  title: '出院老人端',
  nav: [
    { key: 'overview', label: '我的首页' },
    { key: 'plans', label: '康复计划' },
    { key: 'announcements', label: '复诊通知' },
    { key: 'chat', label: '联系医生' },
  ],
  pages: {
    async overview(main, user, go) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const records = elder ? await safe(API.elderRecords(elder.id), []) : [];
      const latestVital = records.find(r => r.category === '体征');
      const reload = () => VIEWS.elder.pages.overview(main, user, go);
      const alone = elder?.living === '独居';
      main.innerHTML = `
      <div class="c-greet">
        <div><div class="hi">${esc(user.name)}，您好</div><div class="sub">今天是居家康复第 ${elder?.day ?? '-'} 天，慢慢来，不舒服随时按求助。</div></div>
      </div>
      <div class="care-banner"><div>
        <div class="c-title">${alone ? '您目前一个人居住' : '家人在身边陪同'}</div>
        <div class="c-desc">${alone ? '如有头晕、胸闷、伤口肿痛，请立刻点下方“一键求助”，社区医生会尽快联系您。' : '家属会协助您打卡、服药和测量血压，有不舒服也可以直接告诉医生。'}</div>
      </div></div>
      <div class="status-card">
        <div><span class="tag ${RISK_TAG[elder?.risk] || 'tag-green'}">${elder?.risk || '低'}危</span><div class="big" style="margin-top:8px">恢复中</div></div>
        <div style="width:1px;height:48px;background:var(--border)"></div>
        <div class="stat-mini"><span class="v">${latestVital?.value || '—'}</span><span class="k">最新血压</span></div>
        <div class="stat-mini"><span class="v">${elder?.rate ?? '-'}%</span><span class="k">任务完成</span></div>
      </div>
      <div class="card elder-wrap">
        <div class="card-head"><span class="bar"></span><h3>今天要做的事</h3></div>
        <div class="task-list">
          <div class="task-item elder-task"><div class="t-icon">🏃</div><div><div class="t-name">完成康复训练打卡</div><div class="t-desc">按医生教的动作慢慢做，做完点打卡</div></div><div class="t-right"><button class="btn btn-primary elder-big-btn" data-act="checkin">已完成</button></div></div>
          <div class="task-item elder-task"><div class="t-icon">💊</div><div><div class="t-name">按时吃药</div><div class="t-desc">饭后半小时服药，不要自己停药</div></div><div class="t-right"><button class="btn btn-ghost elder-big-btn" data-act="med">已服药</button></div></div>
          <div class="task-item elder-task"><div class="t-icon">⚠</div><div><div class="t-name">量血压并上报</div><div class="t-desc">早晚各量一次，偏高会提醒医生</div></div><div class="t-right"><button class="btn btn-ghost elder-big-btn" data-act="report">去量血压</button></div></div>
        </div>
        <div style="margin-top:14px;display:flex;gap:10px;flex-wrap:wrap">
          <button class="btn btn-ghost elder-big-btn" data-act="exercise">看康复动作指导</button>
          <button class="btn btn-ghost elder-big-btn" data-go="announcements">复诊安排</button>
        </div>
      </div>
      <button class="help-fab" data-act="help">一键求助</button>`;
      main.querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
      main.querySelectorAll('[data-act]').forEach(btn => {
        btn.onclick = async () => {
          if (!elder) { toast('暂无档案信息'); return; }
          const act = btn.dataset.act;
          if (act === 'checkin') {
            const r = await safe(API.addRecord(elder.id, { category: '护理', title: '康复训练打卡', detail: '老人本人完成当日康复训练', value: '', source: user.name }), null);
            if (r !== null) { toast('打卡成功，医生能看到'); reload(); }
          } else if (act === 'med') {
            const r = await safe(API.addRecord(elder.id, { category: '用药', title: '用药记录 · 已服药', detail: '老人本人记录服药情况', value: '', source: user.name }), null);
            if (r !== null) { toast('已记下服药'); reload(); }
          } else if (act === 'report') {
            openVitalModal(elder.id, reload);
          } else if (act === 'exercise') {
            openExerciseGuide(elder.id, elder.name);
          } else if (act === 'help') {
            await openChat(elder.id, elder.name);
          }
        };
      });
    },

    async plans(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const all = elder ? await safe(API.plans(), []) : [];
      const elderPlans = all.filter(p => p.elder_id === elder?.id);
      const rows = elderPlans.length ? elderPlans.map(p => `
        <div class="plan-block">
          <div class="pb-title">${esc(p.type)} · <span class="tag ${p.status === '已签发' ? 'tag-green' : 'tag-yellow'}">${esc(p.status)}</span></div>
          <div class="pb-desc">${esc(p.content).replace(/\n/g, '<br>')}</div>
        </div>`).join('') : '<div class="c-desc" style="padding:16px">医生还没下发康复计划，请耐心等待。</div>';
      main.innerHTML = `
      ${pageTopbar('康复计划', user)}
      <div class="card"><div class="card-head"><span class="bar"></span><h3>${esc(elder?.name || '')} 的康复计划</h3></div>
        <div class="check-list">${rows}</div>
        ${elder ? `<div style="margin-top:14px"><button class="btn btn-primary elder-big-btn" data-act="exercise">查看 AI 康复动作指导</button></div>` : ''}
      </div>`;
      const exBtn = main.querySelector('[data-act=exercise]');
      if (exBtn) exBtn.onclick = () => openExerciseGuide(elder.id, elder.name);
    },

    async announcements(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      const [announcements, revisits, records] = await Promise.all([
        safe(API.announcements(), []),
        safe(API.revisits(), []),
        elder ? safe(API.elderRecords(elder.id), []) : [],
      ]);
      const rvRows = revisits.map(r => `
        <div class="plan-block">
          <div class="pb-title">复诊 · ${esc(r.plan_at || '待定')} <span class="tag ${r.status === '已完成' ? 'tag-green' : 'tag-yellow'}">${esc(r.status)}</span></div>
          <div class="pb-desc">${esc(r.reason || '—')}${r.conclusion ? `<br>医生意见：${esc(r.conclusion)}` : ''}</div>
        </div>`).join('');
      const recRows = records.filter(r => ['随访', '复诊'].includes(r.category)).slice(0, 8).map(r => `
        <div class="tl-item"><div class="tl-time">${esc((r.created_at || '').slice(5, 16))}</div>
          <div class="tl-content"><div class="tl-title">${esc(r.title)}</div><div class="tl-desc">${esc(r.detail || '')}</div></div></div>`).join('');
      const annRows = announcements.map(a => `
        <div class="check-item"><div class="idx" style="background:#EAF1F8;color:var(--blue)">i</div>
          <div><div class="c-title">${esc(a.title)}</div><div class="c-desc">${esc(a.content)}</div></div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('复诊通知', user)}
      <div class="card"><div class="card-head"><span class="bar"></span><h3>复诊安排</h3></div><div class="check-list">${rvRows || '<div class="c-desc">暂无复诊安排</div>'}</div></div>
      <div class="card"><div class="card-head"><span class="bar"></span><h3>医生上门 / 随访记录</h3></div><div class="timeline">${recRows || '<div class="c-desc">暂无记录</div>'}</div></div>
      <div class="card"><div class="card-head"><span class="bar"></span><h3>通知公告</h3></div><div class="check-list">${annRows || '<div class="c-desc">暂无公告</div>'}</div></div>`;
    },

    async chat(main, user) {
      const elders = await safe(API.elders(), []);
      const elder = elders[0] || null;
      if (!elder) { main.innerHTML = '<div class="c-desc" style="padding:24px">暂无档案信息</div>'; return; }
      const messages = await safe(API.messages(elder.id), []);
      const me = user;
      const render = (list) => {
        const box = main.querySelector('#chatMsgs');
        box.innerHTML = (list || []).map(m => {
          const mine = me && m.sender_role === me.role && m.sender_name === me.name;
          const side = mine ? 'mine' : 'other';
          return `<div class="chat-row ${side}">
            <div class="chat-bubble">
              <div class="chat-who">${esc(m.sender_name)} · ${roleLabel(m.sender_role)}</div>
              <div class="chat-text">${esc(m.content)}</div>
              <div class="chat-time">${esc((m.created_at || '').slice(5, 16))}</div>
            </div></div>`;
        }).join('') || '<div class="c-desc" style="padding:20px;text-align:center">还没有消息，点下面的短语就能发给医生。</div>';
        box.scrollTop = box.scrollHeight;
      };
      const phrases = ['我有点头晕', '伤口有点肿', '伤口有点疼', '胸口有点闷', '想联系医生', '今天按时服药了'];
      main.innerHTML = `
      ${pageTopbar('联系医生', user)}
      <div class="card">
        <div class="chat">
          <div class="chat-msgs" id="chatMsgs"></div>
          <div class="quick-reply" id="quickReply">${phrases.map(p => `<button type="button" class="qr-chip" data-phrase="${esc(p)}">${esc(p)}</button>`).join('')}</div>
          <div class="chat-input">
            <button class="voice-btn" id="voiceBtn" title="语音输入" style="display:none">语音</button>
            <input id="chatText" placeholder="点短语或说话告诉医生…" autocomplete="off">
            <button class="btn btn-primary" id="chatSend">发送</button>
          </div>
          <div class="chat-side"><button class="link-btn" id="aiAssist">先问 AI 健康助手</button></div>
        </div>
      </div>`;
      render(messages);
      const input = main.querySelector('#chatText');
      const send = async (text) => {
        const content = (text || input.value).trim();
        if (!content) return;
        const msg = await safe(API.sendMessage(elder.id, content), null);
        if (msg) { input.value = ''; render(await safe(API.messages(elder.id), [])); }
      };
      main.querySelectorAll('[data-phrase]').forEach(b => b.onclick = () => send(b.dataset.phrase));
      main.querySelector('#chatSend').onclick = () => send();
      input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
      main.querySelector('#aiAssist').onclick = () => openAiChat(elder.id, elder.name);
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      const voiceBtn = main.querySelector('#voiceBtn');
      if (SR) {
        voiceBtn.style.display = '';
        const rec = new SR(); rec.lang = 'zh-CN'; rec.interimResults = false;
        voiceBtn.onclick = () => { try { rec.start(); } catch { toast('语音识别启动失败，请用短语'); } };
        rec.onresult = e => { input.value = e.results[0][0].transcript; };
        rec.onerror = () => toast('语音识别不可用，请用快捷短语');
      }
      input.focus();
    },
  },
};

/* ==================== 管理员 ==================== */
const ROLE_LABEL = { family_doctor: '家庭医生', hospital: '医院主治医生', nurse: '社区照护员', family: '家属', elder: '出院老人', admin: '管理员' };

VIEWS.admin = {
  title: '系统管理',
  nav: [
    { key: 'overview', label: '数据总览' },
    { key: 'users', label: '账号与权限' },
    { key: 'elders', label: '档案管理' },
    { key: 'report', label: '运营报表' },
    { key: 'config', label: '系统配置' },
  ],
  pages: {
    async overview(main, user, go) {
      const [stats, elders, users] = await Promise.all([safe(API.stats(), {}), safe(API.elders(), []), safe(API.adminUsers(), [])]);
      main.innerHTML = `
      ${pageTopbar('数据总览', user)}
      <div class="metrics">
        <div class="metric"><div class="m-label">在管老人</div><div class="m-num">${stats.total ?? elders.length}</div><div class="m-sub">出院随访期内</div></div>
        <div class="metric" data-go="users" style="cursor:pointer"><div class="m-label">账号总数</div><div class="m-num">${users.length}</div><div class="m-sub">医生 / 护士 / 家属</div></div>
        <div class="metric"><div class="m-label">随访完成率</div><div class="m-num">${stats.followupRate ?? 0}%</div><div class="m-sub">本周口径</div></div>
        <div class="metric"><div class="m-label">预警闭环率</div><div class="m-num">${stats.alertCloseRate ?? 0}%</div><div class="m-sub">${stats.alertsOpen ?? 0} 条待闭环</div></div>
      </div>
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>辖区风险分布</h3><span class="hint">AI 初筛 · 医生终判</span></div>
          ${riskBar('高风险', elders.filter(e => e.risk === '高').length, elders.length, '#D64545')}
          ${riskBar('中风险', elders.filter(e => e.risk === '中').length, elders.length, '#E8A13A')}
          ${riskBar('低风险', elders.filter(e => e.risk === '低').length, elders.length, '#2E9E77')}
          <div style="margin-top:18px;display:flex;gap:12px">
            <button class="btn btn-primary" data-go="report">导出报表</button>
            <button class="btn btn-ghost" data-go="users">账号管理</button>
            <button class="btn btn-ghost" data-go="config">系统配置</button>
          </div>
        </div>
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>系统说明</h3></div>
          <div class="check-list">
            <div class="check-item"><div class="idx">1</div><div><div class="c-title">权限分级</div><div class="c-desc">医生、照护员、老人、管理员数据相互隔离，仅按职责开放对应模块</div></div></div>
            <div class="check-item"><div class="idx">2</div><div><div class="c-title">数据安全</div><div class="c-desc">健康档案操作留痕，预警闭环记录不可删除，支持定期备份</div></div></div>
          </div>
        </div>
      </div>`;
      main.querySelectorAll('[data-go]').forEach(b => b.onclick = () => go(b.dataset.go));
    },

    async users(main, user) {
      const users = await safe(API.adminUsers(), []);
      const reload = () => VIEWS.admin.pages.users(main, user);
      const rows = users.map(a => `
        <div class="acc-row" style="display:flex;align-items:center;gap:12px;padding:13px 0;border-bottom:1px solid var(--border)">
          <div class="person" style="flex:1;min-width:0"><div class="p-avatar">${esc((a.name || a.username)[0])}</div><div><div class="p-name">${esc(a.name)}</div><div class="p-meta">@${esc(a.username)} · ${esc(ROLE_LABEL[a.role] || a.role)}</div></div></div>
          <span class="tag ${a.status === '启用' ? 'tag-green' : 'tag-yellow'}">${esc(a.status)}</span>
          ${a.id === user.id
            ? '<button class="link-btn" disabled style="opacity:.4;cursor:not-allowed">当前账号</button>'
            : `<button class="link-btn" data-act="toggle" data-id="${a.id}" data-self="0">${a.status === '启用' ? '停用' : '启用'}</button>`}
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('账号与权限', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>账号与权限</h3><span class="hint">按角色分级隔离；停用后该账号无法登录</span></div>
        <div>${rows || '<div class="c-desc">暂无账号</div>'}</div>
      </div>`;
      main.querySelectorAll('[data-act=toggle]').forEach(btn => btn.onclick = async () => {
        if (btn.dataset.self === '1') { toast('不可停用当前登录账号'); return; }
        const r = await safe(API.toggleUser(btn.dataset.id), null);
        if (r !== null) { toast(`账号已${r.status}`); reload(); }
      });
    },

    async elders(main, user) {
      const elders = await safe(API.elders(), []);
      const reload = () => VIEWS.admin.pages.elders(main, user);
      const rows = elders.map(e => `
        <div class="plan-block">
          <div class="pb-title">${esc(e.name)} · ${e.age} 岁 · <span class="tag ${RISK_TAG[e.risk]}">${e.risk}危</span></div>
          <div class="pb-desc">${esc(e.diag)} · ${esc(e.living || '')} · ${esc(e.address || '—')} · 联系 ${esc(e.contact || '—')}</div>
          <div style="margin-top:8px;display:flex;gap:8px">
            <button class="link-btn" data-act="detail" data-id="${e.id}">查看档案</button>
            <button class="link-btn" style="color:var(--red)" data-act="del" data-id="${e.id}" data-name="${esc(e.name)}">删除档案</button>
          </div>
        </div>`).join('');
      main.innerHTML = `
      ${pageTopbar('档案管理', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>老人档案</h3><span class="hint">仅管理员可新增 / 删除；医生可在业务端编辑信息</span></div>
        <div style="margin-bottom:14px"><button class="btn btn-primary" data-act="add">新增老人档案</button></div>
        <div class="check-list">${rows || '<div class="c-desc">暂无档案</div>'}</div>
      </div>`;
      main.querySelector('[data-act=add]').onclick = () => {
        const body = `
          <div class="grid-2" style="gap:12px">
            <div class="field"><label>姓名</label><input id="neName"></div>
            <div class="field"><label>年龄</label><input id="neAge" type="number"></div>
          </div>
          <div class="field"><label>性别</label><select id="neGender"><option>女</option><option>男</option></select></div>
          <div class="field"><label>出院诊断 / 慢病</label><input id="neDiag"></div>
          <div class="field"><label>联系电话</label><input id="neContact"></div>
          <div class="field"><label>现住址</label><input id="neAddress"></div>
          <div class="field"><label>居住情况</label><select id="neLiving"><option>与子女同住</option><option>独居</option></select></div>`;
        const { mask } = openModal({ title: '新增老人档案', bodyHTML: body, footerHTML: '<button class="btn btn-ghost" data-close>取消</button><button class="btn btn-primary" id="neSave">保存档案</button>' });
        mask.querySelector('#neSave').onclick = async () => {
          const payload = {
            name: mask.querySelector('#neName').value.trim(),
            age: mask.querySelector('#neAge').value,
            gender: mask.querySelector('#neGender').value,
            diag: mask.querySelector('#neDiag').value.trim(),
            contact: mask.querySelector('#neContact').value.trim(),
            address: mask.querySelector('#neAddress').value.trim(),
            living: mask.querySelector('#neLiving').value,
          };
          if (!payload.name || !payload.diag) { toast('姓名和诊断不能为空'); return; }
          const r = await safe(API.elderCreate(payload), null);
          if (r !== null) { toast('档案已建立'); closeModal(); reload(); }
        };
      };
      main.querySelectorAll('[data-act]').forEach(btn => {
        if (btn.dataset.act === 'detail') btn.onclick = () => openElderDetail(btn.dataset.id, {});
        else if (btn.dataset.act === 'del') btn.onclick = async () => {
          if (!confirm(`确认删除老人「${btn.dataset.name}」的档案及关联记录？此操作不可恢复。`)) return;
          const r = await safe(API.elderDelete(btn.dataset.id), null);
          if (r !== null) { toast('档案已删除'); reload(); }
        };
      });
    },

    async report(main, user) {
      const [stats, elders] = await Promise.all([safe(API.stats(), {}), safe(API.elders(), [])]);
      main.innerHTML = `
      ${pageTopbar('运营报表', user)}
      <div class="metrics">
        <div class="metric"><div class="m-label">在管老人</div><div class="m-num">${stats.total ?? elders.length}</div></div>
        <div class="metric"><div class="m-label">随访完成率</div><div class="m-num">${stats.followupRate ?? 0}%</div></div>
        <div class="metric"><div class="m-label">预警闭环率</div><div class="m-num">${stats.alertCloseRate ?? 0}%</div></div>
        <div class="metric"><div class="m-label">待闭环预警</div><div class="m-num">${stats.alertsOpen ?? 0}</div></div>
      </div>
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>辖区报表导出</h3><span class="hint">CSV 含 BOM，Excel 可直接打开中文</span></div>
        <div class="c-desc">导出全部在管老人档案、随访完成率与预警闭环率汇总。</div>
        <div style="margin-top:14px"><button class="btn btn-primary" data-act="export">导出 CSV 报表</button></div>
      </div>`;
      main.querySelector('[data-act=export]').onclick = () => downloadCsv('/api/admin/export.csv', '医养接力站-辖区报表.csv');
    },

    async config(main, user) {
      const [announcements, ai] = await Promise.all([safe(API.announcements(), []), safe(API.aiSettings(), {})]);
      const reload = () => VIEWS.admin.pages.config(main, user);
      const rows = announcements.map(a => `
        <div class="plan-block">
          <div class="pb-title">${esc(a.title)} <span class="p-meta">· ${esc(a.publisher || '管理员')} ${esc((a.created_at || '').slice(5, 16))}</span></div>
          <div class="pb-desc">${esc(a.content)}</div>
        </div>`).join('');
      const aiReady = ai && ai.configured;
      main.innerHTML = `
      ${pageTopbar('系统配置', user)}
      <div class="card">
        <div class="card-head"><span class="bar"></span><h3>AI 能力配置</h3>
          <span class="hint">${aiReady ? `已配置 · Key ${esc(ai.maskedKey || '')}` : '未配置 · 走本地规则兜底'}</span></div>
        <div class="c-desc">风险评分、方案草案、随访摘要、分诊答疑、康复动作指导都走这套配置；没填 Key 时自动退回本地规则，流程照样能演示。</div>
        <div class="grid-2" style="margin-top:14px">
          <div class="field"><label>接口地址</label><input id="aiBase" placeholder="https://api.deepseek.com/v1" value="${esc(ai.baseUrl || '')}"></div>
          <div class="field"><label>模型</label><input id="aiModel" placeholder="deepseek-chat" value="${esc(ai.model || '')}"></div>
          <div class="field"><label>API Key</label><input id="aiKey" type="password" placeholder="${aiReady ? '已保存，留空表示不改' : 'sk-…'}"></div>
          <div class="field"><label>超时（秒）</label><input id="aiTimeout" placeholder="30" value="${esc(ai.timeoutSeconds ?? 30)}"></div>
        </div>
        <div style="display:flex;gap:10px;margin-top:6px">
          <button class="btn btn-primary" data-act="ai-save">保存配置</button>
          <button class="btn btn-ghost" data-act="ai-test">测试连接</button>
          ${aiReady ? '<button class="btn btn-ghost" data-act="ai-clear">清空 Key</button>' : ''}
        </div>
        <div class="c-desc" id="aiResult" style="margin-top:10px"></div>
      </div>
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>发布公告</h3><span class="hint">发布后同步到家门口</span></div>
          <div class="field"><label>公告标题</label><input id="annTitle" placeholder="公告标题"></div>
          <div class="field"><label>公告内容</label><textarea id="annContent" placeholder="面向老人与照护员的通知内容…"></textarea></div>
          <button class="btn btn-primary" data-act="notice">发布公告</button>
        </div>
        <div class="card">
          <div class="card-head"><span class="bar"></span><h3>已发布公告</h3></div>
          <div class="check-list">${rows || '<div class="c-desc">暂无公告</div>'}</div>
        </div>
      </div>`;

      const aiPayload = () => ({
        baseUrl: main.querySelector('#aiBase').value.trim(),
        model: main.querySelector('#aiModel').value.trim(),
        apiKey: main.querySelector('#aiKey').value.trim(),
        timeoutSeconds: Number(main.querySelector('#aiTimeout').value) || null,
      });

      main.querySelector('[data-act=ai-save]').onclick = async () => {
        const r = await safe(API.saveAiSettings(aiPayload()), null);
        if (r !== null) { toast('AI 配置已保存，下一个请求生效'); reload(); }
      };
      main.querySelector('[data-act=ai-test]').onclick = async () => {
        const out = main.querySelector('#aiResult');
        out.textContent = '正在测试连接…';
        const r = await safe(API.testAiSettings(aiPayload()), null);
        if (r === null) { out.textContent = '测试失败'; return; }
        out.textContent = r.ok
          ? `连接成功 · 模型 ${r.model} · 耗时 ${r.latencyMs}ms · 回复「${r.reply}」`
          : `连接失败：${r.message}（耗时 ${r.latencyMs}ms）`;
      };
      const clearBtn = main.querySelector('[data-act=ai-clear]');
      if (clearBtn) clearBtn.onclick = async () => {
        if (!confirm('清空 API Key 后 AI 会退回本地规则，确认继续？')) return;
        const r = await safe(API.saveAiSettings({ clearKey: true }), null);
        if (r !== null) { toast('Key 已清空，AI 走本地规则'); reload(); }
      };

      main.querySelector('[data-act=notice]').onclick = async () => {
        const title = main.querySelector('#annTitle').value.trim();
        const content = main.querySelector('#annContent').value.trim();
        if (!title || !content) { toast('标题和内容不能为空'); return; }
        const r = await safe(API.publishAnnouncement({ title, content }), null);
        if (r !== null) { toast('公告已发布'); reload(); }
      };
    },
  },
};
