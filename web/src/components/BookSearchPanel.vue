<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NEmpty, NInput, NSpin, useMessage } from 'naive-ui'
import { shelfApi } from '@/api'
import type { BookSearchHit, BookSearchResult } from '@/api/types'
import { t } from '@/i18n'

/** 阅读页目录抽屉里的书内全文搜索 */
const props = defineProps<{ bookId: number; isLocal: boolean }>()
const emit = defineEmits<{ jump: [hit: BookSearchHit, keyword: string] }>()
const message = useMessage()

const keyword = ref('')
/** 结果对应的关键字（输入框改了之后，结果仍按原关键字高亮和跳转） */
const searchedKeyword = ref('')
const result = ref<BookSearchResult | null>(null)
const loading = ref(false)
const caching = ref(false)

async function search() {
  const q = keyword.value.trim()
  if (!q) return
  loading.value = true
  try {
    result.value = await shelfApi.search(props.bookId, q)
    searchedKeyword.value = q
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 在线书籍：还有多少章没有缓存（未缓存的章节不在搜索范围内） */
const uncached = computed(() => (result.value && !props.isLocal ? result.value.total - result.value.searched : 0))

async function cacheMore() {
  caching.value = true
  try {
    // 服务器每次在后台缓存最多 200 个未缓存的章节
    await shelfApi.cache(props.bookId, 0, 200)
    message.success(t('已开始在后台缓存章节（每次最多 200 章），完成后重新搜索即可搜到'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    caching.value = false
  }
}

function parts(h: BookSearchHit) {
  return [
    h.snippet.slice(0, h.matchStart),
    h.snippet.slice(h.matchStart, h.matchStart + h.matchLength),
    h.snippet.slice(h.matchStart + h.matchLength),
  ]
}
</script>

<template>
  <div class="book-search">
    <div class="bar">
      <n-input v-model:value="keyword" size="small" :placeholder="t('搜索本书内容')" clearable @keyup.enter="search" />
      <n-button size="small" type="primary" :loading="loading" @click="search">{{ t('搜索') }}</n-button>
    </div>

    <div v-if="result" class="info">
      <span>{{ t('共 {0}{1} 处', [result.hits.length, result.truncated ? '+' : '']) }}</span>
      <span v-if="!isLocal">{{ t('· 已搜索 {0} / {1} 章', [result.searched, result.total]) }}</span>
    </div>
    <div v-if="uncached > 0" class="uncached">
      {{ t('还有 {0} 章没有缓存，不在搜索范围内', [uncached]) }}
      <n-button text type="primary" size="tiny" :loading="caching" @click="cacheMore">{{ t('缓存章节') }}</n-button>
    </div>
    <div v-if="result?.truncated" class="uncached">{{ t('结果太多，只显示前 {0} 处，可以换个更具体的关键字', [result.hits.length]) }}</div>

    <div v-if="loading && !result" class="center"><n-spin size="small" /></div>
    <n-empty v-else-if="result && !result.hits.length" :description="t('没有找到')" style="margin-top: 32px" />
    <n-empty v-else-if="!result" :description="isLocal ? t('搜索全书内容') : t('搜索已缓存章节的内容')" style="margin-top: 48px" />

    <div v-for="(h, i) in result?.hits ?? []" :key="i" class="hit" @click="emit('jump', h, searchedKeyword)">
      <div class="chapter">{{ h.chapterTitle }}</div>
      <div class="snippet">{{ parts(h)[0] }}<mark>{{ parts(h)[1] }}</mark>{{ parts(h)[2] }}</div>
    </div>
  </div>
</template>

<style scoped>
.book-search {
  padding-bottom: 16px;
}

.bar {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
}

.info,
.uncached {
  padding: 0 16px 6px;
  font-size: 12px;
  opacity: 0.7;
}

.uncached {
  color: #d97706;
  opacity: 1;
}

.center {
  display: flex;
  justify-content: center;
  padding: 32px 0;
}

.hit {
  padding: 8px 16px;
  border-bottom: 1px solid rgba(128, 128, 128, 0.15);
  cursor: pointer;
}

.hit:hover {
  background: rgba(128, 128, 128, 0.08);
}

.chapter {
  font-size: 12px;
  opacity: 0.6;
}

.snippet {
  margin-top: 2px;
  font-size: 14px;
  line-height: 1.6;
}

mark {
  padding: 0 1px;
  border-radius: 2px;
  background: rgba(255, 190, 0, 0.45);
  color: inherit;
}
</style>
