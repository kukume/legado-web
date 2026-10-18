<script setup lang="ts">
import { computed, h, onActivated, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NDropdown, NEmpty, NIcon, NInput, NModal, NSelect, NSpin, NTabs, NTab, NBadge, NTooltip,
  NRadioGroup, NRadioButton, NAlert, NTag, NSpace, useDialog, useMessage,
} from 'naive-ui'
import { RefreshOutline, SearchOutline, AddOutline, GridOutline, ListOutline, EllipsisVertical, CloudUploadOutline } from '@vicons/ionicons5'
import BookCover from '@/components/BookCover.vue'
import LocalBookUpload from '@/components/LocalBookUpload.vue'
import { groupApi, shelfApi } from '@/api'
import type { Book, BookGroup, LocalBookInfo } from '@/api/types'
import { formatTime } from '@/utils/media'
import { isNetworkError } from '@/api/http'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

defineOptions({ name: 'ShelfView' })

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const books = ref<Book[]>([])
const groups = ref<BookGroup[]>([])
const loading = ref(false)
const refreshing = ref(false)
const activeGroup = ref<number>(-1)
const filter = ref('')
const offline = useOfflineStore()
/** 离线状态：只显示本机已下载的书 */
const offlineMode = ref(false)
const viewMode = ref<'grid' | 'list'>((localStorage.getItem('legado_shelf_view') as any) || 'grid')

const shown = computed(() => {
  let list = books.value
  if (activeGroup.value >= 0) list = list.filter((b) => b.groupId === activeGroup.value)
  const f = filter.value.trim()
  if (f) list = list.filter((b) => b.name.includes(f) || b.author.includes(f))
  return list
})

// ---------- 本地书籍导入 ----------
const localInfo = ref<LocalBookInfo | null>(null)
const showUpload = ref(false)
const uploader = ref<InstanceType<typeof LocalBookUpload> | null>(null)
const pageDragging = ref(false)
let dragDepth = 0

async function loadLocalInfo() {
  localInfo.value = await shelfApi.localInfo().catch(() => null)
}

function onUploaded(b: Book) {
  books.value = [b, ...books.value.filter((x) => x.id !== b.id)]
}

const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types || []).includes('Files')
function onPageDragEnter(e: DragEvent) {
  if (!localInfo.value?.enabled || offlineMode.value || showUpload.value || !hasFiles(e)) return
  dragDepth++
  pageDragging.value = true
}
function onPageDragLeave() {
  if (dragDepth > 0 && --dragDepth === 0) pageDragging.value = false
}
function onPageDrop(e: DragEvent) {
  dragDepth = 0
  if (!pageDragging.value) return
  pageDragging.value = false
  const files = e.dataTransfer?.files
  if (!files?.length) return
  showUpload.value = true
  uploader.value?.addFiles(files)
}

/** 离线：只显示本机已离线下载的书（分组在服务器上，离线时不显示） */
async function showOfflineShelf() {
  books.value = await offline.loadOfflineShelf().catch(() => [])
  groups.value = []
  activeGroup.value = -1
  offlineMode.value = true
}

