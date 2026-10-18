<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NIcon, NInput, NModal, NSpace, NSpin, useDialog, useMessage } from 'naive-ui'
import { CreateOutline, TrashOutline } from '@vicons/ionicons5'
import { bookmarkApi } from '@/api'
import type { Bookmark } from '@/api/types'
import { t } from '@/i18n'

/** 阅读页目录抽屉里的书签列表 */
const props = defineProps<{ bookId: number; active: boolean }>()
const emit = defineEmits<{ jump: [Bookmark] }>()
const message = useMessage()
const dialog = useDialog()

const list = ref<Bookmark[]>([])
const loading = ref(false)
const keyword = ref('')
/** 按选中的文字、备注、章节名搜索 */
const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return list.value
  return list.value.filter((b) => [b.bookText, b.content, b.chapterName].some((t) => t.toLowerCase().includes(k)))
})

async function load() {
  loading.value = true
  try {
    list.value = await bookmarkApi.list(props.bookId)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

// 切换到书签页时刷新（阅读时可能刚添加过）
watch(
  () => [props.active, props.bookId] as const,
  ([a]) => a && load(),
  { immediate: true },
)

function remove(b: Bookmark) {
  dialog.warning({
    title: t('删除书签'),
    content: b.bookText ? `「${b.bookText.slice(0, 40)}」` : b.chapterName,
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await bookmarkApi.remove([b.id])
      list.value = list.value.filter((x) => x.id !== b.id)
    },
  })
}

const editing = ref<Bookmark | null>(null)
const note = ref('')
function edit(b: Bookmark) {
  editing.value = b
  note.value = b.content
}
async function saveNote() {
  const b = editing.value
  if (!b) return
  try {
    await bookmarkApi.update(b.id, note.value)
    b.content = note.value
    editing.value = null
  } catch (e: any) {
    message.error(e.message)
  }
}

defineExpose({ load })
</script>

<template>
  <div class="bookmarks">
    <div v-if="loading && !list.length" class="center"><n-spin size="small" /></div>
    <n-empty v-else-if="!list.length" :description="t('还没有书签，选中正文的文字即可添加')" style="margin-top: 48px" />
    <template v-else>
      <div class="search">
        <n-input v-model:value="keyword" size="small" :placeholder="t('搜索书签（文字、备注、章节）')" clearable />
      </div>
      <n-empty v-if="!shown.length" :description="t('没有匹配的书签')" style="margin-top: 32px" />
    </template>
    <div v-for="b in shown" :key="b.id" class="bm" @click="emit('jump', b)">
      <div class="head">
        <span class="chapter ellipsis">{{ b.chapterName || t('第 {0} 章', [b.chapterIndex + 1]) }}</span>
        <span class="ops" @click.stop>
          <n-button quaternary circle size="tiny" :title="t('备注')" @click="edit(b)">
            <template #icon><n-icon :component="CreateOutline" /></template>
          </n-button>
          <n-button quaternary circle size="tiny" :title="t('删除')" @click="remove(b)">
            <template #icon><n-icon :component="TrashOutline" /></template>
          </n-button>
        </span>
      </div>
      <div v-if="b.bookText" class="text">{{ b.bookText }}</div>
      <div v-if="b.content" class="note">{{ b.content }}</div>
    </div>

    <n-modal :show="!!editing" preset="card" :title="t('书签备注')" style="width: 420px; max-width: 94vw" @update:show="(v: boolean) => !v && (editing = null)">
      <n-input v-model:value="note" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :placeholder="t('写点什么…')" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="editing = null">{{ t('取消') }}</n-button>
          <n-button type="primary" @click="saveNote">{{ t('保存') }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.bookmarks {
  padding: 4px 0 16px;
}

.search {
  padding: 8px 12px;
}

.center {
  display: flex;
  justify-content: center;
  padding: 32px 0;
}

.bm {
  padding: 10px 16px;
  border-bottom: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.15));
  cursor: pointer;
}

.bm:hover {
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
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
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
