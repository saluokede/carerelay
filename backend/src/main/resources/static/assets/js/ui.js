/* 通用弹窗与聊天会话组件（依赖 api.js 的 API / Session / esc / toast） */

function closeModal() {
  document.querySelectorAll('.modal-mask').forEach(m => m.remove());
}

/* opts: { title, bodyHTML, footerHTML, onMount(mask) } */
function openModal(opts) {
  closeModal();
  const mask = document.createElement('div');
  mask.className = 'modal-mask';
  mask.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-head">
        <h3>${esc(opts.title || '')}</h3>
        <button class="modal-x" data-close aria-label="关闭">×</button>
      </div>
      <div class="modal-body">${opts.bodyHTML || ''}</div>
      ${opts.footerHTML ? `<div class="modal-foot">${opts.footerHTML}</div>` : ''}
    </div>`;
  document.body.appendChild(mask);
  mask.addEventListener('click', e => {
    if (e.target === mask || e.target.closest('[data-close]')) closeModal();
  });
  if (opts.onMount) opts.onMount(mask);
  return { mask, close: closeModal };
}

/* 聊天会话：elderId 绑定老人；当前用户本人消息靠右 */
async function openChat(elderId, elderName) {
  const bodyHTML = `
    <div class="chat">
      <div class="chat-msgs" id="chatMsgs"></div>
      <div class="chat-input">
        <input id="chatText" placeholder="输入消息，回车发送…" autocomplete="off">
        <button class="btn btn-primary" id="chatSend">发送</button>
      </div>
    </div>`;
  const { mask } = openModal({ title: `会话 · ${elderName || '老人档案'}`, bodyHTML });
  const me = Session.user;
  const box = mask.querySelector('#chatMsgs');

  const render = (list) => {
    box.innerHTML = (list || []).map(m => {
      const mine = me && (m.sender_role === me.role && m.sender_name === me.name);
      const side = mine ? 'mine' : 'other';
      return `<div class="chat-row ${side}">
        <div class="chat-bubble">
          <div class="chat-who">${esc(m.sender_name)} · ${roleLabel(m.sender_role)}</div>
          <div class="chat-text">${esc(m.content)}</div>
          <div class="chat-time">${esc((m.created_at || '').slice(5, 16))}</div>
        </div>
      </div>`;
    }).join('') || '<div class="c-desc" style="padding:20px;text-align:center">暂无消息，先发起一条咨询</div>';
    box.scrollTop = box.scrollHeight;
  };

  const list = await safe(API.messages(elderId), []);
  render(list);

  const input = mask.querySelector('#chatText');
  const send = async () => {
    const text = input.value.trim();
    if (!text) return;
    const msg = await safe(API.sendMessage(elderId, text), null);
    if (msg) {
      input.value = '';
      const cur = box.dataset.reload ? null : null;
      const fresh = await safe(API.messages(elderId), []);
      render(fresh);
    }
  };
  mask.querySelector('#chatSend').onclick = send;
  input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
  input.focus();
}

function roleLabel(role) {
  return ({ family: '家属', elder: '老人本人', family_doctor: '家庭医生', hospital: '医院医生', nurse: '护士', admin: '管理员' })[role] || role;
}
