<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { flexItemStyle } from '@/utils/flex'
import { useRouter } from 'vue-router'
import { NButton, NEmpty, NInput, NSelect, NSpin, useMessage } from 'naive-ui'
import SearchResultItem from '@/components/SearchResultItem.vue'
import { shelfApi, sourceApi, toOpenRequest } from '@/api'
import type { ExploreKind, SearchBook, SourceSummary } from '@/api/types'
import { safeHref } from '@/utils/safeUrl'
import { showSourceToast } from '@/utils/effects'
import { t } from '@/i18n'

defineOptions({ name: 'ExploreView' })

const router = useRouter()
const message = useMessage()

const sources = ref<SourceSummary[]>([])
const sourceId = ref<number | null>(Number(localStorage.getItem('legado_explore_source')) || null)
const kinds = ref<ExploreKind[]>([])
const infoMap = reactive<Record<string, string>>({})
const kindsLoading = ref(false)
const activeKind = ref<ExploreKind | null>(null)
const books = ref<SearchBook[]>([])
const page = ref(1)
const loading = ref(false)
const noMore = ref(false)
const opening = ref<string | null>(null)
const acting = ref<string | null>(null)
const openLinks = ref<string[]>([])

const sourceOptions = computed(() =>
  sources.value
    .filter((s) => s.hasExplore && s.enabledExplore && s.enabled)
    .map((s) => ({ label: s.bookSourceName, value: s.id })),
)

/** 重新获取书源列表；当前书源不可用时切换到第一个。返回书源是否变化 */
async function reloadSources(): Promise<boolean> {
  sources.value = await sourceApi.list()
  if (!sourceOptions.value.some((o) => o.value === sourceId.value)) {
    sourceId.value = sourceOptions.value[0]?.value ?? null
    return true
  }
  return false
}

onMounted(async () => {
  try {
    await reloadSources()
    if (sourceId.value) loadKinds()
  } catch (e: any) {
    message.error(e.message)
  }
})

// 页面被缓存：回到发现页时更新书源列表（例如刚启用了新书源）
let mounted = false
onActivated(async () => {
  if (!mounted) {
    mounted = true
    return
  }
  try {
    const changed = await reloadSources()
    if (changed) {
      kinds.value = []
      books.value = []
      activeKind.value = null
      if (sourceId.value) loadKinds()
    }
  } catch {
    /* 静默 */
  }
})

const refreshing = ref(false)
/** 刷新：重新获取书源列表和当前书源的分类 */
async function refreshAll() {
  refreshing.value = true
  try {
    await reloadSources()
    if (sourceId.value) await loadKinds(true, true)
    else {
      kinds.value = []
      books.value = []
    }
    message.success(t('已刷新，共 {0} 个支持发现的书源', [sourceOptions.value.length]))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    refreshing.value = false
  }
}

async function loadKinds(refresh = false, keepSelection = false) {
  if (!sourceId.value) return
  localStorage.setItem('legado_explore_source', String(sourceId.value))
  kindsLoading.value = true
  const prevTitle = activeKind.value?.title
  if (!keepSelection) {
    books.value = []
    activeKind.value = null
  }
  try {
    const r = await sourceApi.exploreKinds(sourceId.value, refresh)
    kinds.value = r.kinds
    Object.keys(infoMap).forEach((k) => delete infoMap[k])
    Object.assign(infoMap, r.infoMap)
    const again = keepSelection && prevTitle ? r.kinds.find((k) => k.title === prevTitle && k.url) : null
    const first = again ?? r.kinds.find((k) => isLink(k))
    if (first) selectKind(first)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    kindsLoading.value = false
  }
}

// ---------- 分类类型 ----------
const isControl = (k: ExploreKind) => k.type !== 'url'
/** 书源的发现规则执行出错（与 App 相同，出错时返回一个标题为 ERROR: 的分类，地址是错误详情） */
const isError = (k: ExploreKind) => k.title?.startsWith('ERROR:')
/** 错误信息只取第一行（脚本错误会附带大段源码上下文） */
const errorText = (k: ExploreKind) => k.title.slice(6).split(/\n| Source context/)[0].trim() || t('未知错误')
const isLink = (k: ExploreKind) => !isControl(k) && !isError(k) && !!k.url && !k.url.trim().startsWith('{{')
const isJsLink = (k: ExploreKind) => !isControl(k) && !isError(k) && !!k.url && k.url.trim().startsWith('{{')
const label = (k: ExploreKind) => k.displayName || k.title

/** 按书源设定的 FlexChildStyle 布局，与 App 一致 */
function itemStyle(k: ExploreKind) {
  return flexItemStyle(k.style, false)
}

