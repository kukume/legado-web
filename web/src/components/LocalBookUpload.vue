<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NIcon, NModal, NProgress, NSelect, useMessage } from 'naive-ui'
import { CloudUploadOutline, CheckmarkCircle, CloseCircle, CloudDownloadOutline } from '@vicons/ionicons5'
import { shelfApi, webdavApi } from '@/api'
import WebDavBookPicker, { type PickedFile } from '@/components/WebDavBookPicker.vue'
import type { Book, BookGroup, LocalBookInfo } from '@/api/types'
import { t } from '@/i18n'
import { inspectPdf, isPdfFile } from '@/utils/pdf'

const EXTS = ['epub', 'txt', 'mobi', 'azw3', 'azw', 'umd', 'pdf', 'zip']

const props = defineProps<{ show: boolean; groups: BookGroup[]; info: LocalBookInfo | null; defaultGroup?: number }>()
const emit = defineEmits<{ 'update:show': [boolean]; uploaded: [Book]; refreshInfo: [] }>()
const message = useMessage()

interface Item {
  /** 上传的文件（从 WebDAV 导入时为空） */
  file?: File
  /** 从 WebDAV 导入的文件 */
  remote?: PickedFile
  status: 'wait' | 'uploading' | 'done' | 'error'
  /** PDF 上传前在浏览器里读取页数、渲染封面 */
  reading?: boolean
  progress: number
  error?: string
  book?: Book
  /** zip 压缩包：解压后导入的书和跳过的文件 */
  books?: Book[]
  skipped?: { name: string; reason: string }[]
}

const items = ref<Item[]>([])
const groupId = ref(0)
const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const showDav = ref(false)
const itemName = (i: Item) => i.file?.name ?? i.remote?.name ?? ''
const itemSize = (i: Item) => i.file?.size ?? i.remote?.size ?? 0
const updatedCount = (books: Book[]) => books.filter((b) => b.updated).length

/** 从 WebDAV 选择的文件：加入队列，由服务器下载后导入 */
function addRemote(files: PickedFile[]) {
  for (const f of files) items.value.push({ remote: f, status: 'wait', progress: 0 })
  run()
}

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
    if (ext === 'rar' || ext === '7z') Object.assign(item, { status: 'error', error: t('暂不支持 RAR、7z 压缩包，请改用 zip，或解压后上传') })
    else if (!ext || !EXTS.includes(ext)) Object.assign(item, { status: 'error', error: t('只支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF 和 zip 压缩包') })
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
      if (item.remote) {
        try {
          const res = await webdavApi.importBook(item.remote.path, groupId.value)
          if ('archive' in res && res.archive) {
            item.books = res.books
            item.skipped = res.skipped
            res.books.forEach((b) => emit('uploaded', b))
          } else {
            item.book = res as Book
            emit('uploaded', item.book)
          }
          item.status = 'done'
        } catch (e: any) {
          item.status = 'error'
          item.error = e.message
        }
        continue
      }
      const file = item.file!
      try {
        let pdf: { pages: number; cover: Blob | null } | null = null
        if (isPdfFile(file)) {
          item.reading = true
          try {
            pdf = await inspectPdf(file)
          } finally {
            item.reading = false
          }
        }
        const res = await shelfApi.uploadLocal(file, groupId.value, (p) => (item.progress = p), pdf?.pages)
        if ('archive' in res && res.archive) {
          // zip 压缩包：解压后导入了多本
          item.books = res.books
          item.skipped = res.skipped
          item.status = 'done'
          res.books.forEach((b) => emit('uploaded', b))
          continue
        }
        item.book = res as Book
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
  const ok = items.value.reduce((n, i) => n + (i.status !== 'done' ? 0 : i.books ? i.books.length : 1), 0)
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
      <div class="muted small">{{ t('支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF 和 zip 压缩包，单个文件最大 {0}MB', [info?.maxMb ?? 50]) }}</div>
      <input ref="fileInput" type="file" :accept="accept" multiple hidden @change="onPick" />
    </div>
    <n-button size="small" secondary block class="dav-btn" @click="showDav = true">
      <template #icon><n-icon :component="CloudDownloadOutline" /></template>
      {{ t('从 WebDAV 选择') }}
    </n-button>
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
          <span class="ellipsis name">{{ itemName(it) }}</span>
          <span class="muted small">{{ fmtSize(itemSize(it)) }}</span>
          <n-icon v-if="it.status === 'done'" :component="CheckmarkCircle" color="#18a058" />
          <n-icon v-else-if="it.status === 'error'" :component="CloseCircle" color="#d03050" />
        </div>
        <n-progress
          v-if="(it.status === 'uploading' || it.status === 'wait') && !it.remote"
          type="line"
          :percentage="Math.round(it.progress * 100)"
          :height="4"
          :show-indicator="false"
          :processing="it.progress >= 1"
        />
        <div v-if="it.remote && it.status === 'wait'" class="muted small">{{ t('等待导入…') }}</div>
        <div v-else-if="it.remote && it.status === 'uploading'" class="muted small">{{ t('正在从 WebDAV 下载并导入…') }}</div>
        <div v-else-if="it.reading" class="muted small">{{ t('正在读取 PDF…') }}</div>
        <div v-else-if="it.status === 'uploading' && it.progress >= 1" class="muted small">{{ t('正在解析目录…') }}</div>
        <div v-if="it.status === 'done' && it.book" class="muted small">
          {{ it.book.updated ? t('已更新《{0}》，共 {1} 章（保留阅读进度）', [it.book.name, it.book.totalChapterNum]) : t('《{0}》共 {1} 章', [it.book.name, it.book.totalChapterNum]) }}
        </div>
        <template v-if="it.status === 'done' && it.books">
          <div class="muted small">{{ t('解压导入 {0} 本：{1}', [it.books.length, it.books.map((b) => `《${b.name}》`).join('')]) }}</div>
          <div v-if="updatedCount(it.books)" class="muted small">{{ t('其中 {0} 本是书架上已有的书，已更新（保留阅读进度）', [updatedCount(it.books)]) }}</div>
          <div v-for="s in it.skipped" :key="s.name" class="muted small skip">{{ t('跳过 {0}：{1}', [s.name, s.reason]) }}</div>
        </template>
        <div v-if="it.status === 'error'" class="small err">{{ it.error }}</div>
      </div>
    </div>

    <template #footer>
      <div class="footer">
        <n-button size="small" :disabled="busy" @click="items = []">{{ t('清空列表') }}</n-button>
      </div>
    </template>
  </n-modal>
  <web-dav-book-picker v-model:show="showDav" @pick="addRemote" />
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

.dav-btn {
  margin-top: 10px;
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
