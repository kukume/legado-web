<script setup lang="ts">
import { computed, onActivated, onMounted, ref, shallowRef, triggerRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NEmpty, NIcon, NInput, NInputGroup, NProgress, NSelect, NSwitch, NModal, useMessage } from 'naive-ui'
import { SearchOutline, StopCircleOutline } from '@vicons/ionicons5'
import SearchResultItem from '@/components/SearchResultItem.vue'
import { searchApi, shelfApi, sourceApi, toOpenRequest } from '@/api'
import type { MergedBook, SearchBook, SourceSummary } from '@/api/types'
import { useUiStore } from '@/stores/ui'
import { t } from '@/i18n'

defineOptions({ name: 'SearchView' })

const route = useRoute()
const router = useRouter()
const message = useMessage()
const ui = useUiStore()

const keyword = ref('')
const groups = ref<string[]>([])
const sources = ref<SourceSummary[]>([])
const results = shallowRef<Map<string, MergedBook>>(new Map())
const searching = ref(false)
const progress = ref({ done: 0, total: 0 })
const errors = ref<{ source: string; error: string }[]>([])
const showErrors = ref(false)
const exactOnly = ref(false)
const opening = ref<string | null>(null)
const lastKeyword = ref('')
let controller: AbortController | null = null

/**
 * 搜索范围（与 App 相同，可以按分组，也可以只搜某个书源）。保存的值：'' 全部，'g:分组名'，'s:书源 id'；
 * 旧版本保存的是分组名本身，按分组处理
 */
const scopeOptions = computed(() => {
  const enabled = sources.value.filter((x) => x.enabled)
  return [
    { label: t('全部书源'), value: '' },
    ...(groups.value.length
      ? [{ type: 'group', label: t('分组'), key: 'groups', children: groups.value.map((g) => ({ label: t('{0}（分组）', [g]), value: `g:${g}` })) }]
      : []),
    ...(enabled.length
      ? [{ type: 'group', label: t('书源'), key: 'sources', children: enabled.map((x) => ({ label: x.bookSourceName, value: `s:${x.id}` })) }]
      : []),
  ]
})

function scopeParams(): { group?: string; sourceIds?: number[] } {
  const v = ui.searchGroup || ''
  if (!v) return {}
  if (v.startsWith('s:')) return { sourceIds: [Number(v.slice(2))] }
  return { group: v.startsWith('g:') ? v.slice(2) : v }
}

const list = computed(() => {
  let arr = [...results.value.values()]
  const k = lastKeyword.value
  if (exactOnly.value && k) arr = arr.filter((b) => b.name.includes(k) || b.author.includes(k))
  // 排序：书名完全匹配 > 包含关键字 > 来源数量多 > 响应快
  const score = (b: MergedBook) =>
    (b.name === k ? 1000 : 0) + (b.name.includes(k) ? 100 : 0) + (b.author === k ? 50 : 0) + b.sources.length
  return arr.sort((a, b) => score(b) - score(a) || a.respondTime - b.respondTime)
})

/** 获取书源和分组；选过的书源已被删除或停用、分组已不存在时回到全部书源 */
async function reloadSources() {
  ;[groups.value, sources.value] = await Promise.all([sourceApi.groups(), sourceApi.list()])
  const v = ui.searchGroup || ''
  if (v.startsWith('s:') && !sources.value.some((x) => x.enabled && `s:${x.id}` === v)) ui.searchGroup = ''
  if (v.startsWith('g:') && !groups.value.includes(v.slice(2))) ui.searchGroup = ''
}

// 页面被缓存：回到搜索页时更新书源列表（例如刚删除、导入或启用了书源）
let mounted = false
onActivated(() => {
  if (!mounted) {
    mounted = true
    return
  }
  reloadSources().catch(() => {})
})

onMounted(async () => {
  try {
    await reloadSources()
  } catch {
    /* ignore */
  }
  const q = route.query.key as string | undefined
  if (q) {
    keyword.value = q
    search()
  }
})

function mergeKey(b: SearchBook) {
  return `${b.name.trim()}|${(b.author || '').trim()}`
}

