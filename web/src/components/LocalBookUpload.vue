<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NIcon, NModal, NProgress, NSelect, useMessage } from 'naive-ui'
import { CloudUploadOutline, CheckmarkCircle, CloseCircle } from '@vicons/ionicons5'
import { shelfApi } from '@/api'
import type { Book, BookGroup, LocalBookInfo } from '@/api/types'
import { t } from '@/i18n'
import { inspectPdf, isPdfFile } from '@/utils/pdf'

const EXTS = ['epub', 'txt', 'mobi', 'azw3', 'azw', 'umd', 'pdf']

const props = defineProps<{ show: boolean; groups: BookGroup[]; info: LocalBookInfo | null; defaultGroup?: number }>()
const emit = defineEmits<{ 'update:show': [boolean]; uploaded: [Book]; refreshInfo: [] }>()
const message = useMessage()

interface Item {
  file: File
  status: 'wait' | 'uploading' | 'done' | 'error'
  /** PDF 上传前在浏览器里读取页数、渲染封面 */
  reading?: boolean
  progress: number
  error?: string
  book?: Book
}

const items = ref<Item[]>([])
const groupId = ref(0)
const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const busy = computed(() => items.value.some((i) => i.status === 'uploading' || i.status === 'wait'))

watch(
  () => props.show,
  (v) => {
    if (v) groupId.value = props.defaultGroup && props.defaultGroup > 0 ? props.defaultGroup : 0
    else if (!busy.value) items.value = []
  },
)

const groupOptions = computed(() => [{ label: t('未分组'), value: 0 }, ...props.groups.map((g) => ({ label: g.name, value: g.id }))])

const accept = EXTS.map((e) => `.${e}`).join(',') + ',application/epub+zip,text/plain,application/pdf'

const fmtSize = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`)

/** 选择 / 拖入文件后加入队列并依次上传；外部（书架页面拖放）也会调用 */
function addFiles(files: FileList | File[]) {
  const max = (props.info?.maxMb ?? 50) * 1024 * 1024
  for (const f of Array.from(files)) {
    const ext = f.name.split('.').pop()?.toLowerCase()
    const item: Item = { file: f, status: 'wait', progress: 0 }
    if (!ext || !EXTS.includes(ext)) Object.assign(item, { status: 'error', error: t('只支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF') })
    else if (f.size > max) Object.assign(item, { status: 'error', error: t('超过 {0}MB', [props.info?.maxMb]) })
    else if (f.size === 0) Object.assign(item, { status: 'error', error: t('文件是空的') })
    items.value.push(item)
  }
  run()
}

let running = false
async function run() {
  if (running) return
  running = true
  try {
    for (;;) {
      const item = items.value.find((i) => i.status === 'wait')
      if (!item) break
      item.status = 'uploading'
      try {
        let pdf: { pages: number; cover: Blob | null } | null = null
        if (isPdfFile(item.file)) {
          item.reading = true
          try {
            pdf = await inspectPdf(item.file)
          } finally {
            item.reading = false
          }
        }
        item.book = await shelfApi.uploadLocal(item.file, groupId.value, (p) => (item.progress = p), pdf?.pages)
        // 封面上传失败不影响导入
        if (pdf?.cover) item.book = await shelfApi.uploadLocalCover(item.book.id, pdf.cover).catch(() => item.book!)
        item.status = 'done'
        emit('uploaded', item.book)
      } catch (e: any) {
        item.status = 'error'
        item.error = e.message
      }
    }
  } finally {
    running = false
    emit('refreshInfo')
  }
  const ok = items.value.filter((i) => i.status === 'done').length
  if (ok && !props.show) message.success(t('已导入 {0} 本书', [ok]))
}

function onDrop(e: DragEvent) {
  dragging.value = false
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files)
}

function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) addFiles(input.files)
  input.value = ''
}

defineExpose({ addFiles })
</script>

<template>
  <n-modal :show="show" preset="card" :title="t('导入本地书籍')" style="max-width: 520px" @update:show="(v: boolean) => emit('update:show', v)">
    <div
      class="drop"
      :class="{ dragging }"
      @click="fileInput?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <n-icon :component="CloudUploadOutline" size="40" />
      <div>{{ t('点击选择或拖入文件') }}</div>
      <div class="muted small">{{ t('支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF，单个文件最大 {0}MB', [info?.maxMb ?? 50]) }}</div>
      <input ref="fileInput" type="file" :accept="accept" multiple hidden @change="onPick" />
    </div>
    <div class="row">
      <span class="muted small">{{ t('放入分组') }}</span>
      <n-select v-model:value="groupId" :options="groupOptions" size="small" style="flex: 1" />
    </div>
    <div v-if="info" class="muted small quota">
      {{ t('已用空间 {0} / {1}MB', [fmtSize(info.usedBytes), info.quotaMb]) }}
    </div>

    <div v-if="items.length" class="items">
      <div v-for="(it, i) in items" :key="i" class="item">
        <div class="item-head">
          <span class="ellipsis name">{{ it.file.name }}</span>
          <span class="muted small">{{ fmtSize(it.file.size) }}</span>
          <n-icon v-if="it.status === 'done'" :component="CheckmarkCircle" color="#18a058" />
          <n-icon v-else-if="it.status === 'error'" :component="CloseCircle" color="#d03050" />
        </div>
        <n-progress
          v-if="it.status === 'uploading' || it.status === 'wait'"
          type="line"
          :percentage="Math.round(it.progress * 100)"
          :height="4"
          :show-indicator="false"
          :processing="it.progress >= 1"
        />
        <div v-if="it.reading" class="muted small">{{ t('正在读取 PDF…') }}</div>
        <div v-else-if="it.status === 'uploading' && it.progress >= 1" class="muted small">{{ t('正在解析目录…') }}</div>
        <div v-if="it.status === 'done' && it.book" class="muted small">{{ t('《{0}》共 {1} 章', [it.book.name, it.book.totalChapterNum]) }}</div>
        <div v-if="it.status === 'error'" class="small err">{{ it.error }}</div>
      </div>
    </div>

    <template #footer>
      <div class="footer">
        <n-button size="small" :disabled="busy" @click="items = []">{{ t('清空列表') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 26px 12px;
  border: 1.5px dashed var(--app-border);
  border-radius: 10px;
  cursor: pointer;
  color: var(--app-text-2);
  transition: border-color 0.15s, background 0.15s;
  text-align: center;
}

.drop:hover,
.drop.dragging {
  border-color: #18a058;
  background: rgba(24, 160, 88, 0.06);
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}

.quota {
  margin-top: 8px;
}

.items {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 260px;
  overflow: auto;
}

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name {
  flex: 1;
  min-width: 0;
}

.small {
  font-size: 12.5px;
}

.err {
  color: #d03050;
}

.footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}
</style>
