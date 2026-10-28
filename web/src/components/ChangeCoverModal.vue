<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NProgress, NSwitch, useMessage } from 'naive-ui'
import { searchApi } from '@/api'
import type { Book, SearchBook } from '@/api/types'
import BookCover from '@/components/BookCover.vue'
import { t } from '@/i18n'

/**
 * 换封面（与 App 相同）：用书名在所有启用的书源中搜索，列出同名书籍的封面供选择。
 * 选中后通过 select 返回封面地址，空字符串表示恢复为书源封面
 */
const props = defineProps<{ book: Book | null }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ select: [url: string] }>()
const message = useMessage()

const results = ref<SearchBook[]>([])
const progress = ref({ done: 0, total: 0 })
const searching = ref(false)
const matchAuthor = ref(true)
let controller: AbortController | null = null

/** 同名（可选同作者）书籍的封面，同一个地址只显示一次 */
const covers = computed(() => {
  const b = props.book
  if (!b) return []
  const seen = new Set<string>()
  return results.value.filter((r) => {
    if (!r.coverUrl || r.name !== b.name) return false
    if (matchAuthor.value && b.author && r.author !== b.author) return false
    if (seen.has(r.coverUrl)) return false
    seen.add(r.coverUrl)
    return true
  })
})

watch(show, (v) => {
  if (v) start()
  else controller?.abort()
})

async function start() {
  if (!props.book) return
  controller?.abort()
  controller = new AbortController()
  results.value = []
  progress.value = { done: 0, total: 0 }
  searching.value = true
  try {
    await searchApi.search(
      { key: props.book.name },
      (e) => {
        if (e.type === 'books') results.value.push(...e.books)
        if (e.type === 'progress') progress.value = { done: e.done, total: e.total }
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e.name !== 'AbortError') message.error(e.message)
  } finally {
    searching.value = false
  }
}

function pick(url: string) {
  controller?.abort()
  emit('select', url)
  show.value = false
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="t('换封面')" style="width: 720px; max-width: 94vw">
    <div class="cover-bar">
      <n-progress
        v-if="searching"
        type="line"
        :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
        :show-indicator="false"
        style="flex: 1"
      />
      <span v-else class="muted small" style="flex: 1">{{ t('找到 {0} 个封面', [covers.length]) }}</span>
      <span class="small">{{ t('作者相同') }}</span>
      <n-switch v-model:value="matchAuthor" size="small" />
    </div>
    <div class="cover-grid">
      <button v-if="book?.customCoverUrl" class="cover-item" @click="pick('')">
        <book-cover :url="book.coverUrl" :name="book.name" :author="book.author" :origin="book.origin" />
        <span class="cover-src">{{ t('恢复书源封面') }}</span>
      </button>
      <button v-for="r in covers" :key="r.coverUrl!" class="cover-item" :class="{ current: r.coverUrl === (book?.customCoverUrl || book?.coverUrl) }" @click="pick(r.coverUrl!)">
        <book-cover :url="r.coverUrl" :name="r.name" :author="r.author" :source-id="r.sourceId" />
        <span class="cover-src ellipsis">{{ r.originName }}</span>
      </button>
    </div>
    <n-empty v-if="!searching && !covers.length" :description="t('没有找到其他封面')" style="padding: 24px 0" />
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button v-if="!searching" size="small" @click="start">{{ t('重新搜索') }}</n-button>
        <n-button size="small" @click="show = false">{{ t('关闭') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.cover-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 14px 12px;
  max-height: 60vh;
  overflow-y: auto;
}

.cover-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 4px;
  border: 2px solid transparent;
  border-radius: 8px;
  background: none;
  color: inherit;
  cursor: pointer;
  text-align: center;
}

.cover-item:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}

.cover-item.current {
  border-color: var(--app-accent);
}

.cover-src {
  font-size: 12px;
  color: var(--app-text-2);
}

.small {
  font-size: 12.5px;
}
</style>
