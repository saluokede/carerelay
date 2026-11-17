<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchOverview, type Overview } from '@/api'
import { useAccessibility } from '@/composables/useAccessibility'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { largeText, highContrast, toggleLargeText, toggleContrast } = useAccessibility()

const overview = ref<Overview | null>(null)
const collapsed = ref(false)

const navAll = [
  { name: 'workbench', label: '护士工作台', icon: 'grid' },
  { name: 'elder-new', label: '接收出院', icon: 'plus' },
  { name: 'alerts', label: '预警与工单', icon: 'bell', badge: true },
  { name: 'ai-records', label: 'AI 记录', icon: 'spark', adminOnly: true },
  { name: 'settings', label: '系统设置', icon: 'gear', adminOnly: true },
]
// 「系统设置」只给管理员看：护士/医生看不到入口，服务端也会二次拒绝
const nav = computed(() => navAll.filter((item) => !item.adminOnly || auth.isAdmin))

const activeName = computed(() => {
  const n = route.name?.toString() ?? 'workbench'
  if (n.startsWith('elder-detail') || n === 'today' || n === 'followup' || n === 'report' || n === 'plan-review') {
    return 'workbench'
  }
  if (n === 'discharge-import') {
    return 'elder-new'
  }
  return n
})

const badgeCount = computed(() => (overview.value?.openAlerts ?? 0) + (overview.value?.pendingTickets ?? 0))

const roleText = computed(
  () =>
    ({ ADMIN: '管理员', NURSE: '社区护士', DOCTOR: '家庭医生', FAMILY: '家属' })[auth.user?.role ?? ''] ??
    '社区护士',
)

const icons: Record<string, string> = {
  grid: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></svg>',
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 5v14M5 12h14" stroke-linecap="round"/></svg>',
  bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M6 9a6 6 0 1 1 12 0c0 4 1.5 5.5 2 6H4c.5-.5 2-2 2-6Z"/><path d="M10 19a2 2 0 0 0 4 0" stroke-linecap="round"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/></svg>',
  spark: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3l2 5.2 5.2 2-5.2 2L12 17.4l-2-5.2L4.8 10.2l5.2-2z" stroke-linejoin="round"/><path d="M18.5 16.5l.8 2.1 2.1.8-2.1.8-.8 2.1-.8-2.1-2.1-.8 2.1-.8z" stroke-linejoin="round"/></svg>',
}

async function loadBadge() {
  try {
    overview.value = await fetchOverview()
  } catch {
    /* 角标失败不打断页面 */
  }
}

onMounted(loadBadge)

function go(name: string) {
  router.push({ name })
}

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="side">
      <div class="brand" @click="go('workbench')">
        <div class="mark">接</div>
        <div class="brand-text">
          <div class="brand-name">医养接力站</div>
          <div class="brand-sub">出院老人照护接力</div>
        </div>
      </div>

      <nav class="nav">
        <button
          v-for="item in nav"
          :key="item.name"
          class="nav-item"
          :class="{ on: activeName === item.name }"
          @click="go(item.name)"
        >
          <span class="ico" v-html="icons[item.icon]" />
          <span class="label">{{ item.label }}</span>
          <span v-if="item.badge && badgeCount" class="badge">{{ badgeCount }}</span>
        </button>
      </nav>

      <div class="side-foot">
        <div class="toggle-row">
          <button class="mini" :class="{ on: largeText }" title="放大字号" @click="toggleLargeText">大字</button>
          <button class="mini" :class="{ on: highContrast }" title="高对比度" @click="toggleContrast">高对比</button>
        </div>
        <div class="who">
          <div class="avatar">{{ (auth.displayName || '?').slice(0, 1) }}</div>
          <div class="who-text">
            <div class="who-name">{{ auth.displayName }}</div>
            <div class="who-role">{{ roleText }}</div>
          </div>
        </div>
        <button class="mini wide" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--sidebar) minmax(0, 1fr);
  min-height: 100vh;
}

/* 侧栏＝书脊：深墨绿，纸色字，一条细金线收边 */
.side {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 22px 16px 16px;
  background:
    radial-gradient(420px 220px at 20% -10%, rgba(214, 198, 160, 0.14), transparent 70%),
    linear-gradient(180deg, #10312a 0%, #0a211c 100%);
  color: #e6e1d3;
  border-right: 1px solid rgba(226, 216, 198, 0.10);
}

.brand { display: flex; gap: 11px; align-items: center; cursor: pointer; padding: 0 4px; }
/* 印章式圆标：呼应"接"这个动作 */
.mark {
  width: 40px; height: 40px; border-radius: 999px;
  background: #f6f2e9; color: #10312a;
  display: grid; place-items: center; font-weight: 700; font-size: 19px;
  box-shadow: inset 0 0 0 1px rgba(16, 49, 42, 0.12);
}
.brand-name { font-weight: 650; font-size: 15.5px; letter-spacing: 0.08em; color: #f4efe3; }
.brand-sub { font-size: 11px; color: #9fb6ad; margin-top: 2px; letter-spacing: 0.04em; }

.nav { display: flex; flex-direction: column; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  height: 42px; padding: 0 13px; border: none; border-radius: 999px;
  background: transparent; color: #b9ccc4; font-size: 14px; text-align: left;
  transition: background 0.15s ease, color 0.15s ease;
}
.nav-item:hover { background: rgba(246, 242, 233, 0.08); color: #f4efe3; }
/* 选中态＝一张纸签贴在书脊上 */
.nav-item.on {
  background: #f6f2e9; color: #10312a; font-weight: 600;
  box-shadow: 0 6px 16px -12px rgba(0, 0, 0, 0.7);
}
.nav-item .ico { width: 17px; height: 17px; display: inline-flex; opacity: 0.9; }
.nav-item .ico :deep(svg) { width: 100%; height: 100%; }
.nav-item .label { flex: 1; }
.badge {
  min-width: 20px; height: 20px; padding: 0 6px; border-radius: 999px;
  background: #a3342a; color: #fdf6f2; font-size: 11.5px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
}

.side-foot { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
.toggle-row { display: flex; gap: 6px; }
.mini {
  height: 28px; padding: 0 11px; border-radius: 999px; font-size: 12px;
  border: 1px solid rgba(246, 242, 233, 0.22); background: transparent; color: #c4d5ce;
}
.mini:hover { background: rgba(246, 242, 233, 0.10); }
.mini.on { background: #f6f2e9; color: #10312a; border-color: transparent; font-weight: 600; }
.mini.wide { width: 100%; }

.who { display: flex; align-items: center; gap: 10px; padding: 12px 4px 0; border-top: 1px solid rgba(246, 242, 233, 0.13); }
.avatar {
  width: 32px; height: 32px; border-radius: 999px;
  background: #1b4a3f; color: #f6f2e9; display: grid; place-items: center; font-size: 13px;
}
.who-name { font-size: 13px; color: #f2ede1; }
.who-role { font-size: 11.5px; color: #9fb6ad; }

.main { min-width: 0; background: var(--paper); }

@media (max-width: 860px) {
  .shell { grid-template-columns: 64px minmax(0, 1fr); }
  .brand-text, .nav-item .label, .who-text, .toggle-row, .mini.wide { display: none; }
  .brand { justify-content: center; }
  .nav-item { justify-content: center; padding: 0; }
}
</style>