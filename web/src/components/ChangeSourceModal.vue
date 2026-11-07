<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NProgress, NSwitch, NTag, useMessage } from 'naive-ui'
import { searchApi, shelfApi, toOpenRequest } from '@/api'
import type { Book, SearchBook } from '@/api/types'
import { t } from '@/i18n'

const props = defineProps<{ show: boolean; book: Book | null }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'changed', b: Book): void }>()
const message = useMessage()

const results = ref<SearchBook[]>([])
const progress = ref({ done: 0, total: 0 })
const searching = ref(false)
const matchAuthor = ref(true)
/** 正在切换的结果（按书源区分：多个书源可能返回同一个书籍地址） */
const changing = ref<string | null>(null)
const keyOf = (r: SearchBook) => `${r.sourceId}|${r.bookUrl}`
const isCurrent = (r: SearchBook) => r.bookUrl === props.book?.bookUrl && (props.book?.sourceId == null || r.sourceId === props.book.sourceId)
let controller: AbortController | null = null

const list = computed(() => {
  const b = props.book
  if (!b) return []
  return results.value
    .filter((r) => r.name === b.name && (!matchAuthor.value || !b.author || r.author === b.author))
    .sort((a, c) => a.respondTime - c.respondTime)
})

watch(
  () => props.show,
  (v) => {
    if (v) start()
    else controller?.abort()
  },
)

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

async function change(r: SearchBook) {
  if (!props.book) return
  if (changing.value) return
  changing.value = keyOf(r)
  try {
    const nb = await shelfApi.changeSource(props.book.id, toOpenRequest(r))
    message.success(t('已切换到「{0}」', [r.originName]))
    emit('changed', nb)
    emit('update:show', false)
  } catch (e: any) {
    message.error(t('换源失败：{0}', [e.message]))
  } finally {
    changing.value = null
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="t('换源')"
    style="max-width: 560px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="bar">
      <n-progress
        type="line"
        :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
        :show-indicator="false"
        :processing="searching"
        :height="4"
        style="flex: 1"
      />
      <span class="muted small">{{ progress.done }}/{{ progress.total }}</span>
      <span class="muted small">{{ t('校验作者') }}</span>
      <n-switch v-model:value="matchAuthor" size="small" />
      <n-button size="tiny" :disabled="searching" @click="start">{{ t('重新搜索') }}</n-button>
    </div>
    <n-empty v-if="!searching && !list.length" :description="t('没有找到其他来源')" />
    <div class="list">
      <div
        v-for="r in list"
        :key="keyOf(r)"
        class="row"
        :class="{ current: isCurrent(r) }"
        @click="!isCurrent(r) && change(r)"
      >
        <div class="row-main">
          <div class="ellipsis">
            <b>{{ r.originName }}</b>
            <n-tag v-if="isCurrent(r)" size="tiny" type="success" :bordered="false" style="margin-left: 6px">{{ t('当前') }}</n-tag>
          </div>
          <div class="muted ellipsis small">{{ r.latestChapterTitle || t('无最新章节') }}</div>
        </div>
        <span class="muted small">{{ r.respondTime }}ms</span>
        <n-button v-if="!isCurrent(r)" size="tiny" :loading="changing === keyOf(r)" :disabled="!!changing && changing !== keyOf(r)">{{ t('切换') }}</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.small {
  font-size: 12px;
  white-space: nowrap;
}

.list {
  max-height: 60vh;
  overflow: auto;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 6px;
  border-bottom: 1px solid var(--app-border);
  cursor: pointer;
}

.row:hover {
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}

.row.current {
  cursor: default;
}

.row-main {
  flex: 1;
  min-width: 0;
}
</style>
