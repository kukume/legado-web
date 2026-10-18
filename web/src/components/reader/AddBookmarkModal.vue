<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NInput, NModal, useMessage } from 'naive-ui'
import { bookmarkApi } from '@/api'
import { t } from '@/i18n'
import type { BookmarkDraft } from './types'

/** 添加书签的确认框：显示选中的文字，可以写备注 */
const props = defineProps<{ bookId: number }>()
const draft = defineModel<BookmarkDraft | null>('draft', { default: null })
const emit = defineEmits<{ saved: [] }>()
const message = useMessage()
const saving = ref(false)

async function confirm() {
  const d = draft.value
  if (!d) return
  saving.value = true
  try {
    await bookmarkApi.add(props.bookId, d)
    message.success(t('已添加书签，可在目录的「书签」中查看'))
    draft.value = null
    emit('saved')
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <n-modal
    :show="!!draft"
    preset="card"
    :title="t('添加书签')"
    style="width: 420px; max-width: 94vw"
    :auto-focus="false"
    @update:show="(v: boolean) => !v && (draft = null)"
  >
    <template v-if="draft">
      <div class="bm-draft-chapter">{{ draft.chapterName }}</div>
      <blockquote class="bm-draft-text">{{ draft.bookText }}</blockquote>
      <n-input v-model:value="draft.content" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :placeholder="t('备注（可不填）')" />
    </template>
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button @click="draft = null">{{ t('取消') }}</n-button>
        <n-button type="primary" :loading="saving" @click="confirm">{{ t('确定') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.bm-draft-chapter {
  font-size: 13px;
  opacity: 0.65;
}

.bm-draft-text {
  margin: 8px 0 12px;
  padding: 6px 10px;
  max-height: 160px;
  overflow-y: auto;
  border-left: 3px solid rgba(128, 128, 128, 0.35);
  background: rgba(128, 128, 128, 0.08);
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
