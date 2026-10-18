<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NEmpty, NInput, NModal, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import { ttsApi } from '@/api'
import type { TtsEngine } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { t } from '@/i18n'

const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const engines = ref<TtsEngine[]>([])
const loading = ref(false)
const showImport = ref(false)
const importText = ref('')
const importing = ref(false)
const testing = ref<number | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

async function load() {
  loading.value = true
  try {
    engines.value = await ttsApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function doImport() {
  if (!importText.value.trim()) return
  importing.value = true
  try {
    const r = await ttsApi.import(importText.value)
    message.success(t('已导入：新增 {0} 个{1}', [r.added, r.updated ? t('，更新 {0} 个', [r.updated]) : '']))
    showImport.value = false
    importText.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    importing.value = false
  }
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  input.value = ''
  if (!f) return
  importText.value = await f.text()
}

async function test(e: TtsEngine) {
  testing.value = e.id
  try {
    const blob = await ttsApi.speak(e.id, t('这是一段朗读测试。'), 10)
    const url = URL.createObjectURL(blob)
    const audio = new Audio(url)
    audio.onended = () => URL.revokeObjectURL(url)
    await audio.play()
  } catch (err: any) {
    message.error(err.message)
  } finally {
    testing.value = null
  }
}

function remove(e: TtsEngine) {
  dialog.warning({
    title: t('删除朗读引擎'),
    content: t('确定删除「{0}」吗？', [e.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await ttsApi.remove(e.id)
      engines.value = engines.value.filter((x) => x.id !== e.id)
    },
  })
}

async function exportAll() {
  const data = await ttsApi.export()
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'httpTTS.json'
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
</script>

<template>
  <div>
    <p class="muted small" style="margin-top: 0">
      {{ t('阅读时点击屏幕中间 → 朗读。默认使用浏览器自带的语音；也可以导入阅读 App 的在线朗读引擎（App 中“朗读 → 朗读引擎”导出的 JSON）。 在线引擎的规则在服务器上执行，') }}<template v-if="!auth.me?.canAddSource">{{ t('与添加书源的权限相同，你暂时不能导入，只能使用管理员提供的公共引擎。') }}</template><template v-else>{{ t('管理员导入的引擎所有用户都可以使用。') }}</template>
    </p>
    <n-space v-if="auth.me?.canAddSource" style="margin-bottom: 10px">
      <n-button size="small" type="primary" @click="showImport = true">{{ t('导入') }}</n-button>
      <n-button v-if="engines.some((e) => e.owned)" size="small" @click="exportAll">{{ t('导出') }}</n-button>
    </n-space>
    <n-empty v-if="!loading && !engines.length" :description="t('还没有在线朗读引擎')" size="small" style="padding: 12px 0" />
    <div v-for="e in engines" :key="e.id" class="engine">
      <div class="engine-body">
        <div class="engine-name">
          <span class="ellipsis">{{ e.name }}</span>
          <n-tag v-if="!e.owned" size="small" :bordered="false">{{ t('公共') }}</n-tag>
          <n-tag v-if="e.hasLogin" size="small" :bordered="false" type="warning">{{ t('需要登录') }}</n-tag>
        </div>
        <div class="muted small ellipsis mono">{{ e.url }}</div>
      </div>
      <n-button size="tiny" :loading="testing === e.id" @click="test(e)">{{ t('试听') }}</n-button>
      <n-button v-if="e.owned" size="tiny" type="error" ghost @click="remove(e)">{{ t('删除') }}</n-button>
    </div>

    <n-modal v-model:show="showImport" preset="card" :title="t('导入在线朗读引擎')" style="max-width: 560px">
      <n-input
        v-model:value="importText"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 16 }"
        :placeholder="t('粘贴 JSON，例如 [{\u0022name\u0022:\u0022…\u0022,\u0022url\u0022:\u0022https://…{{speakText}}…\u0022}]')"
        class="mono"
      />
      <p class="muted small">
        {{ t('url 中可以使用') }} <code v-pre>{{speakText}}</code>{{ t('（要朗读的文字）和') }} <code v-pre>{{speakSpeed}}</code>{{ t('（语速，10 为正常，与 App 相同）。同名的引擎会被覆盖。') }}
      </p>
      <template #footer>
        <n-space justify="space-between">
          <n-button size="small" @click="fileInput?.click()">{{ t('从文件读取') }}</n-button>
          <n-space>
            <n-button size="small" @click="showImport = false">{{ t('取消') }}</n-button>
            <n-button size="small" type="primary" :loading="importing" @click="doImport">{{ t('导入') }}</n-button>
          </n-space>
        </n-space>
      </template>
      <input ref="fileInput" type="file" accept=".json,.txt,application/json" hidden @change="onFile" />
    </n-modal>
  </div>
</template>

<style scoped>
.engine {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--app-border);
}

.engine-body {
  flex: 1;
  min-width: 0;
}

.engine-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.small {
  font-size: 12.5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
