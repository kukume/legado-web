<script setup lang="ts">
import { goBack } from '@/utils/navigation'
import { useIsMobile } from '@/composables/useIsMobile'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NCheckbox, NIcon, NInput, NInputNumber, NRadioButton, NRadioGroup, NSelect, NSpace, NSwitch, NTabs, NTabPane, NTag, NTooltip,
  useMessage,
} from 'naive-ui'
import { ArrowBackOutline, PlayOutline, StopOutline, SaveOutline, CopyOutline } from '@vicons/ionicons5'
import { sourceApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/media'
import { t } from '@/i18n'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const id = computed(() => (route.params.id ? Number(route.params.id) : null))
const src = ref<Record<string, any>>(emptySource())
const isPublic = ref(false)
const canEdit = ref(true)
const saving = ref(false)
const tab = ref('base')
/** 手机上编辑和调试分开显示（屏幕太窄放不下左右两栏） */
const isMobile = useIsMobile()
const mobilePane = ref<'edit' | 'debug'>('edit')
const jsonText = ref('')
const jsonError = ref('')

function emptySource() {
  return {
    bookSourceUrl: '',
    bookSourceName: '',
    bookSourceGroup: '',
    bookSourceType: 0,
    enabled: true,
    enabledExplore: true,
    enabledCookieJar: true,
    customOrder: 0,
    ruleSearch: {},
    ruleExplore: {},
    ruleBookInfo: {},
    ruleToc: {},
    ruleContent: {},
  } as Record<string, any>
}

interface Field {
  key: string
  label: string
  multiline?: boolean
  hint?: string
}

const groupsDef: { name: string; tab: string; obj?: string; fields: Field[] }[] = [
  {
    name: t('基本'),
    tab: 'base',
    fields: [
      { key: 'bookSourceUrl', label: t('源地址'), hint: t('唯一标识，通常为网站首页') },
      { key: 'bookSourceName', label: t('源名称') },
      { key: 'bookSourceGroup', label: t('分组'), hint: t('多个用逗号分隔') },
      { key: 'bookSourceComment', label: t('源注释'), multiline: true },
      { key: 'bookUrlPattern', label: t('详情页 URL 正则') },
      { key: 'header', label: t('请求头'), multiline: true, hint: t('JSON，或 @js: / <js></js>') },
      { key: 'concurrentRate', label: t('并发率'), hint: t('如 1000（毫秒间隔）或 5/1000（次数/毫秒）') },
      { key: 'jsLib', label: 'jsLib', multiline: true },
      { key: 'loginUrl', label: t('登录地址'), multiline: true, hint: t('网址，或包含 login() 函数的 js') },
      { key: 'loginUi', label: t('登录 UI'), multiline: true, hint: t('JSON 数组：[{\u0022name\u0022:\u0022账号\u0022,\u0022type\u0022:\u0022text\u0022}]') },
      { key: 'loginCheckJs', label: t('登录检测 js'), multiline: true },
      { key: 'coverDecodeJs', label: t('封面解密 js'), multiline: true },
      { key: 'variableComment', label: t('变量说明'), multiline: true },
    ],
  },
  {
    name: t('搜索'),
    tab: 'search',
    obj: 'ruleSearch',
    fields: [
      { key: '@searchUrl', label: t('搜索地址'), multiline: true, hint: t('{{key}} 关键字，{{page}} 页码') },
      { key: 'checkKeyWord', label: t('校验关键字') },
      { key: 'bookList', label: t('书籍列表') },
      { key: 'name', label: t('书名') },
      { key: 'author', label: t('作者') },
      { key: 'kind', label: t('分类') },
      { key: 'wordCount', label: t('字数') },
      { key: 'lastChapter', label: t('最新章节') },
      { key: 'intro', label: t('简介') },
      { key: 'coverUrl', label: t('封面') },
      { key: 'bookUrl', label: t('详情页 URL') },
    ],
  },
  {
    name: t('发现'),
    tab: 'explore',
    obj: 'ruleExplore',
    fields: [
      { key: '@exploreUrl', label: t('发现地址'), multiline: true, hint: t('名称::地址，每行一个；或 JSON / js') },
      { key: 'bookList', label: t('书籍列表') },
      { key: 'name', label: t('书名') },
      { key: 'author', label: t('作者') },
      { key: 'kind', label: t('分类') },
      { key: 'wordCount', label: t('字数') },
      { key: 'lastChapter', label: t('最新章节') },
      { key: 'intro', label: t('简介') },
      { key: 'coverUrl', label: t('封面') },
      { key: 'bookUrl', label: t('详情页 URL') },
    ],
  },
  {
    name: t('详情'),
    tab: 'info',
    obj: 'ruleBookInfo',
    fields: [
      { key: 'init', label: t('预处理规则') },
      { key: 'name', label: t('书名') },
      { key: 'author', label: t('作者') },
      { key: 'kind', label: t('分类') },
      { key: 'wordCount', label: t('字数') },
      { key: 'lastChapter', label: t('最新章节') },
      { key: 'intro', label: t('简介') },
      { key: 'coverUrl', label: t('封面') },
      { key: 'tocUrl', label: t('目录 URL') },
      { key: 'canReName', label: t('允许修改书名作者') },
      { key: 'downloadUrls', label: t('下载 URL（文件类）') },
    ],
  },
  {
    name: t('目录'),
    tab: 'toc',
    obj: 'ruleToc',
    fields: [
      { key: 'preUpdateJs', label: t('更新前 js'), multiline: true },
      { key: 'chapterList', label: t('目录列表'), hint: t('以 - 开头表示倒序') },
      { key: 'chapterName', label: t('章节名') },
      { key: 'chapterUrl', label: t('章节 URL') },
      { key: 'formatJs', label: t('格式化 js'), multiline: true },
      { key: 'isVolume', label: t('卷名标识') },
      { key: 'updateTime', label: t('章节信息') },
      { key: 'isVip', label: t('VIP 标识') },
      { key: 'isPay', label: t('购买标识') },
      { key: 'nextTocUrl', label: t('目录下一页') },
    ],
  },
  {
    name: t('正文'),
    tab: 'content',
    obj: 'ruleContent',
    fields: [
      { key: 'content', label: t('正文') },
      { key: 'title', label: t('章节名') },
      { key: 'nextContentUrl', label: t('正文下一页') },
      { key: 'webJs', label: 'WebView js', multiline: true },
      { key: 'sourceRegex', label: t('资源正则') },
      { key: 'replaceRegex', label: t('替换规则'), multiline: true },
      { key: 'imageStyle', label: t('图片样式'), hint: t('FULL 最大宽度') },
      { key: 'imageDecode', label: t('图片解密 js'), multiline: true },
      { key: 'payAction', label: t('购买操作'), multiline: true },
    ],
  },
]

function getVal(obj: string | undefined, key: string) {
  if (key.startsWith('@')) return src.value[key.slice(1)] ?? ''
  if (!obj) return src.value[key] ?? ''
  return src.value[obj]?.[key] ?? ''
}

function setVal(obj: string | undefined, key: string, v: string) {
  const val = v === '' ? null : v
  if (key.startsWith('@')) src.value[key.slice(1)] = val
  else if (!obj) src.value[key] = val
  else {
    if (!src.value[obj] || typeof src.value[obj] !== 'object') src.value[obj] = {}
    src.value[obj][key] = val
  }
}

function clean(o: any): any {
  if (Array.isArray(o)) return o.map(clean)
  if (o && typeof o === 'object') {
    const r: any = {}
    for (const [k, v] of Object.entries(o)) {
      if (v === null || v === undefined || v === '') continue
      const c = clean(v)
      if (c && typeof c === 'object' && !Array.isArray(c) && !Object.keys(c).length) continue
      r[k] = c
    }
    return r
  }
  return o
}

const sourceJson = () => JSON.stringify(clean(src.value), null, 2)

watch(tab, (t, old) => {
  if (t === 'json') {
    jsonText.value = sourceJson()
    jsonError.value = ''
  } else if (old === 'json') {
    applyJson()
  }
})

function applyJson(): boolean {
  try {
    const parsed = JSON.parse(jsonText.value)
    src.value = { ...emptySource(), ...(Array.isArray(parsed) ? parsed[0] : parsed) }
    jsonError.value = ''
    return true
  } catch (e: any) {
    jsonError.value = e.message
    return false
  }
}

onMounted(async () => {
  if (!id.value) {
    canEdit.value = auth.me?.canAddSource ?? false
    return
  }
  try {
    const d = await sourceApi.get(id.value)
    src.value = { ...emptySource(), ...d.source }
    isPublic.value = d.isPublic
    canEdit.value = d.canEdit
    debugKey.value = d.source?.ruleSearch?.checkKeyWord || '我的'
  } catch (e: any) {
    message.error(e.message)
  }
})

async function save() {
  if (tab.value === 'json' && !applyJson()) return message.error(t('JSON 格式错误'))
  if (!src.value.mainJs && (!src.value.bookSourceUrl || !src.value.bookSourceName)) return message.warning(t('源地址和源名称不能为空'))
  saving.value = true
  try {
    const body = sourceJson()
    if (id.value) {
      await sourceApi.save(id.value, body, auth.isAdmin ? isPublic.value : undefined)
      message.success(t('已保存'))
      // JS 书源的字段由服务端从脚本重新解析，保存后刷新
      if (src.value.mainJs) {
        const d = await sourceApi.get(id.value)
        src.value = { ...emptySource(), ...d.source }
      }
    } else {
      const r = await sourceApi.create(body, auth.isAdmin ? isPublic.value : undefined)
      message.success(t('已创建'))
      router.replace({ name: 'source-edit', params: { id: r.id } })
    }
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function copyJson() {
  if (tab.value === 'json' && !applyJson()) return
  ;(await copyText(sourceJson())) ? message.success(t('已复制')) : message.error(t('复制失败'))
}

// ---------------- 调试 ----------------
// 调试搜索的默认关键字（与 App 相同，是数据不翻译）
const debugKey = ref('我的')
const logs = ref<{ state: number; msg: string }[]>([])
const debugging = ref(false)
const showSource = ref(false)
const logBox = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

const htmlStates: Record<number, string> = { 10: t('搜索页源码'), 20: t('详情页源码'), 30: t('目录页源码'), 40: t('正文页源码') }

async function runDebug() {
  if (tab.value === 'json' && !applyJson()) return message.error(t('JSON 格式错误'))
  if (!debugKey.value.trim()) return message.warning(t('请输入调试关键字'))
  controller?.abort()
  controller = new AbortController()
  logs.value = []
  debugging.value = true
  // 可编辑时调试当前编辑中的内容，否则调试已保存的书源
  const body = canEdit.value ? { source: sourceJson(), key: debugKey.value } : { sourceId: id.value!, key: debugKey.value }
  try {
    await sourceApi.debug(
      body,
      (item) => {
        logs.value.push(item)
        nextTick(() => logBox.value && (logBox.value.scrollTop = logBox.value.scrollHeight))
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e.name !== 'AbortError') logs.value.push({ state: -1, msg: e.message })
  } finally {
    debugging.value = false
  }
}

function stopDebug() {
  controller?.abort()
  debugging.value = false
}

const debugHints = [
  { label: t('搜索'), key: 'search' },
  { label: t('详情页 URL'), key: 'http' },
  { label: t('发现 名称::URL'), key: 'explore' },
  { label: t('目录 ++URL'), key: '++' },
  { label: t('正文 --URL'), key: '--' },
]

const typeOptions = [
  { label: t('文本'), value: 0 },
  { label: t('音频'), value: 1 },
  { label: t('图片'), value: 2 },
  { label: t('文件'), value: 3 },
]
</script>

<template>
  <div class="page wide">
    <div class="page-header">
      <n-button quaternary circle @click="goBack(router, { name: 'sources' })">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <h2>{{ id ? (canEdit ? t('编辑书源') : t('查看书源')) : t('新建书源') }}<span class="muted name" :title="src.bookSourceName">{{ src.bookSourceName }}</span></h2>
      <n-checkbox v-if="auth.isAdmin && canEdit" v-model:checked="isPublic">{{ t('公共书源') }}</n-checkbox>
      <n-button size="small" @click="copyJson">
        <template #icon><n-icon :component="CopyOutline" /></template>{{ t('复制 JSON') }}
      </n-button>
      <n-button v-if="canEdit" type="primary" size="small" :loading="saving" @click="save">
        <template #icon><n-icon :component="SaveOutline" /></template>{{ t('保存') }}
      </n-button>
    </div>

    <n-radio-group v-if="isMobile" v-model:value="mobilePane" size="small" class="pane-switch">
      <n-radio-button value="edit">{{ t('编辑') }}</n-radio-button>
      <n-radio-button value="debug">{{ t('调试') }}<span v-if="debugging" class="dot" /></n-radio-button>
    </n-radio-group>

    <div class="editor">
      <div v-show="!isMobile || mobilePane === 'edit'" class="card form">
        <n-tabs v-model:value="tab" type="line" size="small">
          <n-tab-pane v-for="g in groupsDef" :key="g.tab" :name="g.tab" :tab="g.name">
            <div v-if="g.tab === 'base'" class="flags">
              <label>{{ t('类型') }} <n-select v-model:value="src.bookSourceType" :options="typeOptions" size="small" style="width: 90px" /></label>
              <label>{{ t('排序') }} <n-input-number v-model:value="src.customOrder" size="small" style="width: 90px" /></label>
              <label><n-switch v-model:value="src.enabled" size="small" /> {{ t('启用') }}</label>
              <label><n-switch v-model:value="src.enabledExplore" size="small" /> {{ t('发现') }}</label>
              <label><n-switch v-model:value="src.enabledCookieJar" size="small" /> CookieJar</label>
            </div>
            <div v-for="f in g.fields" :key="f.key" class="field">
              <label>
                {{ f.label }}
                <n-tooltip v-if="f.hint"><template #trigger><span class="hint">?</span></template>{{ f.hint }}</n-tooltip>
              </label>
              <n-input
                :value="getVal(g.obj, f.key)"
                :type="f.multiline ? 'textarea' : 'text'"
                :autosize="f.multiline ? { minRows: 1, maxRows: 10 } : undefined"
                size="small"
                class="mono"
                :readonly="!canEdit"
                @update:value="(v: string) => setVal(g.obj, f.key, v)"
              />
            </div>
          </n-tab-pane>
          <n-tab-pane name="mainJs" :tab="src.mainJs ? t('JS 源 ●') : t('JS 源')">
            <p class="muted small">
              {{ t('纯 JavaScript 单文件书源（阅读新版功能）：脚本中用') }} <code>const config = {...}</code> {{ t('声明书源信息， 并实现') }} <code>search</code> / <code>getChapters</code> / <code>getContent</code> {{ t('等函数。 填写后书源以 JS 方式运行，保存时会从脚本的 config 重新解析名称、地址等字段，其它分栏的规则将被忽略。') }}
            </p>
            <n-input
              :value="src.mainJs ?? ''"
              type="textarea"
              :autosize="{ minRows: 20, maxRows: 40 }"
              class="mono"
              :readonly="!canEdit"
              :placeholder="t('const config = { bookSourceUrl: \'https://example.com\', bookSourceName: \'示例\' }\nfunction search(key, page) { ... }\nfunction getChapters(book) { ... }\nfunction getContent(chapter) { ... }')"
              @update:value="(v: string) => (src.mainJs = v || null)"
            />
          </n-tab-pane>
          <n-tab-pane name="json" tab="JSON">
            <n-input
              v-model:value="jsonText"
              type="textarea"
              :autosize="{ minRows: 20, maxRows: 40 }"
              class="mono"
              :readonly="!canEdit"
              @blur="applyJson"
            />
            <p v-if="jsonError" class="err">{{ jsonError }}</p>
          </n-tab-pane>
        </n-tabs>
      </div>

      <div v-show="!isMobile || mobilePane === 'debug'" class="card debug">
        <div class="debug-head">
          <n-input v-model:value="debugKey" size="small" :placeholder="t('调试关键字')" @keyup.enter="runDebug" />
          <n-button v-if="!debugging" type="primary" size="small" @click="runDebug">
            <template #icon><n-icon :component="PlayOutline" /></template>{{ t('调试') }}
          </n-button>
          <n-button v-else type="warning" size="small" @click="stopDebug">
            <template #icon><n-icon :component="StopOutline" /></template>{{ t('停止') }}
          </n-button>
        </div>
        <n-space size="small" class="debug-tips">
          <n-tag v-for="h in debugHints" :key="h.key" size="tiny" :bordered="false">{{ h.label }}</n-tag>
          <label class="muted small"><n-switch v-model:value="showSource" size="small" /> {{ t('显示源码') }}</label>
        </n-space>
        <div ref="logBox" class="logs mono">
          <template v-for="(l, i) in logs" :key="i">
            <details v-if="htmlStates[l.state]" v-show="showSource" class="html-log">
              <summary>{{ t('{0}（{1} 字符）', [htmlStates[l.state], l.msg.length]) }}</summary>
              <pre>{{ l.msg }}</pre>
            </details>
            <div v-else class="log" :class="{ err: l.state === -1, done: l.state === 1000 }">{{ l.msg }}</div>
          </template>
          <div v-if="!logs.length" class="muted small">
            {{ t('调试规则：输入关键字测试搜索；输入详情页地址测试详情；“名称::地址” 测试发现；“++目录地址”、“--正文地址” 分别测试目录和正文。') }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wide {
  max-width: 1500px;
}

.page-header h2 {
  display: flex;
  align-items: baseline;
  min-width: 0;
}

/* 书源名：与标题隔开，一行放不下时省略 */
.name {
  flex: 1;
  min-width: 0;
  margin-left: 14px;
  font-size: 15px;
  font-weight: 400;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.form {
  padding: 8px 16px 16px;
}

.flags {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 8px 0 12px;
}

.flags label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.field {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 10px;
  align-items: start;
  margin-bottom: 8px;
}

.field > label {
  font-size: 13px;
  padding-top: 4px;
  color: var(--app-text-2);
}

.hint {
  display: inline-block;
  width: 14px;
  height: 14px;
  line-height: 14px;
  text-align: center;
  font-size: 10px;
  border-radius: 50%;
  border: 1px solid var(--app-text-2);
  cursor: help;
}

.mono :deep(textarea),
.mono :deep(input) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.debug {
  position: sticky;
  top: 16px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 110px);
}

.debug-head {
  display: flex;
  gap: 8px;
}

.debug-tips {
  margin: 8px 0;
  align-items: center;
}

.logs {
  flex: 1;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  background: rgba(128, 128, 128, 0.07);
  border-radius: 8px;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-all;
}

.log.err {
  color: #d03050;
}

.log.done {
  color: #18a058;
  font-weight: 600;
}

.html-log summary {
  cursor: pointer;
  color: var(--app-accent);
}

.html-log pre {
  max-height: 300px;
  overflow: auto;
  margin: 4px 0;
  font-size: 11px;
}

.err {
  color: #d03050;
  font-size: 13px;
}

.small {
  font-size: 12px;
}

@media (max-width: 1000px) {
  .editor {
    /* minmax(0, 1fr)：不被标签栏、长网址撑得比屏幕宽 */
    grid-template-columns: minmax(0, 1fr);
  }

  .debug {
    position: static;
    height: 60vh;
  }

  .field {
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }
}

.pane-switch {
  display: flex;
  margin-bottom: 12px;
}

.pane-switch :deep(.n-radio-button) {
  flex: 1;
  text-align: center;
}

/* 调试进行中的提示点 */
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 4px;
  border-radius: 50%;
  background: #18a058;
  vertical-align: 2px;
}

@media (max-width: 767px) {
  /* 页头：返回 + 标题一行，书源名单独一行省略，按钮排在下面 */
  .page-header h2 {
    display: block;
    flex: 1 1 calc(100% - 60px);
    min-width: 0;
    font-size: 18px;
  }

  .name {
    display: block;
    font-size: 13px;
    margin: 2px 0 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .form {
    padding: 4px 12px 12px;
  }

  /* iPhone 上输入框字号小于 16px 时，点击输入框页面会自动放大 */
  .mono :deep(textarea),
  .mono :deep(input),
  .debug-head :deep(input) {
    font-size: 16px;
  }

  .flags {
    gap: 10px 14px;
  }

  /* 调试面板占满切换按钮和底部导航之间的高度 */
  .debug {
    height: calc(100dvh - 250px);
    min-height: 360px;
  }
}
</style>
