<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  getAiSettings,
  saveAiSettings,
  testAiSettings,
  type AiSettings,
  type AiTestResult,
} from '@/api'

/**
 * 系统设置 · AI 连接
 *
 * 设计取舍：Key 存库、不回显明文（只显示末四位），保存时留空表示"不改"。
 * 环境变量仍可作为兜底——页面上会明确告诉现在生效的是哪一处配置。
 */
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)

const baseUrl = ref('')
const model = ref('')
const timeoutSeconds = ref(30)
const apiKey = ref('')

const settings = ref<AiSettings | null>(null)
const notice = ref('')
const errorText = ref('')
const testResult = ref<AiTestResult | null>(null)

const configured = computed(() => settings.value?.configured === true)
const keySourceText = computed(() => {
  const src = settings.value?.keySource
  if (src === 'DATABASE') return '界面保存的 Key'
  if (src === 'ENV') return '环境变量 CR_AI_API_KEY'
  return '尚未配置'
})

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const s = await getAiSettings()
    settings.value = s
    baseUrl.value = s.baseUrl
    model.value = s.model
    timeoutSeconds.value = s.timeoutSeconds
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '读取设置失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  notice.value = ''
  errorText.value = ''
  try {
    const s = await saveAiSettings({
      baseUrl: baseUrl.value.trim() || undefined,
      model: model.value.trim() || undefined,
      timeoutSeconds: Number(timeoutSeconds.value) || undefined,
      apiKey: apiKey.value.trim() || undefined,
    })
    settings.value = s
    apiKey.value = ''
    notice.value = '已保存。下一次模型调用就会用新配置，不需要重启服务。'
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  testResult.value = null
  errorText.value = ''
  try {
    testResult.value = await testAiSettings({
      baseUrl: baseUrl.value.trim() || undefined,
      model: model.value.trim() || undefined,
      timeoutSeconds: Number(timeoutSeconds.value) || undefined,
      apiKey: apiKey.value.trim() || undefined,
    })
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '测试失败'
  } finally {
    testing.value = false
  }
}

async function clearKey() {
  if (!confirm('清空后模型调用会立刻降级到本地规则模式，确定清空已保存的 Key？')) return
  saving.value = true
  try {
    settings.value = await saveAiSettings({ clearKey: true })
    notice.value = '已清空 Key，系统回到本地规则模式。'
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '清空失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <div class="kicker">SETTINGS · 仅管理员</div>
        <h1>系统设置</h1>
        <p class="lead">
          这里填模型服务的地址、Key 和模型名。保存后立即生效，不用重启服务；留空则继续用环境变量里的配置。
        </p>
      </div>
      <div class="row">
        <button class="btn" :disabled="testing" @click="test">{{ testing ? '测试中…' : '测试连接' }}</button>
        <button class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存设置' }}</button>
      </div>
    </header>

    <p v-if="notice" class="note ok">{{ notice }}</p>
    <p v-if="errorText" class="note warn">{{ errorText }}</p>

    <p v-if="testResult" class="note" :class="testResult.ok ? 'ok' : 'warn'">
      <template v-if="testResult.ok">
        连接成功：模型 {{ testResult.model }}，{{ testResult.latencyMs }} ms，模型回了一个「{{ testResult.reply }}」。
      </template>
      <template v-else>
        连接失败：{{ testResult.message }}
      </template>
    </p>

    <section class="card">
      <div class="card-head">
        <div>
          <h3>模型连接</h3>
          <p class="muted">
            当前状态：
            <span class="pill" :class="configured ? 'good' : 'bad'">{{ configured ? '已配置' : '未配置（本地规则模式）' }}</span>
            生效来源：{{ keySourceText }}
            <template v-if="settings?.maskedKey">· Key {{ settings.maskedKey }}</template>
          </p>
        </div>
        <button v-if="configured" class="btn sm" :disabled="saving" @click="clearKey">清空 Key</button>
      </div>

      <div class="grid cols-2">
        <label class="field">
          <span>接口地址（OpenAI 兼容）</span>
          <input v-model="baseUrl" class="input" placeholder="https://api.deepseek.com/v1" :disabled="loading" />
          <small class="muted">官方地址一般是 <code>…/v1</code>，末尾不要带斜杠。</small>
        </label>

        <label class="field">
          <span>模型名</span>
          <input v-model="model" class="input" placeholder="deepseek-chat" :disabled="loading" />
          <small class="muted">换厂商时只改这里与地址，业务代码不用动。</small>
        </label>

        <label class="field">
          <span>API Key</span>
          <input
            v-model="apiKey"
            class="input"
            type="password"
            autocomplete="off"
            :placeholder="configured ? '已保存，留空表示不修改' : '粘贴 sk- 开头的 Key'"
            :disabled="loading"
          />
          <small class="muted">只保存在本机数据库，界面上只回显末四位。</small>
        </label>

        <label class="field">
          <span>超时（秒）</span>
          <input v-model.number="timeoutSeconds" class="input" type="number" min="5" max="120" :disabled="loading" />
          <small class="muted">长文抽取会慢一些，建议 30–60。</small>
        </label>
      </div>
    </section>

    <section class="card">
      <h3>没有 Key 的时候会怎样</h3>
      <ul class="plain">
        <li>出院小结不再由模型抽取，页面会提示"已切到本地模式"，护士可以手工填写结构化档案。</li>
        <li>打卡的文字描述走关键词判读（<code>RULE</code>），数值类本来就由代码算，不受影响。</li>
        <li>随访对话走六问脚本，周报走模板，四个环依然跑得通——这是刻意的降级设计。</li>
      </ul>
      <p class="muted" style="margin-top: 8px">
        想看每一次模型调用用了谁、花了多久、有没有被采纳，去数据库表 <code>ai_request</code>（<code>scene / mode / latency_ms / adopted</code>）。
      </p>
    </section>
  </div>
</template>
