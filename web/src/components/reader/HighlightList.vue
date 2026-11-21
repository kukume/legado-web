<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NEmpty, NIcon, NInput } from 'naive-ui'
import { CreateOutline, TrashOutline } from '@vicons/ionicons5'
import type { BookHighlight } from '@/api/types'
import { parseStyle, styleToCss } from '@/utils/highlight'
import { t } from '@/i18n'

/** 阅读页目录抽屉里的标注列表（与 App 目录里的“标注”页相同）：点击跳转，可以写备注、删除 */
const props = defineProps<{ list: BookHighlight[] }>()
const emit = defineEmits<{ jump: [BookHighlight]; note: [BookHighlight]; remove: [BookHighlight] }>()

const keyword = ref('')
/** 按章节、位置排序；按文字、备注、章节名搜索 */
const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  const sorted = [...props.list].sort((a, b) => a.chapterIndex - b.chapterIndex || a.chapterPos - b.chapterPos)
  if (!k) return sorted
  return sorted.filter((h) => [h.bookText, h.note, h.chapterName].some((s) => s.toLowerCase().includes(k)))
})

const css = (h: BookHighlight) => styleToCss(parseStyle(h.style))
</script>

<template>
  <div class="highlights">
    <n-empty v-if="!list.length" :description="t('还没有标注，选中正文的文字后点“高亮”即可添加')" style="margin-top: 48px" />
    <template v-else>
      <div class="search">
        <n-input v-model:value="keyword" size="small" :placeholder="t('搜索标注（文字、备注、章节）')" clearable />
      </div>
      <n-empty v-if="!shown.length" :description="t('没有匹配的标注')" style="margin-top: 32px" />
    </template>
    <div v-for="h in shown" :key="h.id" class="item" @click="emit('jump', h)">
      <div class="head">
        <span class="chapter ellipsis">{{ h.chapterName || t('第 {0} 章', [h.chapterIndex + 1]) }}</span>
        <span class="ops" @click.stop>
          <n-button quaternary circle size="tiny" :title="t('备注')" @click="emit('note', h)">
            <template #icon><n-icon :component="CreateOutline" /></template>
          </n-button>
          <n-button quaternary circle size="tiny" :title="t('删除')" @click="emit('remove', h)">
            <template #icon><n-icon :component="TrashOutline" /></template>
          </n-button>
        </span>
      </div>
      <div class="text"><span class="sample" :style="css(h)">{{ h.bookText }}</span></div>
      <div v-if="h.note" class="note">{{ h.note }}</div>
    </div>
  </div>
</template>

<style scoped>
.highlights {
  padding: 4px 0 16px;
}

.search {
  padding: 8px 12px;
}

.item {
  padding: 10px 16px;
  border-bottom: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.15));
  cursor: pointer;
}

.item:hover {
  background: rgba(128, 128, 128, 0.08);
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chapter {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  opacity: 0.65;
}

.ellipsis {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.ops {
  display: flex;
  flex-shrink: 0;
}

.text {
  margin-top: 4px;
  font-size: 14px;
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sample {
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}

.note {
  margin-top: 6px;
  padding: 4px 8px;
  font-size: 13px;
  line-height: 1.5;
  border-radius: 4px;
  background: rgba(128, 128, 128, 0.1);
  white-space: pre-wrap;
}
</style>
