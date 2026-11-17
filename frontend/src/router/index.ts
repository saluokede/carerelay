import { createRouter, createWebHistory } from 'vue-router'

import { getToken } from '@/api'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由按"环"组织，护士台是入口：
 * 工作台 → 老人详情（档案/评分卡/计划/打卡/随访/报告都在这一页的标签下）。
 * 家属端独立于护士端，用 /family 进入。
 */
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录 · 医养接力站' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'workbench',
          component: () => import('@/views/WorkbenchView.vue'),
          meta: { title: '护士工作台 · 医养接力站' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '系统设置 · 医养接力站', requiresAdmin: true },
        },
        {
          path: 'elders/new',
          name: 'elder-new',
          component: () => import('@/views/DischargeImportView.vue'),
          meta: { title: '新接收 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)',
          name: 'elder-detail',
          component: () => import('@/views/ElderDetailView.vue'),
          meta: { title: '老人详情 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)/import',
          name: 'discharge-import',
          component: () => import('@/views/DischargeImportView.vue'),
          meta: { title: '接收出院 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)/plan/:planId(\\d+)',
          name: 'plan-review',
          component: () => import('@/views/PlanReviewView.vue'),
          meta: { title: '计划签发 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)/today',
          name: 'today',
          component: () => import('@/views/TodayView.vue'),
          meta: { title: '今日任务 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)/followup',
          name: 'followup',
          component: () => import('@/views/FollowupView.vue'),
          meta: { title: '随访 · 医养接力站' },
        },
        {
          path: 'elders/:id(\\d+)/report',
          name: 'report',
          component: () => import('@/views/ReportView.vue'),
          meta: { title: '复盘 · 医养接力站' },
        },
        {
          path: 'ai-records',
          name: 'ai-records',
          component: () => import('@/views/AiRecordsView.vue'),
          meta: { title: 'AI 调用记录 · 医养接力站', requiresAdmin: true },
        },
        {
          path: 'alerts',
          name: 'alerts',
          component: () => import('@/views/AlertsView.vue'),
          meta: { title: '预警与工单 · 医养接力站' },
        },
      ],
    },
    {
      // 交接单是"打印件"，不带侧栏与导航，独立于 AppLayout
      path: '/elders/:id(\\d+)/print',
      name: 'elder-print',
      component: () => import('@/views/ElderPrintView.vue'),
      meta: { title: '照护交接单 · 医养接力站', requiresAuth: true },
    },
    {
      path: '/family',
      component: () => import('@/layouts/FamilyLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'family-home',
          component: () => import('@/views/FamilyView.vue'),
          meta: { title: '家里今天 · 医养接力站' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAuth !== true) {
    return true
  }
  if (!getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const auth = useAuthStore()
  if (!auth.user && !auth.restoring) {
    await auth.restore()
  }
  if (!auth.user) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 家属账号默认进家属端；护士/医生账号不应该停留在家属端
  if (auth.isFamily && to.name !== 'family-home' && !to.path.startsWith('/family')) {
    return { name: 'family-home' }
  }
  if (!auth.isFamily && to.path.startsWith('/family')) {
    return { name: 'workbench' }
  }
  // 系统设置只有管理员能进，别让护士在地址栏里试出来
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'workbench' }
  }
  return true
})

router.afterEach((to) => {
  if (to.meta.title) {
    document.title = String(to.meta.title)
  }
})

export default router