async function search() {
  const key = keyword.value.trim()
  if (!key) return
  stop()
  lastKeyword.value = key
  router.replace({ query: { key } })
  results.value = new Map()
  errors.value = []
  progress.value = { done: 0, total: 0 }
  searching.value = true
  controller = new AbortController()
  try {
    await searchApi.search(
      { key, ...scopeParams() },
      (e) => {
        if (e.type === 'books') {
          const map = results.value
          for (const b of e.books) {
            const k = mergeKey(b)
            const exist = map.get(k)
            if (exist) {
              if (!exist.sources.some((s) => s.sourceId === b.sourceId && s.bookUrl === b.bookUrl)) exist.sources.push(b)
              if (!exist.coverUrl && b.coverUrl) {
                exist.coverUrl = b.coverUrl
                exist.sourceId = b.sourceId
              }
              if (!exist.intro && b.intro) exist.intro = b.intro
            } else {
              map.set(k, { ...b, key: k, sources: [b] })
            }
          }
          triggerRef(results)
        } else if (e.type === 'progress') {
          progress.value = { done: e.done, total: e.total }
          if (e.error) errors.value.push({ source: e.source, error: e.error })
        }
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e.name !== 'AbortError') message.error(e.message)
  } finally {
    searching.value = false
  }
}

function stop() {
  controller?.abort()
  controller = null
  searching.value = false
}

/** 从合并结果中移除打开失败的来源，返回剩余来源数；没有来源时整条结果移除 */
function dropSource(b: MergedBook, src: SearchBook): number {
  b.sources = b.sources.filter((s) => s !== src)
  if (b.sources.length === 0) {
    results.value.delete(b.key)
  } else if (b.bookUrl === src.bookUrl) {
    // 卡片上显示的来源信息换成剩下的来源
    const next = [...b.sources].sort((x, y) => x.respondTime - y.respondTime)[0]
    const coverUrl = b.coverUrl || next.coverUrl
    const sourceId = b.coverUrl ? b.sourceId : next.sourceId
    Object.assign(b, { ...next, key: b.key, sources: b.sources, coverUrl, sourceId, intro: b.intro || next.intro })
  }
  triggerRef(results)
  return b.sources.length
}

async function open(b: MergedBook) {
  if (opening.value) return
  opening.value = b.key
  // 优先使用响应最快的来源
  const src = [...b.sources].sort((x, y) => x.respondTime - y.respondTime)[0]
  try {
    const book = await shelfApi.open(toOpenRequest(src, false))
    router.push({ name: 'book', params: { id: book.id } })
  } catch (e: any) {
    const left = dropSource(b, src)
    // 脚本错误会附带大段源码上下文，只保留第一行
    const reason = String(e.message).split(/\n| Source context/)[0]
    message.error(
      t('「{0}」打开失败：{1}', [src.originName, reason]) +
        (left > 0 ? t('。已移除该来源，再次点击将换用其他来源（剩余 {0} 个）', [left]) : t('。没有其他来源了')),
      { duration: 5000 },
    )
  } finally {
    opening.value = null
  }
}

const percent = computed(() =>
  progress.value.total ? Math.round((progress.value.done / progress.value.total) * 100) : 0,
)
</script>

<template>
  <div class="page">
    <div class="search-bar">
      <n-input-group>
        <n-select v-model:value="ui.searchGroup" :options="scopeOptions" filterable style="width: 130px; flex-shrink: 0" size="large" :consistent-menu-width="false" />
        <n-input
          v-model:value="keyword"
          size="large"
          :placeholder="t('书名 / 作者')"
          clearable
          autofocus
          @keyup.enter="search"
        >
          <template #prefix><n-icon :component="SearchOutline" /></template>
        </n-input>
        <n-button v-if="!searching" type="primary" size="large" @click="search">{{ t('搜索') }}</n-button>
        <n-button v-else type="warning" size="large" @click="stop">
          <template #icon><n-icon :component="StopCircleOutline" /></template>
          {{ t('停止') }}
        </n-button>
      </n-input-group>
    </div>

    <div v-if="progress.total" class="status">
      <n-progress
        type="line"
        :percentage="percent"
        :show-indicator="false"
        :height="4"
        :processing="searching"
        style="flex: 1"
      />
      <span class="muted small">{{ t('{0}/{1} 个书源 · {2} 本', [progress.done, progress.total, list.length]) }}</span>
      <n-button v-if="errors.length" text size="tiny" type="error" @click="showErrors = true">{{ t('{0} 个失败', [errors.length]) }}</n-button>
      <span class="muted small">{{ t('精准') }}</span>
      <n-switch v-model:value="exactOnly" size="small" />
    </div>

    <n-empty v-if="!searching && lastKeyword && !list.length" :description="t('没有找到相关书籍')" style="padding: 60px 0" />
    <n-empty v-if="!lastKeyword" :description="t('输入书名或作者开始搜索')" style="padding: 80px 0" />

    <div class="results">
      <search-result-item
        v-for="b in list"
        :key="b.key"
        :book="b"
        :source-count="b.sources.length"
        :loading="opening === b.key"
        @open="open(b)"
      />
    </div>

    <!-- 失败的书源：用居中的弹窗，手机上也能完整显示（气泡弹层贴着按钮，会超出屏幕） -->
    <n-modal v-model:show="showErrors" preset="card" :title="t('{0} 个书源搜索失败', [errors.length])" style="width: 520px; max-width: calc(100vw - 24px)">
      <div class="err-list">
        <div v-for="(e, i) in errors" :key="i" class="err-line">
          <b>{{ e.source }}</b>：<span class="muted">{{ e.error }}</span>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.search-bar {
  max-width: 720px;
  margin: 8px auto 16px;
}

.status {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 720px;
  margin: 0 auto 16px;
}

.small {
  font-size: 12px;
  white-space: nowrap;
}

.results {
  display: grid;
  /* minmax(0, …)：列宽不被书籍卡片里不换行的长文字撑开（手机上会超出屏幕） */
  grid-template-columns: repeat(auto-fill, minmax(min(420px, 100%), 1fr));
  gap: 12px;
}

.err-list {
  max-height: 60vh;
  overflow-y: auto;
}

.err-line {
  font-size: 13px;
  padding: 4px 0;
  /* 错误信息里常有很长的网址，不换行会超出弹窗 */
  overflow-wrap: anywhere;
}

@media (max-width: 767px) {
  .results {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
