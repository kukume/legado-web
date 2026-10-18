<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  NAlert, NButton, NEmpty, NInput, NModal, NSelect, NSpace, NSpin, NSwitch, NTabs, NTabPane, useMessage,
} from 'naive-ui'
import { sourceApi } from '@/api'
import type { LoginConfig, LoginV2Render, RowUi } from '@/api/types'
import { useScriptEffects } from '@/utils/effects'
import CookieHelp from '@/components/CookieHelp.vue'
import { flexItemStyle } from '@/utils/flex'
import { t } from '@/i18n'

const props = defineProps<{ show: boolean; sourceId: number | null }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()
const message = useMessage()
const effects = useScriptEffects()

const loading = ref(false)
const saving = ref(false)
const config = ref<LoginConfig | null>(null)
/** 表单值：v1 以 name 为键，v2 以 key 为键 */
const form = reactive<Record<string, string>>({})
const variable = ref('')
const tab = ref('login')
const actionResult = ref('')
const running = ref<string | null>(null)

// v2 状态
const v2 = ref<LoginV2Render | null>(null)
const v2Errors = ref<Record<string, string>>({})
const countdowns = reactive<Record<string, number>>({})
let timer: ReturnType<typeof setInterval> | null = null

function resetForm() {
  Object.keys(form).forEach((k) => delete form[k])
}

