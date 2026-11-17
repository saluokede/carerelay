import { onMounted, ref, watch } from 'vue'

/**
 * 适老化开关（大字模式 / 高对比）。
 * 用模块级单例，公开端与管理端两个布局共享同一状态，写 localStorage 后刷新仍保留。
 */

const LARGE_TEXT_KEY = 'cr.largeText'
const CONTRAST_KEY = 'cr.highContrast'

export const largeText = ref(false)
export const highContrast = ref(false)

let initialized = false

function apply(): void {
  document.documentElement.dataset.largeText = String(largeText.value)
  document.documentElement.dataset.highContrast = String(highContrast.value)
}

function init(): void {
  if (initialized) {
    return
  }
  initialized = true
  largeText.value = localStorage.getItem(LARGE_TEXT_KEY) === '1'
  highContrast.value = localStorage.getItem(CONTRAST_KEY) === '1'
  apply()
  watch([largeText, highContrast], () => {
    localStorage.setItem(LARGE_TEXT_KEY, largeText.value ? '1' : '0')
    localStorage.setItem(CONTRAST_KEY, highContrast.value ? '1' : '0')
    apply()
  })
}

export function useAccessibility() {
  onMounted(init)
  return {
    largeText,
    highContrast,
    toggleLargeText: () => {
      largeText.value = !largeText.value
    },
    toggleContrast: () => {
      highContrast.value = !highContrast.value
    },
  }
}
