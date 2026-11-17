<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { getToken } from '@/api'
import { useAccessibility } from '@/composables/useAccessibility'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const a11y = useAccessibility()

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}

onMounted(() => {
  if (!getToken()) {
    router.push({ name: 'login' })
  }
})
</script>

<template>
  <div class="family-shell">
    <header class="topbar">
      <div class="brand">
        <span class="logo">接</span>
        <div>
          <div class="name">医养接力站</div>
          <div class="small muted">家属端 · 回家照着做</div>
        </div>
      </div>
      <div class="tools">
        <button class="btn sm ghost" :class="{ on: a11y.largeText.value }" @click="a11y.toggleLargeText()">大字</button>
        <button class="btn sm ghost" :class="{ on: a11y.highContrast.value }" @click="a11y.toggleContrast()">高对比</button>
        <button class="btn sm ghost" @click="logout">退出</button>
      </div>
    </header>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.family-shell {
  min-height: 100vh;
  background:
    radial-gradient(760px 320px at 90% -10%, rgba(184, 84, 44, 0.10), transparent 65%),
    #fbf4e9;
}
.topbar {
  position: sticky; top: 0; z-index: 20;
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 18px;
  background: rgba(251, 244, 233, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid #eadbc8;
}
.brand { display: flex; align-items: center; gap: 10px; }
/* 家属端用陶土橙的圆标，和护士端的墨绿区分开 */
.logo {
  width: 34px; height: 34px; border-radius: 999px;
  display: grid; place-items: center; color: #fffaf1; font-weight: 700; font-size: 15px;
  background: #b8542c;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}
.name { font-size: 15px; font-weight: 650; letter-spacing: 0.02em; color: #4a3122; }
.tools { display: flex; gap: 6px; }
.tools .btn { border-color: #e6d4bf; color: #6b5546; background: #fffdf7; }
.tools .btn.on { background: #fbe9dc; color: #8f3a1c; border-color: #efcfba; }
.content { max-width: 680px; margin: 0 auto; padding: 20px 16px 64px; }
</style>