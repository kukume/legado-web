<script setup lang="ts">
import { NTag } from 'naive-ui'
import BookCover from './BookCover.vue'
import type { SearchBook } from '@/api/types'
import { t } from '@/i18n'

defineProps<{ book: SearchBook; sourceCount?: number; loading?: boolean }>()
defineEmits<{ (e: 'open'): void }>()

function kinds(b: SearchBook) {
  return (b.kind || '')
    .split(/[,，\n]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 3)
}
</script>

<template>
  <div class="item card" :class="{ loading }" @click="$emit('open')">
    <book-cover :url="book.coverUrl" :name="book.name" :source-id="book.sourceId" :width="72" />
    <div class="body">
      <div class="title ellipsis">{{ book.name }}</div>
      <div class="meta muted ellipsis">
        <span>{{ book.author || t('佚名') }}</span>
        <span v-if="book.wordCount"> · {{ book.wordCount }}</span>
      </div>
      <div v-if="kinds(book).length" class="tags">
        <n-tag v-for="k in kinds(book)" :key="k" size="tiny" :bordered="false">{{ k }}</n-tag>
      </div>
      <div class="intro muted ellipsis-2">{{ book.intro?.trim() || t('暂无简介') }}</div>
      <div class="foot muted">
        <span v-if="book.latestChapterTitle" class="latest">{{ t('最新：{0}', [book.latestChapterTitle]) }}</span>
        <span class="src" :title="book.originName">{{ book.originName }}<template v-if="sourceCount && sourceCount > 1"> {{ t('等 {0} 个源', [sourceCount]) }}</template></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.item {
  display: flex;
  gap: 14px;
  min-width: 0;
  padding: 12px;
  cursor: pointer;
  transition: border-color 0.15s, opacity 0.15s;
}

.item:hover {
  border-color: var(--app-accent);
}

.item.loading {
  opacity: 0.6;
  pointer-events: none;
}

.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.meta,
.foot {
  font-size: 12.5px;
}

.tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.intro {
  font-size: 13px;
  line-height: 1.5;
}

.foot {
  display: flex;
  gap: 12px;
  min-width: 0;
}

/* 最新章节、来源名都可能很长：各自省略，不撑开卡片 */
.foot > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.latest {
  flex: 1 1 auto;
}

/* 来源名在右侧，最多占一半宽度；没有最新章节时靠左 */
.src {
  flex: 0 1 auto;
  max-width: 50%;
  margin-left: auto;
  color: var(--app-accent);
}

.src:only-child {
  max-width: 100%;
  margin-left: 0;
}
</style>