async function load() {
  // 已知离线时直接显示本机的书，不再等请求超时；恢复联网后由下面的 watch 重新加载
  if (!offline.online) return showOfflineShelf()
  loading.value = true
  try {
    const [b, g] = await Promise.all([shelfApi.list(), groupApi.list()])
    books.value = b
    groups.value = g
    offlineMode.value = false
    if (!localInfo.value) loadLocalInfo()
  } catch (e: any) {
    offline.markError(e)
    if (isNetworkError(e)) await showOfflineShelf()
    else message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
onActivated(() => {
  if (!loading.value) load()
})

// 网络断开 / 恢复（包括连着网但服务器不可达、之后探测到服务器恢复）时自动刷新书架
watch(
  () => offline.online,
  (on) => {
    if (!on) showOfflineShelf()
    else if (!loading.value) load()
  },
)

async function refreshAll() {
  refreshing.value = true
  try {
    const r = await shelfApi.refreshAll()
    message.success(r.message)
    setTimeout(load, 8000)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    setTimeout(() => (refreshing.value = false), 3000)
  }
}

function setView(v: 'grid' | 'list') {
  viewMode.value = v
  localStorage.setItem('legado_shelf_view', v)
}

function read(b: Book) {
  router.push({ name: b.isVideo ? 'watch' : b.isAudio ? 'listen' : 'read', params: { id: b.id } })
}

function progressText(b: Book) {
  if (!b.totalChapterNum) return t('未读')
  const pct = Math.min(100, Math.round(((b.durChapterIndex + 1) / b.totalChapterNum) * 100))
  return `${pct}%`
}

function unread(b: Book) {
  return Math.max(0, b.totalChapterNum - b.durChapterIndex - 1)
}

// ---------- 书籍菜单 ----------
const menuOptions = [
  { label: t('书籍详情'), key: 'info' },
  { label: t('移动分组'), key: 'group' },
  { label: t('更新目录'), key: 'refresh' },
  { label: t('移出书架'), key: 'remove' },
]
const localMenuOptions = [
  { label: t('书籍详情'), key: 'info' },
  { label: t('移动分组'), key: 'group' },
  { label: t('重新解析目录'), key: 'refresh' },
  { label: t('删除'), key: 'remove' },
]
const menuFor = (b: Book) => (b.isLocal ? localMenuOptions : menuOptions)

const moveTarget = ref<Book | null>(null)
const moveGroup = ref<number>(0)

async function onMenu(key: string, b: Book) {
  if (key === 'info') router.push({ name: 'book', params: { id: b.id } })
  if (key === 'group') {
    moveTarget.value = b
    moveGroup.value = b.groupId
  }
  if (key === 'refresh') {
    try {
      const nb = await shelfApi.refresh(b.id)
      Object.assign(b, nb)
      message.success(t('《{0}》目录已更新，共 {1} 章', [b.name, nb.totalChapterNum]))
    } catch (e: any) {
      message.error(e.message)
    }
  }
  if (key === 'remove') {
    dialog.warning({
      title: t('移出书架'),
      content: b.isLocal
        ? t('确定要删除《{0}》吗？上传的文件和阅读进度会一并删除。', [b.name])
        : t('确定要删除《{0}》吗？阅读进度和缓存会一并删除。', [b.name]),
      positiveText: t('删除'),
      negativeText: t('取消'),
      onPositiveClick: async () => {
        await shelfApi.remove(b.id)
        books.value = books.value.filter((x) => x.id !== b.id)
        if (b.isLocal) loadLocalInfo()
      },
    })
  }
}

async function doMove() {
  if (!moveTarget.value) return
  const nb = await shelfApi.update(moveTarget.value.id, { groupId: moveGroup.value })
  Object.assign(moveTarget.value, nb)
  moveTarget.value = null
}

const groupOptions = computed(() => [
  { label: t('未分组'), value: 0 },
  ...groups.value.map((g) => ({ label: g.name, value: g.id })),
])

// ---------- 分组管理 ----------
const showGroups = ref(false)
const newGroup = ref('')
async function addGroup() {
  if (!newGroup.value.trim()) return
  const g = await groupApi.create(newGroup.value.trim(), groups.value.length)
  groups.value.push(g)
  newGroup.value = ''
}
async function renameGroup(g: BookGroup, name: string) {
  if (!name.trim() || name === g.name) return
  await groupApi.update(g.id, name.trim(), g.order)
  g.name = name.trim()
}
async function removeGroup(g: BookGroup) {
  await groupApi.remove(g.id)
  groups.value = groups.value.filter((x) => x.id !== g.id)
  books.value.forEach((b) => b.groupId === g.id && (b.groupId = 0))
  if (activeGroup.value === g.id) activeGroup.value = -1
}

const icon = (c: any) => () => h(NIcon, null, { default: () => h(c) })
</script>

<template>
  <div class="page" @dragenter="onPageDragEnter" @dragleave="onPageDragLeave" @dragover.prevent @drop.prevent="onPageDrop">
    <div v-if="pageDragging" class="page-drop">{{ t('松开鼠标导入本地书籍') }}</div>
    <div class="page-header">
      <h2>{{ t('书架') }}</h2>
      <n-input v-model:value="filter" :placeholder="t('筛选书名 / 作者')" clearable size="small" style="max-width: 200px">
        <template #prefix><n-icon :component="SearchOutline" /></template>
      </n-input>
      <n-radio-group :value="viewMode" size="small" @update:value="setView">
        <n-radio-button value="grid"><n-icon :component="GridOutline" /></n-radio-button>
        <n-radio-button value="list"><n-icon :component="ListOutline" /></n-radio-button>
      </n-radio-group>
      <n-tooltip>
        <template #trigger>
          <n-button size="small" :loading="refreshing" :render-icon="icon(RefreshOutline)" @click="refreshAll" />
        </template>
        {{ t('更新全部书籍目录') }}
      </n-tooltip>
      <n-tooltip v-if="localInfo?.enabled && !offlineMode">
        <template #trigger>
          <n-button size="small" :render-icon="icon(CloudUploadOutline)" @click="showUpload = true">{{ t('导入') }}</n-button>
        </template>
        {{ t('导入本地书籍（EPUB、TXT、MOBI、AZW3、UMD、PDF，也可以直接拖到书架上）') }}
      </n-tooltip>
      <n-button size="small" type="primary" :render-icon="icon(AddOutline)" @click="router.push('/search')">{{ t('找书') }}</n-button>
    </div>

    <div class="groups">
      <n-tabs v-model:value="activeGroup" type="line" size="small" style="flex: 1; min-width: 0">
        <n-tab :name="-1">{{ t('全部 {0}', [books.length]) }}</n-tab>
        <n-tab :name="0">{{ t('未分组') }}</n-tab>
        <n-tab v-for="g in groups" :key="g.id" :name="g.id">{{ g.name }}</n-tab>
      </n-tabs>
      <n-button text size="small" class="muted" @click="showGroups = true">{{ t('管理分组') }}</n-button>
    </div>

    <n-alert v-if="offlineMode" type="info" :bordered="false" style="margin-bottom: 12px">
      {{ t('当前离线，只显示本机已离线下载的书；联网后会自动同步阅读进度') }}
    </n-alert>

    <n-spin :show="loading && !books.length">
      <n-empty v-if="!loading && !shown.length" :description="t('书架空空如也')" style="padding: 60px 0">
        <template #extra>
          <n-space justify="center">
            <n-button type="primary" @click="router.push('/search')">{{ t('去搜索') }}</n-button>
            <n-button v-if="localInfo?.enabled && !offlineMode" @click="showUpload = true">{{ t('导入本地书籍') }}</n-button>
          </n-space>
        </template>
      </n-empty>

      <div v-if="viewMode === 'grid'" class="grid">
        <div v-for="b in shown" :key="b.id" class="grid-item" @click="read(b)">
          <n-badge :value="b.lastCheckCount || unread(b)" :max="999" :show="unread(b) > 0" :type="b.lastCheckCount ? 'error' : 'info'" :offset="[-14, 6]">
            <book-cover :url="b.customCoverUrl || b.coverUrl" :name="b.name" :author="b.author" :origin="b.origin" />
          </n-badge>
          <div class="grid-title ellipsis-2">{{ b.name }}</div>
          <div class="grid-sub muted ellipsis">
            <span v-if="b.isLocal" class="offline-mark local-mark">{{ t('本地') }}</span><span v-if="offline.bookIds.has(b.id)" class="offline-mark">{{ t('离线') }}</span>{{ progressText(b) }} · {{ b.author }}
          </div>
          <n-dropdown trigger="click" :options="menuFor(b)" @select="(k: string) => onMenu(k, b)">
            <button class="more" @click.stop>
              <n-icon :component="EllipsisVertical" />
            </button>
          </n-dropdown>
        </div>
      </div>

      <div v-else class="list">
        <div v-for="b in shown" :key="b.id" class="list-item card" @click="read(b)">
          <book-cover :url="b.customCoverUrl || b.coverUrl" :name="b.name" :origin="b.origin" :width="64" />
          <div class="list-body">
            <div class="list-title">
              <span class="ellipsis">{{ b.name }}</span>
              <n-tag v-if="b.isLocal" size="small" :bordered="false" type="info">{{ t('本地') }}</n-tag>
              <n-tag v-if="offline.bookIds.has(b.id)" size="small" :bordered="false" type="success">{{ t('离线') }}</n-tag>
              <n-badge v-if="b.lastCheckCount" :value="b.lastCheckCount" type="error" />
            </div>
            <div class="muted ellipsis small">{{ b.author }} · {{ b.originName }}</div>
            <div class="muted ellipsis small">{{ t('读到：{0}', [b.durChapterTitle || t('未开始')]) }}</div>
            <div v-if="b.isLocal" class="muted ellipsis small">{{ t('共 {0} 章', [b.totalChapterNum]) }}</div>
            <div v-else class="muted ellipsis small">{{ t('最新：{0} · {1}', [b.latestChapterTitle || '-', formatTime(b.lastCheckTime)]) }}</div>
          </div>
          <n-dropdown trigger="click" :options="menuFor(b)" @select="(k: string) => onMenu(k, b)">
            <n-button quaternary circle size="small" :render-icon="icon(EllipsisVertical)" @click.stop />
          </n-dropdown>
        </div>
      </div>
    </n-spin>

    <local-book-upload
      ref="uploader"
      v-model:show="showUpload"
      :groups="groups"
      :info="localInfo"
      :default-group="activeGroup"
      @uploaded="onUploaded"
      @refresh-info="loadLocalInfo"
    />

    <n-modal :show="!!moveTarget" preset="dialog" :title="t('移动分组')" :positive-text="t('确定')" :negative-text="t('取消')"
      @positive-click="doMove" @update:show="(v: boolean) => !v && (moveTarget = null)">
      <n-select v-model:value="moveGroup" :options="groupOptions" />
    </n-modal>

    <n-modal v-model:show="showGroups" preset="card" :title="t('分组管理')" style="max-width: 420px">
      <div v-for="g in groups" :key="g.id" class="group-row">
        <n-input :default-value="g.name" size="small" @change="(v: string) => renameGroup(g, v)" />
        <n-button size="small" type="error" quaternary @click="removeGroup(g)">{{ t('删除') }}</n-button>
      </div>
      <div class="group-row">
        <n-input v-model:value="newGroup" size="small" :placeholder="t('新分组名称')" @keyup.enter="addGroup" />
        <n-button size="small" type="primary" @click="addGroup">{{ t('添加') }}</n-button>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.offline-mark {
  display: inline-block;
  font-size: 10px;
  line-height: 14px;
  padding: 0 4px;
  margin-right: 4px;
  border-radius: 3px;
  color: #fff;
  background: #18a058;
  vertical-align: 1px;
}

.local-mark {
  background: #2080f0;
}

.page {
  position: relative;
}

.page-drop {
  position: fixed;
  inset: 12px;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed #18a058;
  border-radius: 14px;
  background: rgba(24, 160, 88, 0.1);
  color: #18a058;
  font-size: 18px;
  font-weight: 600;
  pointer-events: none;
  backdrop-filter: blur(2px);
}

.groups {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 22px 16px;
}

.grid-item {
  position: relative;
  cursor: pointer;
  /* 书名、作者很长时不撑宽这一列（否则同一行的封面大小不一） */
  min-width: 0;
}

.grid-item :deep(.cover) {
  width: 100%;
}

.grid-item :deep(.n-badge) {
  display: block;
}

.grid-item:hover .cover {
  transform: translateY(-2px);
}

.grid-item .cover {
  transition: transform 0.15s;
}

.grid-title {
  margin-top: 8px;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.35;
}

.grid-sub {
  font-size: 12px;
  margin-top: 2px;
  padding-right: 18px;
}

.more {
  position: absolute;
  right: -4px;
  bottom: 0;
  border: none;
  background: none;
  color: var(--app-text-2);
  padding: 2px;
  cursor: pointer;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-item {
  display: flex;
  gap: 14px;
  padding: 12px;
  cursor: pointer;
  align-items: center;
}

.list-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.list-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
}

.small {
  font-size: 12.5px;
}

.group-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

@media (max-width: 767px) {
  .grid {
    /* minmax(0, 1fr)：三列严格等宽，不被长书名撑开 */
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px 12px;
  }
}
</style>
