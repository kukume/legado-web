<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  NButton, NCollapse, NCollapseItem, NEmpty, NForm, NFormItem, NInput, NModal, NSpace, NSwitch, NTag, useDialog, useMessage,
} from 'naive-ui'
import { ttsApi } from '@/api'
import type { TtsEngine, TtsEngineConfig } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/clipboard'
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

async function play(blob: Blob) {
  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  audio.onended = () => URL.revokeObjectURL(url)
  await audio.play()
}

async function test(e: TtsEngine) {
  testing.value = e.id
  try {
    await play(await ttsApi.speak(e.id, t('这是一段朗读测试。'), 10))
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

// ---------------- 编辑（与阅读 App 的朗读引擎编辑页相同的字段） ----------------

const showEdit = ref(false)
const form = ref<TtsEngineConfig>({ name: '', url: '' })
const saving = ref(false)
const testingForm = ref(false)
const testText = ref('')

const blank = (): TtsEngineConfig => ({
  name: '', url: '', contentType: '', concurrentRate: '0', header: '', loginUrl: '', loginUi: '', loginCheckJs: '', jsLib: '', enabledCookieJar: false,
})

function create() {
  testText.value = t('这是一段朗读测试。')
  form.value = blank()
  showEdit.value = true
}

async function edit(e: TtsEngine) {
  try {
    const full = await ttsApi.get(e.id)
    testText.value = t('这是一段朗读测试。')
    form.value = { ...blank(), ...full }
    showEdit.value = true
  } catch (err: any) {
    message.error(err.message)
  }
}

/** 空字符串按 App 的习惯保存为 null */
function cleaned(e: TtsEngineConfig): TtsEngineConfig {
  const r: Record<string, unknown> = { ...e }
  for (const k of Object.keys(r)) if (r[k] === '') r[k] = null
  return r as unknown as TtsEngineConfig
}

async function save() {
  saving.value = true
  try {
    await ttsApi.save(cleaned(form.value))
    showEdit.value = false
    message.success(t('已保存'))
    await load()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function testForm() {
  testingForm.value = true
  try {
    await play(await ttsApi.test(cleaned(form.value), testText.value || t('这是一段朗读测试。')))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    testingForm.value = false
  }
}

async function copyJson() {
  const { id: _id, ...rest } = cleaned(form.value)
  await copyText(JSON.stringify(rest, null, 2))
  message.success(t('已复制'))
}
</script>

<template>
  <div>
    <p class="muted small" style="margin-top: 0">
      {{ t('除了浏览器自带的语音，还可以添加在线朗读引擎，或导入阅读 App 导出的朗读引擎。在线引擎的规则在服务器上执行，') }}<template v-if="!auth.me?.canAddSource">{{ t('与添加书源的权限相同，你暂时不能添加，只能使用管理员提供的公共引擎。') }}</template><template v-else>{{ t('管理员添加的引擎所有用户都可以使用。') }}</template>
    </p>
    <n-space v-if="auth.me?.canAddSource" style="margin-bottom: 10px">
      <n-button size="small" type="primary" @click="create">{{ t('新建') }}</n-button>
      <n-button size="small" @click="showImport = true">{{ t('导入') }}</n-button>
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
      <div class="engine-actions">
        <n-button size="tiny" :loading="testing === e.id" @click="test(e)">{{ t('试听') }}</n-button>
        <n-button v-if="e.owned" size="tiny" @click="edit(e)">{{ t('编辑') }}</n-button>
        <n-button v-if="e.owned" size="tiny" type="error" ghost @click="remove(e)">{{ t('删除') }}</n-button>
      </div>
    </div>

    <n-modal
      v-model:show="showEdit"
      preset="card"
      :title="form.id ? t('编辑朗读引擎') : t('新建朗读引擎')"
      style="width: 640px; max-width: 94vw"
      :auto-focus="false"
    >
      <n-form label-placement="top" class="tts-form">
        <n-form-item :label="t('名称')" required>
          <n-input v-model:value="form.name" />
        </n-form-item>
        <n-form-item label="url" required>
          <n-input
            v-model:value="form.url"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 10 }"
            class="mono"
            placeholder="https://example.com/tts?text={{java.encodeURI(speakText)}}&speed={{speakSpeed}}"
          />
        </n-form-item>
        <p class="muted small field-help">
          {{ t('与书源的地址规则相同，可以用 POST、请求头、js 等写法。') }}<code v-pre>{{speakText}}</code>{{ t('（要朗读的文字）和') }} <code v-pre>{{speakSpeed}}</code>{{ t('（语速，10 为正常，与 App 相同）') }}
        </p>
        <div class="form-row">
          <n-form-item label="Content-Type" style="flex: 1">
            <n-input v-model:value="form.contentType" class="mono" placeholder="audio/.*" />
          </n-form-item>
          <n-form-item :label="t('并发率')" style="flex: 1">
            <n-input v-model:value="form.concurrentRate" class="mono" :placeholder="t('0 不限制；1000 表示间隔 1000 毫秒；3/1000 表示 1000 毫秒内最多 3 次')" />
          </n-form-item>
        </div>
        <p class="muted small field-help">{{ t('Content-Type 是正则表达式：返回的类型不匹配时视为出错，把返回内容作为错误信息') }}</p>
        <n-form-item :label="t('请求头')">
          <n-input v-model:value="form.header" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" class="mono" placeholder='{"User-Agent": "…"}' />
        </n-form-item>

        <n-collapse :default-expanded-names="form.loginUrl || form.loginUi || form.loginCheckJs || form.jsLib ? ['more'] : []">
          <n-collapse-item :title="t('登录与 js')" name="more">
            <n-form-item :label="t('登录地址')">
              <n-input v-model:value="form.loginUrl" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono" />
            </n-form-item>
            <n-form-item :label="t('登录 UI')">
              <n-input v-model:value="form.loginUi" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono" />
            </n-form-item>
            <n-form-item :label="t('登录检查 JS')">
              <n-input v-model:value="form.loginCheckJs" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono" :placeholder="t('拿到响应后执行，result 为响应，返回新的响应（如登录失效时重新登录后再请求）')" />
            </n-form-item>
            <n-form-item label="jsLib">
              <n-input v-model:value="form.jsLib" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" class="mono" />
            </n-form-item>
            <n-form-item :label="t('启用 CookieJar')" label-placement="left">
              <n-switch :value="!!form.enabledCookieJar" @update:value="(v: boolean) => (form.enabledCookieJar = v)" />
            </n-form-item>
          </n-collapse-item>
        </n-collapse>

        <div class="test-row">
          <n-input v-model:value="testText" size="small" :placeholder="t('试听的文字')" />
          <n-button size="small" :loading="testingForm" :disabled="!form.url.trim()" @click="testForm">{{ t('试听') }}</n-button>
        </div>
      </n-form>
      <template #footer>
        <n-space justify="space-between">
          <n-button size="small" @click="copyJson">{{ t('复制 JSON') }}</n-button>
          <n-space>
            <n-button size="small" @click="showEdit = false">{{ t('取消') }}</n-button>
            <n-button size="small" type="primary" :loading="saving" @click="save">{{ t('保存') }}</n-button>
          </n-space>
        </n-space>
      </template>
    </n-modal>

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

.engine-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.field-help {
  margin: -16px 0 16px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.test-row {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.small {
  font-size: 12.5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 767px) {
  .form-row {
    flex-direction: column;
    gap: 0;
  }

  /* 按钮多时换到名称下面 */
  .engine {
    flex-wrap: wrap;
  }

  .engine-body {
    flex-basis: 100%;
  }

  .engine-actions {
    margin-left: auto;
  }
}
</style>