async function load() {
  if (!props.sourceId) return
  loading.value = true
  config.value = null
  v2.value = null
  actionResult.value = ''
  effects.openLinks.value = []
  resetForm()
  try {
    const c = await sourceApi.loginConfig(props.sourceId)
    config.value = c
    variable.value = c.variable
    cookieInfo.value = null
    cookieUrl.value = ''
    cookieText.value = ''
    if (c.mode === 'v1') fillV1(c.loginUi, c.loginInfo)
    if (c.mode === 'v2') await renderV2(null)
    tab.value = c.mode === 'none' ? 'variable' : 'login'
    // 登录地址是网址（没有登录按钮）：显示已保存的 Cookie
    if (c.loginUrl && !c.loginUi.length) loadCookie()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(() => [props.show, props.sourceId], () => props.show && load())

onBeforeUnmount(() => timer && clearInterval(timer))

// ---------------- v1 ----------------

function chars(row: RowUi): string[] {
  return (row.chars || []).filter((c): c is string => c != null)
}

function fillV1(rows: RowUi[], stored: Record<string, string>) {
  fillingForm = true
  setTimeout(() => (fillingForm = false))
  for (const row of rows) {
    if (row.type === 'button' || row.type === 'label') continue
    const fallback = row.type === 'select' || row.type === 'toggle' ? chars(row)[0] ?? '' : ''
    form[row.name] = stored[row.name] ?? row.default ?? fallback
  }
}

function label(row: RowUi) {
  const vn = row.viewName
  if (vn && vn.length >= 3 && vn.startsWith("'") && vn.endsWith("'")) return vn.slice(1, -1)
  return row.name
}

/** 按 App 的 FlexboxLayout 规则排版：输入框、下拉框默认独占一行，按钮默认按文字宽度 */
function rowStyle(row: RowUi) {
  return flexItemStyle(row.style, row.type === "text" || row.type === "password" || row.type === "select")
}

function v1Data(): Record<string, string> {
  const rows = config.value?.loginUi || []
  const data: Record<string, string> = {}
  rows.forEach((r) => {
    if (r.type !== 'button' && r.type !== 'label') data[r.name] = form[r.name] ?? r.default ?? ''
  })
  return data
}

/** 处理 v1 脚本返回的界面副作用 */
async function applyV1(r: any) {
  await effects.apply(r)
  if (r.uiData) {
    fillingForm = true
    setTimeout(() => (fillingForm = false))
    Object.entries(r.uiData as Record<string, string | null>).forEach(([k, v]) => (form[k] = v ?? ''))
  }
  if (r.events?.includes('reLoginView')) {
    const keep = { ...form }
    await load()
    Object.assign(form, keep)
  }
}

async function doLogin() {
  if (!props.sourceId) return
  saving.value = true
  try {
    const r = await sourceApi.login(props.sourceId, v1Data())
    await applyV1(r)
    r.ok ? message.success(r.message || t('已保存')) : message.error(r.message || t('登录失败'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function runV1Action(row: RowUi, isLongClick = false) {
  if (!props.sourceId || !row.action) return
  running.value = row.name
  try {
    const r = await sourceApi.action(props.sourceId, row.action, v1Data(), isLongClick)
    await applyV1(r)
    if (!r.ok) return message.error(r.message || t('执行失败'))
    if (r.result && /^https?:\/\//.test(r.result) && !r.openUrls?.includes(r.result)) {
      window.open(r.result, '_blank', 'noopener')
    } else if (r.result) {
      actionResult.value = r.result
    }
  } catch (e: any) {
    message.error(e.message)
  } finally {
    running.value = null
  }
}

function toggleV1(row: RowUi) {
  const list = chars(row).length ? chars(row) : ['false', 'true']
  const cur = form[row.name] ?? list[0]
  form[row.name] = list[(list.indexOf(cur) + 1) % list.length]
  if (row.action) runV1Action(row)
}

// 长按按钮（与 App 一致：按住超过 666ms 视为长按）
let pressAt = 0
function onPress() {
  pressAt = Date.now()
}
function onRelease(row: RowUi) {
  runV1Action(row, Date.now() - pressAt > 666)
}

// ---------------- v2 ----------------

async function renderV2(state: string | null) {
  if (!props.sourceId) return
  const r = await sourceApi.loginV2Render(props.sourceId, state)
  applyV2Render(r)
}

function applyV2Render(r: LoginV2Render) {
  v2.value = r
  for (const row of r.rows) {
    if (!row.key) continue
    // 与 App 一致：渲染值 > 本次输入 > 已保存值
    const value = row.value ?? form[row.key] ?? r.stored[row.key]
    const fallback = row.type === 'select' ? row.options?.[0] ?? '' : row.type === 'toggle' ? 'false' : ''
    form[row.key] = value ?? fallback
  }
}

function v2Form(): Record<string, string> {
  const data: Record<string, string> = {}
  v2.value?.rows.forEach((r) => {
    if (r.key) data[r.key] = form[r.key] ?? ''
  })
  return data
}

async function dispatchV2(row: RowUi) {
  if (!props.sourceId || !row.action || running.value || (countdowns[row.action] ?? 0) > 0) return
  running.value = row.action
  v2Errors.value = {}
  try {
    const r = await sourceApi.loginV2Action(props.sourceId, row.action, v2.value?.state ?? null, v2Form())
    await effects.apply(r.effects)
    if (!r.ok) return message.error(r.message || t('执行失败'))
    v2Errors.value = r.errors || {}
    if (r.close) {
      message.success(t('完成'))
      emit('update:show', false)
      return
    }
    if (!Object.keys(v2Errors.value).length && row.countdown && row.countdown > 0) {
      startCountdown(row.action, row.countdown)
    }
    if (r.render) applyV2Render(r.render)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    running.value = null
  }
}

function toggleV2(row: RowUi, v: boolean) {
  if (!row.key) return
  form[row.key] = String(v)
  if (row.action) dispatchV2(row)
}

function startCountdown(action: string, seconds: number) {
  countdowns[action] = seconds
  if (timer) return
  timer = setInterval(() => {
    let active = false
    for (const k of Object.keys(countdowns)) {
      if (countdowns[k] > 0) {
        countdowns[k]--
        active = true
      }
    }
    if (!active && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

// ---------------- 通用 ----------------

/**
 * 只有输入框、没有任何按钮，但书源有 login 函数时才显示“登录”按钮（否则无法执行登录）；
 * 其余情况下填写的内容会自动保存，由书源自己的按钮使用
 */
const showLoginButton = computed(() => {
  const c = config.value
  if (!c || c.mode !== 'v1' || !c.hasLoginUrl) return false
  const rows = c.loginUi
  return !rows.some((r) => r.type === 'button') && rows.some((r) => r.type === 'text' || r.type === 'password')
})

// v1 表单修改后自动保存（加载表单、脚本回填时不保存）
let fillingForm = false
let saveTimer: ReturnType<typeof setTimeout> | null = null
watch(form, () => {
  if (fillingForm || loading.value || config.value?.mode !== 'v1' || !props.sourceId) return
  if (saveTimer) clearTimeout(saveTimer)
  const id = props.sourceId
  saveTimer = setTimeout(() => {
    sourceApi.saveLoginInfo(id, v1Data()).catch((e: any) => message.error(e.message))
  }, 600)
})

// ---------------- Cookie ----------------

const cookieUrl = ref('')
const cookieText = ref('')
const cookieInfo = ref<{ url: string; domain: string; cookie: string } | null>(null)
const cookieSaving = ref(false)

async function loadCookie() {
  if (props.sourceId == null) return
  try {
    cookieInfo.value = await sourceApi.getCookie(props.sourceId, cookieUrl.value || undefined)
    if (!cookieUrl.value) cookieUrl.value = cookieInfo.value.url
  } catch (e: any) {
    message.error(e.message)
  }
}


async function saveCookie() {
  if (props.sourceId == null) return
  if (!cookieText.value.trim()) return message.warning(t('请先粘贴 Cookie'))
  cookieSaving.value = true
  try {
    cookieInfo.value = await sourceApi.setCookie(props.sourceId, cookieText.value, cookieUrl.value || undefined)
    cookieText.value = ''
    message.success(t('已保存到 {0}', [cookieInfo.value.domain]))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    cookieSaving.value = false
  }
}

async function saveVariable() {
  if (!props.sourceId) return
  await sourceApi.setVariable(props.sourceId, variable.value)
  message.success(t('变量已保存'))
}

</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="config ? t('书源设置 · {0}', [config.sourceName]) : t('书源设置')"
    style="max-width: 600px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-spin :show="loading">

      <div v-if="effects.openLinks.value.length" class="links">
        <div class="muted">{{ t('书源请求打开以下网页（若未自动弹出请点击）：') }}</div>
        <a v-for="u in effects.openLinks.value" :key="u" :href="u" target="_blank" rel="noopener" class="ellipsis">{{ u }}</a>
      </div>

      <n-tabs v-if="config" v-model:value="tab" type="line">
        <n-tab-pane name="login" :tab="t('登录 / 配置')">
          <n-empty v-if="config.mode === 'none'" :description="t('该书源没有登录配置')" />

          <!-- v1：静态 JSON 或脚本生成 -->
          <template v-else-if="config.mode === 'v1'">
            <n-alert v-if="config.renderError" type="error" :bordered="false" style="margin-bottom: 12px">
              {{ t('登录界面脚本出错：{0}', [config.renderError]) }}
            </n-alert>
            <!-- loginUrl 为网址：没有登录按钮，只能在网页中登录，由用户粘贴 Cookie -->
            <div v-if="config.loginUrl && !config.loginUi.length" class="web-login">
              <p class="web-login-tip">
                {{ t('此书源需要在网页中登录。网页版读不到你浏览器里的 Cookie，请在新标签页登录后，把该网站的 Cookie 粘贴到下方。') }}
              </p>
              <div class="saved-row">
                <n-button type="primary" size="small" tag="a" :href="config.loginUrl" target="_blank" rel="noopener">{{ t('在新标签页打开登录页') }}</n-button>
                <!-- Cookie 很长时省略（不撑出弹窗），完整内容悬停查看 -->
                <span class="muted small-note saved" :title="cookieInfo?.cookie || ''">
                  {{ t('已保存：') }}<span class="mono">{{ cookieInfo?.cookie || t('无') }}</span>
                </span>
              </div>
              <n-input
                v-model:value="cookieText"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                :placeholder="t('{0} 的 Cookie，如 sessionid=xxx; uid=yyy', [cookieInfo?.domain || t('该网站')])"
                class="mono"
              />
              <cookie-help :site="cookieInfo?.domain" />
              <n-space justify="end" style="margin-top: 8px">
                <n-button size="small" type="primary" :loading="cookieSaving" @click="saveCookie">{{ t('保存 Cookie') }}</n-button>
              </n-space>
            </div>
            <div class="rows">
              <template v-for="row in config.loginUi" :key="row.name">
                <div v-if="row.style?.layout_wrapBefore" class="flex-break" />
                <div v-if="row.type === 'text' || row.type === 'password'" class="row field" :style="rowStyle(row)">
                  <label>{{ label(row) }}</label>
                  <n-input
                    v-model:value="form[row.name]"
                    :type="row.type === 'password' ? 'password' : 'text'"
                    show-password-on="click"
                    size="small"
                  />
                </div>
                <div v-else-if="row.type === 'select'" class="row field" :style="rowStyle(row)">
                  <label>{{ label(row) }}</label>
                  <n-select
                    v-model:value="form[row.name]"
                    size="small"
                    :options="chars(row).map((c) => ({ label: c, value: c }))"
                    @update:value="() => row.action && runV1Action(row)"
                  />
                </div>
                <n-button v-else-if="row.type === 'toggle'" class="row" :style="rowStyle(row)" size="small" @click="toggleV1(row)">
                  {{ label(row) }}：{{ form[row.name] }}
                </n-button>
                <div v-else-if="row.type === 'label'" class="row label" :style="rowStyle(row)">{{ label(row) }}</div>
                <n-button
                  v-else-if="row.type === 'button'"
                  class="row"
                  :style="rowStyle(row)"
                  size="small"
                  :loading="running === row.name"
                  @mousedown="onPress"
                  @touchstart.passive="onPress"
                  @mouseup="onRelease(row)"
                  @touchend.prevent="onRelease(row)"
                >{{ label(row) }}</n-button>
              </template>
            </div>
            <pre v-if="actionResult" class="result mono">{{ actionResult }}</pre>
            <n-space v-if="showLoginButton" justify="end" style="margin-top: 12px">
              <n-button type="primary" :loading="saving" @click="doLogin">{{ t('登录') }}</n-button>
            </n-space>
          </template>

          <!-- v2：由书源脚本驱动 -->
          <template v-else-if="config.mode === 'v2'">
            <n-empty v-if="!v2" :description="t('登录界面加载失败')" />
            <div v-else class="rows">
              <template v-for="(row, i) in v2.rows" :key="row.key || row.action || i">
                <div v-if="row.type === 'text' || row.type === 'password'" class="row field full">
                  <label>{{ row.name }}</label>
                  <n-input
                    v-model:value="form[row.key!]"
                    :type="row.type === 'password' ? 'password' : 'text'"
                    :placeholder="row.hint || ''"
                    show-password-on="click"
                    size="small"
                    :status="v2Errors[row.key!] ? 'error' : undefined"
                  />
                  <span v-if="v2Errors[row.key!]" class="err">{{ v2Errors[row.key!] }}</span>
                </div>
                <div v-else-if="row.type === 'select'" class="row field full">
                  <label>{{ row.name }}</label>
                  <n-select
                    v-model:value="form[row.key!]"
                    size="small"
                    :options="(row.options || []).map((o) => ({ label: o, value: o }))"
                  />
                  <span v-if="v2Errors[row.key!]" class="err">{{ v2Errors[row.key!] }}</span>
                </div>
                <div v-else-if="row.type === 'toggle'" class="row field full inline">
                  <label>{{ row.name }}</label>
                  <n-switch :value="form[row.key!] === 'true'" @update:value="(v: boolean) => toggleV2(row, v)" />
                </div>
                <div v-else-if="row.type === 'label'" class="row label full">{{ row.name }}</div>
                <n-button
                  v-else-if="row.type === 'button'"
                  class="row full"
                  size="small"
                  :loading="running === row.action"
                  :disabled="(countdowns[row.action!] ?? 0) > 0"
                  @click="dispatchV2(row)"
                >
                  {{ (countdowns[row.action!] ?? 0) > 0 ? `${row.name}（${countdowns[row.action!]}s）` : row.name }}
                </n-button>
              </template>
            </div>
          </template>
        </n-tab-pane>

        <n-tab-pane name="variable" :tab="t('书源变量')">
          <p v-if="config.variableComment" class="muted comment">{{ config.variableComment }}</p>
          <n-input
            v-model:value="variable"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 12 }"
            :placeholder="t('source.getVariable() 读取的内容')"
            class="mono"
          />
          <n-space justify="end" style="margin-top: 12px">
            <n-button type="primary" @click="saveVariable">{{ t('保存变量') }}</n-button>
          </n-space>
        </n-tab-pane>
      </n-tabs>
    </n-spin>
  </n-modal>
</template>

<style scoped>
.web-login {
  margin-bottom: 12px;
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(240, 160, 32, 0.08);
}

.web-login-tip {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.6;
}

.small-note {
  font-size: 12px;
  margin-top: 8px;
  word-break: break-all;
}

.rows {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.row {
  min-width: 0;
}

/* layout_wrapBefore：在下一个控件前换行 */
.flex-break {
  flex: 0 0 100%;
  height: 0;
  margin-top: -8px;
}

.full {
  flex: 0 0 100%;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field.inline {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}

.field label {
  font-size: 13px;
  color: var(--app-text-2);
}

.label {
  font-size: 13px;
  color: var(--app-text-2);
  padding: 4px 0;
}

.err {
  font-size: 12px;
  color: #d03050;
}

.result {
  background: rgba(128, 128, 128, 0.1);
  padding: 8px;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  max-height: 200px;
  overflow: auto;
  margin-top: 10px;
}

.comment {
  white-space: pre-wrap;
  font-size: 13px;
}

.links {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  margin-bottom: 12px;
}

.links a {
  color: var(--app-accent);
}
.saved-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-bottom: 8px;
}

.saved-row .saved {
  flex: 1 1 200px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
