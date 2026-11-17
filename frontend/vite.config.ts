import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // .tmp 里放截图/导出等临时产物，不参与热更新监听，避免文件被占用时 watch 崩溃
    watch: {
      ignored: ['**/.tmp/**', '**/dist/**'],
    },
    proxy: {
      // 开发期直连本地后端，避免跨域。
      // 本机 8080 被 DSH 自带浏览器调试端口占用，本地后端统一跑 8081（见 scripts/backend-run.ps1）
      '/api': {
        target: process.env.CR_API_TARGET ?? 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
