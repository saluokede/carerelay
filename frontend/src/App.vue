<script setup lang="ts">
import { useAccessibility } from '@/composables/useAccessibility'

// 适老化开关（大字 / 高对比）在这里初始化一次，任何入口都生效。
useAccessibility()
</script>

<template>
  <router-view />
</template>
