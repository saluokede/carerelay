import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import './styles/global.css'

/**
 * 刻意不引组件库：本项目的界面只有表格、卡片、输入框和按钮，
 * 全部用 styles/global.css 里的设计令牌手写，打包体积从 1.06 MB 降到百 KB 量级，
 * 也避免"模板感"——评审看到的是这个项目的界面，而不是某个组件库的默认皮肤。
 */
createApp(App).use(createPinia()).use(router).mount('#app')
