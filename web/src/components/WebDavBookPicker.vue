<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NEmpty, NIcon, NModal, NSpin, NTag } from 'naive-ui'
import { DocumentTextOutline, FolderOutline, ArchiveOutline, ChevronForward } from '@vicons/ionicons5'
import { webdavApi, type WebDavBookEntry } from '@/api'
import { t } from '@/i18n'

/** 可以从 WebDAV 导入的格式（PDF 的页数和封面要在浏览器里读取，需下载后上传） */
const EXTS = ['epub', 'txt', 'mobi', 'azw3', 'azw', 'umd', 'zip']
const LAST_PATH_KEY = 'legado_webdav_book_path'

export interface PickedFile {
  path: string
  name: string
  size: number
}

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [boolean]; pick: [PickedFile[]] }>()
const router = useRouter()

function loadPath() {
  try {
    return localStorage.getItem(LAST_PATH_KEY) ?? ''
  } catch {
    return ''
  }
}

const path = ref(loadPath())
const entries = ref<WebDavBookEntry[]>([])
const loading = ref(false)
const error = ref('')
const notConfigured = ref(false)
/** 选中的文件（完整路径） */
const selected = ref(new Map<string, PickedFile>())

const extOf = (name: string) => (name.includes('.') ? name.split('.').pop()!.toLowerCase() : '')
const isPdf = (e: WebDavBookEntry) => !e.isDir && extOf(e.name) === 'pdf'
const canImport = (e: WebDavBookEntry) => !e.isDir && EXTS.includes(extOf(e.name))
/** 只显示目录和书籍（PDF 显示但不能选，提示下载后上传） */
const visible = computed(() => entries.value.filter((e) => e.isDir || canImport(e) || isPdf(e)))
const files = computed(() => visible.value.filter(canImport))
const hiddenCount = computed(() => entries.value.length - visible.value.length)

const crumbs = computed(() => {
  const parts = path.value.split('/').filter(Boolean)
  return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join('/') }))
})

const join = (dir: string, name: string) => (dir ? `${dir}/${name}` : name)

