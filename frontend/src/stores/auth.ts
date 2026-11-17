import { defineStore } from 'pinia'

import { authLogin, authMe, getToken, setToken, type UserView } from '@/api'

/**
 * 登录态：token 落 localStorage，刷新后用 /auth/me 回填用户信息。
 * 演示环境不开放注册，四类账号来自后端种子数据。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as UserView | null,
    restoring: false,
  }),

  getters: {
    isLoggedIn: (state): boolean => Boolean(state.user) || Boolean(getToken()),
    isNurse: (state): boolean => state.user?.role === 'ADMIN' || state.user?.role === 'NURSE',
    isDoctor: (state): boolean => state.user?.role === 'DOCTOR',
    isFamily: (state): boolean => state.user?.role === 'FAMILY',
    isAdmin: (state): boolean => state.user?.role === 'ADMIN',
    displayName: (state): string => state.user?.nickname || state.user?.phone || '未登录',
  },

  actions: {
    async login(phone: string, password: string) {
      const resp = await authLogin({ phone, password })
      setToken(resp.token)
      this.user = resp.user
      return resp.user
    },

    /** 页面刷新后回填身份；token 失效则清掉 */
    async restore() {
      if (!getToken() || this.user) {
        return
      }
      this.restoring = true
      try {
        this.user = await authMe()
      } catch {
        setToken(null)
        this.user = null
      } finally {
        this.restoring = false
      }
    },

    logout() {
      setToken(null)
      this.user = null
    },
  },
})