function applyEffects(r: { messages?: string[]; openUrls?: string[] }) {
  r.messages?.forEach((m) => m && showSourceToast(message, m))
  if (r.openUrls?.length) {
    openLinks.value = r.openUrls
    r.openUrls.forEach((u) => window.open(u, '_blank', 'noopener'))
  }
}

/** 执行控件 action，完成后重新生成分类（action 通常会修改书源变量） */
async function runControl(k: ExploreKind, value: string | null) {
  if (!sourceId.value) return
  if (value !== null) infoMap[k.title] = value
  acting.value = k.title
  try {
    const r = await sourceApi.exploreAction(sourceId.value, {
      title: k.title,
      value,
      action: k.action,
    })
    applyEffects(r)
    if (!r.ok) message.error(r.message || t('执行失败'))
    else if (r.result && /^https?:\/\//.test(r.result)) window.open(r.result, '_blank', 'noopener')
    await loadKinds(false, true)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    acting.value = null
  }
}

function toggleNext(k: ExploreKind) {
  const chars = k.chars?.length ? k.chars : ['false', 'true']
  const cur = infoMap[k.title] ?? k.default ?? chars[0]
  const next = chars[(chars.indexOf(cur) + 1) % chars.length]
  runControl(k, next)
}

async function clickKind(k: ExploreKind) {
  if (isLink(k)) return selectKind(k)
  if (isJsLink(k) && sourceId.value) {
    // 只执行 js 的分类（如打开登录页），不影响当前列表
    try {
      const r = await sourceApi.explore(sourceId.value, k.url!, 1)
      applyEffects(r)
      if (r.books.length) {
        activeKind.value = k
        books.value = r.books
      }
    } catch (e: any) {
      message.error(e.message)
    }
  }
}

function selectKind(k: ExploreKind) {
  if (!k.url) return
  activeKind.value = k
  page.value = 1
  books.value = []
  noMore.value = false
  loadPage()
}

async function loadPage() {
  if (!sourceId.value || !activeKind.value?.url || loading.value) return
  loading.value = true
  try {
    const r = await sourceApi.explore(sourceId.value, activeKind.value.url, page.value)
    applyEffects(r)
    const exists = new Set(books.value.map((b) => b.bookUrl))
    const fresh = r.books.filter((b) => !exists.has(b.bookUrl))
    books.value.push(...fresh)
    if (!fresh.length) noMore.value = true
    page.value++
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

async function open(b: SearchBook) {
  opening.value = b.bookUrl
  try {
    const book = await shelfApi.open(toOpenRequest(b))
    router.push({ name: 'book', params: { id: book.id } })
  } catch (e: any) {
    message.error(e.message)
  } finally {
    opening.value = null
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header explore-header">
      <h2>{{ t('发现') }}</h2>
      <n-select
        v-model:value="sourceId"
        :options="sourceOptions"
        filterable
        :placeholder="t('选择书源')"
        class="source-select"
        @update:value="() => loadKinds()"
      />
      <n-button size="small" :loading="refreshing" :title="t('重新获取书源列表和分类')" style="flex-shrink: 0" @click="refreshAll">{{ t('刷新') }}</n-button>
    </div>

    <n-empty v-if="!sourceOptions.length" :description="t('没有支持发现的书源')" style="padding: 60px 0" />

    <div v-if="openLinks.length" class="links card">
      <span class="muted">{{ t('书源请求打开以下网页（若未自动弹出请点击）：') }}</span>
      <a v-for="u in openLinks" :key="u" :href="safeHref(u)" target="_blank" rel="noopener">{{ u }}</a>
      <n-button text size="tiny" @click="openLinks = []">{{ t('关闭') }}</n-button>
    </div>

    <n-spin :show="kindsLoading">
      <div class="kinds">
        <template v-for="(k, i) in kinds" :key="i">
          <!-- 发现规则执行出错 -->
          <div v-if="isError(k)" class="kind-error">
            <div>{{ t('获取发现分类失败：{0}', [errorText(k)]) }}</div>
            <details v-if="k.url">
              <summary>{{ t('错误详情') }}</summary>
              <pre>{{ k.url.slice(0, 4000) }}</pre>
            </details>
          </div>
          <!-- 下拉选择 -->
          <div v-else-if="k.type === 'select'" class="kind control" :style="itemStyle(k)">
            <span class="control-label">{{ label(k) }}</span>
            <n-select
              size="small"
              :value="infoMap[k.title] ?? k.default ?? null"
              :options="(k.chars || []).map((c) => ({ label: c, value: c }))"
              :loading="acting === k.title"
              filterable
              @update:value="(v: string) => runControl(k, v)"
            />
          </div>
          <!-- 文本输入 -->
          <div v-else-if="k.type === 'text' || k.type === 'password'" class="kind control" :style="itemStyle(k)">
            <span class="control-label">{{ label(k) }}</span>
            <n-input
              size="small"
              :type="k.type === 'password' ? 'password' : 'text'"
              :value="infoMap[k.title] ?? ''"
              :loading="acting === k.title"
              @update:value="(v: string) => (infoMap[k.title] = v)"
              @keyup.enter="runControl(k, infoMap[k.title] ?? '')"
              @blur="runControl(k, infoMap[k.title] ?? '')"
            />
          </div>
          <!-- 开关：在 chars 中循环 -->
          <n-button
            v-else-if="k.type === 'toggle'"
            class="kind"
            :style="itemStyle(k)"
            size="small"
            :loading="acting === k.title"
            @click="toggleNext(k)"
          >{{ label(k) }}：{{ infoMap[k.title] ?? k.default ?? '' }}</n-button>
          <!-- 按钮 -->
          <n-button
            v-else-if="k.type === 'button'"
            class="kind"
            :style="itemStyle(k)"
            size="small"
            secondary
            :loading="acting === k.title"
            @click="runControl(k, null)"
          >{{ label(k) }}</n-button>
          <!-- 分类链接 -->
          <button
            v-else-if="k.url"
            class="kind chip"
            :class="{ active: activeKind === k, js: isJsLink(k) }"
            :style="itemStyle(k)"
            @click="clickKind(k)"
          >{{ label(k) }}</button>
          <!-- 纯标题（分隔行 / 占位） -->
          <div v-else class="kind heading" :class="{ spacer: /^-+$/.test(k.title.trim()) }" :style="itemStyle(k)">
            {{ /^-+$/.test(k.title.trim()) ? '' : k.title }}
          </div>
        </template>
      </div>
    </n-spin>

    <div class="results">
      <search-result-item v-for="b in books" :key="b.bookUrl" :book="b" :loading="opening === b.bookUrl" @open="open(b)" />
    </div>
    <div v-if="activeKind && isLink(activeKind)" class="more">
      <n-button v-if="!noMore" :loading="loading" @click="loadPage">{{ t('加载更多') }}</n-button>
      <span v-else class="muted">{{ t('没有更多了') }}</span>
    </div>
  </div>
</template>

<style scoped>
/* 书源下拉框：最宽 240px，手机上空间不够时自动变窄，不把“刷新”挤到第二行 */
.explore-header {
  flex-wrap: nowrap;
}

/* 去掉全局样式给标题的最小宽度 120px；宽屏时标题占剩余空间（下拉框和按钮靠右），窄屏时下拉框变窄 */
.explore-header h2 {
  flex: 1 0 auto;
  min-width: 0;
  white-space: nowrap;
}

.source-select {
  flex: 0 1 240px;
  min-width: 0;
}

.kinds {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
  min-height: 32px;
}

.kind {
  min-width: 0;
}

.control {
  display: flex;
  align-items: center;
  gap: 8px;
}

.control-label {
  font-size: 13px;
  color: var(--app-text-2);
  white-space: nowrap;
}

.chip {
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  color: var(--app-text);
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 13px;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: border-color 0.15s, color 0.15s;
}

.chip:hover {
  border-color: var(--app-accent);
}

.chip.active {
  border-color: var(--app-accent);
  color: var(--app-accent);
  background: color-mix(in srgb, var(--app-accent) 10%, var(--app-surface));
  font-weight: 600;
}

.chip.js {
  border-style: dashed;
}

.heading {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-2);
  padding: 8px 2px 2px;
  text-align: center;
}

.heading.spacer {
  padding: 0;
}

.links {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  margin-bottom: 14px;
  font-size: 13px;
}

.links a {
  color: var(--app-accent);
  word-break: break-all;
}

.results {
  display: grid;
  /* minmax(0, …)：列宽不被书籍卡片里不换行的长文字撑开（手机上会超出屏幕） */
  grid-template-columns: repeat(auto-fill, minmax(min(420px, 100%), 1fr));
  gap: 12px;
}

.more {
  text-align: center;
  padding: 20px;
}

@media (max-width: 767px) {
  .results {
    grid-template-columns: minmax(0, 1fr);
  }
}
.kind-error {
  flex: 1 1 100%;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(208, 48, 80, 0.08);
  color: #d03050;
  font-size: 13px;
}

.kind-error pre {
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 11px;
  color: var(--app-text-2);
}
</style>