async function load(p: string) {
  loading.value = true
  error.value = ''
  notConfigured.value = false
  try {
    entries.value = await webdavApi.books(p)
    path.value = p
    try {
      localStorage.setItem(LAST_PATH_KEY, p)
    } catch {
      /* ignore */
    }
  } catch (e: any) {
    entries.value = []
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 使用备份设置里的 WebDAV 账号：还没配置时提示去设置 */
async function start() {
  selected.value = new Map()
  error.value = ''
  notConfigured.value = false
  loading.value = true
  try {
    const c = await webdavApi.config()
    if (!c.account || !c.hasPassword) {
      notConfigured.value = true
      error.value = t('还没有配置 WebDAV（与备份共用，在“我的 → 备份与恢复”中设置）')
      loading.value = false
      return
    }
  } catch (e: any) {
    error.value = e.message
    loading.value = false
    return
  }
  await load(path.value)
}

watch(
  () => props.show,
  (v) => {
    if (v) start()
  },
)

function open(e: WebDavBookEntry) {
  if (e.isDir) load(join(path.value, e.name))
  else if (canImport(e)) toggle(e)
}

function toggle(e: WebDavBookEntry) {
  const full = join(path.value, e.name)
  const m = new Map(selected.value)
  if (m.has(full)) m.delete(full)
  else m.set(full, { path: full, name: e.name, size: e.size })
  selected.value = m
}

const allSelected = computed(() => files.value.length > 0 && files.value.every((e) => selected.value.has(join(path.value, e.name))))
function toggleAll() {
  const m = new Map(selected.value)
  const all = allSelected.value
  for (const e of files.value) {
    const full = join(path.value, e.name)
    if (all) m.delete(full)
    else m.set(full, { path: full, name: e.name, size: e.size })
  }
  selected.value = m
}

function confirm() {
  emit('pick', [...selected.value.values()])
  emit('update:show', false)
}

function goSettings() {
  emit('update:show', false)
  router.push({ name: 'settings' })
}

const fmtSize = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`)
const iconOf = (e: WebDavBookEntry) => (e.isDir ? FolderOutline : extOf(e.name) === 'zip' ? ArchiveOutline : DocumentTextOutline)
</script>

<template>
  <n-modal :show="show" preset="card" :title="t('从 WebDAV 选择书籍')" style="max-width: 560px" @update:show="(v: boolean) => emit('update:show', v)">
    <div class="crumbs">
      <button class="crumb" :disabled="loading" @click="load('')">WebDAV</button>
      <template v-for="c in crumbs" :key="c.path">
        <n-icon :component="ChevronForward" class="sep" />
        <button class="crumb" :disabled="loading" @click="load(c.path)">{{ c.name }}</button>
      </template>
    </div>

    <div class="list">
      <div v-if="loading" class="center"><n-spin size="small" /></div>
      <div v-else-if="error" class="center err">
        <div>{{ error }}</div>
        <n-button v-if="notConfigured" size="small" style="margin-top: 10px" @click="goSettings">{{ t('去设置 WebDAV') }}</n-button>
        <n-button v-else size="small" style="margin-top: 10px" @click="load(path)">{{ t('重试') }}</n-button>
      </div>
      <n-empty v-else-if="!visible.length" :description="t('这个目录里没有书籍')" class="center" />
      <template v-else>
        <div v-if="files.length" class="row all" @click="toggleAll">
          <n-checkbox :checked="allSelected" @click.stop @update:checked="toggleAll" />
          <span class="muted small">{{ t('全选本目录的书籍（{0}）', [files.length]) }}</span>
        </div>
        <div
          v-for="e in visible"
          :key="e.name"
          class="row"
          :class="{ disabled: isPdf(e) }"
          @click="open(e)"
        >
          <n-checkbox v-if="canImport(e)" :checked="selected.has(join(path, e.name))" @click.stop @update:checked="toggle(e)" />
          <span v-else class="box-space" />
          <n-icon :component="iconOf(e)" class="icon" />
          <span class="ellipsis name">{{ e.name }}</span>
          <n-tag v-if="e.imported" size="small" :bordered="false" type="info">{{ t('已导入') }}</n-tag>
          <span v-if="isPdf(e)" class="muted small">{{ t('PDF 请下载后上传') }}</span>
          <span v-else-if="!e.isDir" class="muted small">{{ fmtSize(e.size) }}</span>
          <n-icon v-else :component="ChevronForward" class="muted" />
        </div>
        <div v-if="hiddenCount" class="muted small hidden-note">{{ t('另有 {0} 个其它文件未显示', [hiddenCount]) }}</div>
      </template>
    </div>

    <div class="muted small note">{{ t('已导入的书再次导入会更新为新文件，保留阅读进度、书签和标注') }}</div>

    <template #footer>
      <div class="footer">
        <span class="muted small">{{ t('已选 {0} 个', [selected.size]) }}</span>
        <n-button size="small" type="primary" :disabled="!selected.size" @click="confirm">{{ t('导入') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  margin-bottom: 8px;
}

.crumb {
  border: none;
  background: none;
  padding: 2px 4px;
  border-radius: 4px;
  color: var(--app-text-2);
  cursor: pointer;
  font-size: 13px;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crumb:hover:not(:disabled) {
  background: rgba(127, 127, 127, 0.12);
}

.crumb:last-child {
  color: inherit;
  font-weight: 600;
}

.sep {
  opacity: 0.5;
  font-size: 12px;
}

.list {
  min-height: 200px;
  max-height: min(55vh, 420px);
  overflow: auto;
  border: 1px solid var(--app-border);
  border-radius: 8px;
}

.center {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  padding: 16px;
  text-align: center;
}

.err {
  color: #d03050;
  font-size: 13px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--app-border);
}

.row:last-child {
  border-bottom: none;
}

.row:hover {
  background: rgba(127, 127, 127, 0.08);
}

.row.disabled {
  cursor: default;
  opacity: 0.6;
}

.row.all {
  background: rgba(127, 127, 127, 0.05);
}

.box-space {
  width: 16px;
  flex-shrink: 0;
}

.icon {
  flex-shrink: 0;
  font-size: 18px;
  opacity: 0.75;
}

.name {
  flex: 1;
  min-width: 0;
}

.small {
  font-size: 12.5px;
}

.hidden-note {
  padding: 8px 10px;
}

.note {
  margin-top: 10px;
}

.footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
}
</style>
