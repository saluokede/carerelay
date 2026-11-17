<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const phone = ref('13800000001')
const password = ref('nurse123')
const busy = ref(false)
const error = ref('')

const accounts = [
  { label: '社区护士', phone: '13800000001', password: 'nurse123', note: '签发计划、处理预警' },
  { label: '家庭医生', phone: '13800000002', password: 'doctor123', note: '看医生版周报' },
  { label: '家属', phone: '13800000003', password: 'family123', note: '看今天要做什么' },
  { label: '管理员', phone: '13800000000', password: 'admin123', note: '配置模型 Key（系统设置）' },
]

function fill(item: (typeof accounts)[number]) {
  phone.value = item.phone
  password.value = item.password
  error.value = ''
}

async function submit() {
  if (busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await auth.login(phone.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="story">
      <div class="story-mark">接</div>
      <h1>出院不是结束，<br />是照护开始的地方。</h1>
      <p class="lead">
        老人从医院回家，最危险的往往是头 30 天：药怎么吃、伤口怎么换、什么时候该回医院——
        没有人一条一条交代，也没有人第二天来问。
      </p>
      <p class="lead">
        医养接力站把这些事接过来：出院小结变成可执行的 30 天计划，家属照着做，护士看得见进度，
        出现红线症状立刻有人管。
      </p>
      <ul class="story-points">
        <li><span class="dot" />AI 只做语言工作，剂量与阈值由代码守</li>
        <li><span class="dot" />计划必须由护士签发，AI 不能自己生效</li>
        <li><span class="dot" />每一次模型调用都可回溯（谁、什么数据、何时采纳）</li>
      </ul>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div class="kicker">COMMUNITY CARE HANDOVER</div>
        <h2>登录工作台</h2>
      </div>

      <form class="stack" @submit.prevent="submit">
        <div class="field">
          <label for="phone">手机号</label>
          <input id="phone" v-model="phone" class="input" autocomplete="username" inputmode="numeric" />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <input id="password" v-model="password" class="input" type="password" autocomplete="current-password" />
        </div>
        <p v-if="error" class="note danger">{{ error }}</p>
        <button class="btn primary block lg" :disabled="busy" type="submit">
          <span v-if="busy" class="spin" />
          {{ busy ? '正在进入…' : '进入工作台' }}
        </button>
      </form>

      <div class="divider" />
      <div class="demo">
        <div class="demo-title">演示账号（点一下自动填入）</div>
        <button v-for="item in accounts" :key="item.phone" class="demo-item" @click="fill(item)">
          <span class="demo-role">{{ item.label }}</span>
          <span class="demo-note">{{ item.note }}</span>
          <span class="mono">{{ item.phone }}</span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(380px, 470px);
  background: var(--paper);
}

/* 左：一段手记。暖纸 + 墨绿，不用渐变球 */
.story {
  position: relative;
  padding: 72px clamp(34px, 7vw, 100px);
  background:
    repeating-linear-gradient(135deg, rgba(23, 37, 31, 0.016) 0 1px, transparent 1px 8px),
    radial-gradient(900px 380px at 8% 0%, rgba(145, 98, 27, 0.10), transparent 62%),
    linear-gradient(180deg, #f8f4ea, #f3ecdd);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.story::after {
  content: ""; position: absolute; top: 0; bottom: 0; right: 0; width: 1px;
  background: var(--line);
}
.story-mark {
  width: 46px; height: 46px; border-radius: 999px;
  background: var(--primary); color: #f7f3e7; font-size: 21px; font-weight: 700;
  display: grid; place-items: center; margin-bottom: 30px;
  box-shadow: 0 10px 24px -18px rgba(11, 47, 40, 0.9);
}
.story h1 {
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: clamp(30px, 3.6vw, 44px);
  line-height: 1.28;
  letter-spacing: 0.01em;
  color: var(--primary-deep);
  white-space: nowrap;
}
.story h1::after {
  content: ""; display: block; width: 76px; height: 3px; margin-top: 22px;
  background: var(--amber);
}
.lead { margin-top: 26px; max-width: 48ch; color: var(--ink-soft); font-size: 15px; line-height: 1.9; }
.story-points { list-style: none; padding: 0; margin: 32px 0 0; display: flex; flex-direction: column; gap: 11px; }
.story-points li { display: flex; gap: 11px; align-items: baseline; color: var(--ink-soft); font-size: 13.5px; }
.story-points .dot { width: 5px; height: 5px; border-radius: 1px; background: var(--primary-mid); flex: none; margin-top: 6px; }

/* 右：签到台 */
.panel {
  background: var(--card);
  padding: 58px clamp(28px, 4vw, 52px);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.panel-head { margin-bottom: 26px; }
.panel-head h2 {
  margin-top: 8px;
  font-family: "Songti SC", "Source Han Serif SC", "SimSun", serif;
  font-size: 26px;
  letter-spacing: 0.02em;
}
.demo { display: flex; flex-direction: column; gap: 8px; }
.demo-title { font-size: 12px; color: var(--ink-mute); letter-spacing: 0.06em; margin-bottom: 4px; }
.demo-item {
  display: grid;
  grid-template-columns: 72px 1fr auto;
  align-items: center;
  gap: 10px;
  text-align: left;
  padding: 10px 13px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fdfaf2;
  font-size: 13px;
  color: var(--ink);
  transition: border-color 0.15s ease, background 0.15s ease;
}
.demo-item:hover { border-color: #c9b99c; background: #f8f2e4; }
.demo-role { font-weight: 650; color: var(--primary-deep); }
.demo-note { color: var(--ink-mute); font-size: 12.5px; }

@media (max-width: 900px) {
  .login { grid-template-columns: 1fr; }
  .story { padding: 44px 24px; }
  .story::after { display: none; }
  .panel { border-top: 1px solid var(--line); padding: 34px 24px 52px; }
}
</style>