<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { NDrawer, NDrawerContent, NInput, NTab, NTabs, NVirtualList } from 'naive-ui'
import BookmarkList from '@/components/BookmarkList.vue'
import BookSearchPanel from '@/components/BookSearchPanel.vue'
import type { Bookmark, BookSearchHit, Chapter } from '@/api/types'
import { t } from '@/i18n'

/** 阅读页左侧抽屉：目录、书签、书内搜索 */
const props = defineProps<{
  bookId: number
  isLocal: boolean
  chapters: Chapter[]
  curIndex: number
}>()
const show = defineModel<boolean>('show', { default: false })
defineEmits<{
  go: [index: number]
  jumpBookmark: [b: Bookmark]
  jumpSearch: [hit: BookSearchHit, keyword: string]
}>()

const tab = ref<'toc' | 'bookmark' | 'search'>('toc')
/** 搜索页打开过之后保留（只隐藏），切换标签不丢失搜索结果 */
const searchOpened = ref(false)
watch(tab, (v) => {
  if (v === 'search') searchOpened.value = true
})

const bookmarkList = ref<InstanceType<typeof BookmarkList> | null>(null)

const filter = ref('')
const list = computed(() => {
  const f = filter.value.trim()
  return f ? props.chapters.filter((c) => c.title.includes(f)) : props.chapters
})
const listRef = ref<any>(null)

/** 目录滚动到当前章节（居中显示） */
function scrollToCurrent() {
  const i = list.value.findIndex((c) => c.index === props.curIndex)
  if (i < 0) return
  const el = listRef.value?.$el as HTMLElement | undefined
  const h = el?.clientHeight || window.innerHeight
  listRef.value?.scrollTo({ top: Math.max(0, i * 42 - h / 2 + 21) })
}

watch(show, (v) => {
  if (!v) return
  filter.value = ''
  nextTick(scrollToCurrent)
})
// 从「书签」切回「目录」时重新定位到当前章节（列表刚从隐藏变为显示，等它重新测量尺寸后再滚动）
watch(tab, (v) => {
  if (v !== 'toc' || !show.value) return
  setTimeout(scrollToCurrent, 60)
})
// 清空搜索后回到当前章节
watch(filter, (f) => {
  if (!f.trim()) nextTick(scrollToCurrent)
})

defineExpose({
  /** 添加书签后刷新书签列表 */
  reloadBookmarks: () => bookmarkList.value?.load(),
})
</script>

<template>
  <!-- display-directive="show"：关闭时不销毁内容，书内搜索的关键字和结果保留 -->
  <n-drawer v-model:show="show" :width="360" placement="left" style="max-width: 88vw" :auto-focus="false" display-directive="show" @after-enter="scrollToCurrent">
    <n-drawer-content :native-scrollbar="false" body-content-style="padding: 0">
      <template #header>
        <n-tabs v-model:value="tab" type="segment" size="small" style="width: 260px" animated>
          <n-tab name="toc">{{ t('目录（{0}）', [chapters.length]) }}</n-tab>
          <n-tab name="bookmark">{{ t('书签') }}</n-tab>
          <n-tab name="search">{{ t('搜索') }}</n-tab>
        </n-tabs>
      </template>
      <book-search-panel v-if="searchOpened" v-show="tab === 'search'" :book-id="bookId" :is-local="isLocal" @jump="(h: BookSearchHit, k: string) => $emit('jumpSearch', h, k)" />
      <bookmark-list v-if="tab === 'bookmark'" ref="bookmarkList" :book-id="bookId" :active="show && tab === 'bookmark'" @jump="(b: Bookmark) => $emit('jumpBookmark', b)" />
      <!-- 目录列表切到书签时只隐藏不销毁：重新创建的虚拟列表要等测量后才渲染，切回来会是空的 -->
      <div v-show="tab === 'toc'">
        <div style="padding: 8px 12px">
          <n-input v-model:value="filter" size="small" :placeholder="t('搜索章节')" clearable />
        </div>
        <n-virtual-list ref="listRef" :items="list" :item-size="42" key-field="index" style="height: calc(100vh - 130px)">
          <template #default="{ item }">
            <div
              class="toc-row"
              :class="{ active: item.index === curIndex, volume: item.isVolume }"
              @click="!item.isVolume && $emit('go', item.index)"
            >
              <span class="ellipsis">{{ item.title }}</span>
              <span v-if="item.cached" class="dot" />
            </div>
          </template>
        </n-virtual-list>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.toc-row {
  height: 42px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  font-size: 14px;
  cursor: pointer;
  border-bottom: 1px solid rgba(128, 128, 128, 0.12);
}

.toc-row:hover {
  background: rgba(128, 128, 128, 0.08);
}

.toc-row.active {
  color: var(--app-accent);
  font-weight: 600;
}

.toc-row.volume {
  font-weight: 600;
  opacity: 0.7;
  cursor: default;
}

.toc-row .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #18a058;
  flex-shrink: 0;
}
</style>
