<script setup lang="ts">
import { goBack } from '@/utils/navigation'
import { computed, onMounted, ref, watch } from 'vue'
import { isNetworkError } from '@/api/http'
import { offlineContentIndexes } from '@/offline/db'
import { useOfflineStore } from '@/stores/offline'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NButtonGroup, NDropdown, NIcon, NInput, NModal, NSelect, NSkeleton, NSpace, NTag,
  NForm, NFormItem, NSwitch, NAlert, NProgress, useDialog, useMessage,
} from 'naive-ui'
import { ArrowBackOutline, BookOutline, EllipsisHorizontal, SwapVerticalOutline } from '@vicons/ionicons5'
import BookCover from '@/components/BookCover.vue'
import ChangeSourceModal from '@/components/ChangeSourceModal.vue'
import TxtTocRuleModal from '@/components/TxtTocRuleModal.vue'
import { groupApi, shelfApi } from '@/api'
import type { Book, BookGroup, Chapter } from '@/api/types'
import { formatTime } from '@/utils/media'
import { t } from '@/i18n'
import { isPdfBook } from '@/utils/pdf'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const id = computed(() => Number(route.params.id))
const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const groups = ref<BookGroup[]>([])
const loading = ref(true)
const refreshing = ref(false)
const reverse = ref(false)
const tocFilter = ref('')
const showCount = ref(200)
const showChange = ref(false)
const offline = useOfflineStore()
/** 离线状态下显示的是本地保存的数据 */
const offlineMode = ref(false)
/** 本机已离线下载的章节 */
const offlineIndexes = ref(new Set<number>())

async function load() {
  loading.value = true
  try {
    const [b, c, g] = await Promise.all([shelfApi.get(id.value), shelfApi.chapters(id.value), groupApi.list()])
    book.value = b
    chapters.value = c
    groups.value = g
    offlineMode.value = false
    offline.refreshBook(b, c)
  } catch (e: any) {
    offline.markError(e)
    const local = isNetworkError(e) ? await offline.loadOfflineBook(id.value).catch(() => null) : null
    if (local) {
      book.value = local.book
      chapters.value = local.chapters
      offlineMode.value = true
    } else {
      message.error(isNetworkError(e) ? t('当前离线，这本书还没有离线下载') : e.message)
    }
  } finally {
    loadOfflineIndexes()
    loading.value = false
  }
}

onMounted(load)
watch(id, load)

const shownChapters = computed(() => {
  let list = chapters.value
  const f = tocFilter.value.trim()
  if (f) list = list.filter((c) => c.title.includes(f))
  if (reverse.value) list = [...list].reverse()
  return list.slice(0, showCount.value)
})

const cachedCount = computed(() => chapters.value.filter((c) => c.cached).length)

// ---------------- 离线下载（保存到本机浏览器） ----------------

async function loadOfflineIndexes() {
  offlineIndexes.value = await offlineContentIndexes(id.value).catch(() => new Set<number>())
}

const readableCount = computed(() => chapters.value.filter((c) => !c.isVolume).length)
const offlineCount = computed(() => offlineIndexes.value.size)
const task = computed(() => offline.tasks.get(id.value))
const isOffline = computed(() => offline.bookIds.has(id.value))

const offlineOptions = computed(() => [
  { label: t('下载全部章节'), key: "all" },
  { label: t('从当前章起下载 50 章'), key: "50" },
  { label: t('从当前章起下载 200 章'), key: "200" },
  ...(isOffline.value ? [{ label: t('删除离线数据'), key: "delete" }] : []),
])

async function onOffline(key: string) {
  const b = book.value
  if (!b) return
  if (key === "delete") {
    dialog.warning({
      title: t('删除离线数据'),
      content: t('删除《{0}》保存在本机的 {1} 章？服务器上的书架和进度不受影响。', [b.name, offlineCount.value]),
      positiveText: t('删除'),
      negativeText: t('取消'),
      onPositiveClick: async () => {
        await offline.remove(b.id)
        await loadOfflineIndexes()
        message.success(t('已删除离线数据'))
      },
    })
    return
  }
  const range = key === "all" ? undefined : { start: b.durChapterIndex, count: Number(key) }
  try {
    const promise = offline.download(b.id, range)
    message.info(t('开始离线下载，可以离开本页，下载会在后台继续'))
    const task = await promise
    await loadOfflineIndexes()
    if (task.error) message.warning(t('离线下载停止：{0}（已完成 {1} 章）', [task.error, task.done]))
    else if (task.failed) message.warning(t('离线下载完成 {0} 章，{1} 章失败，可以再次下载重试', [task.done, task.failed]))
    else message.success(task.total ? t('离线下载完成，共 {0} 章', [task.done]) : t('这些章节已经下载过了'))
  } catch (e: any) {
    message.error(e.message)
  }
}

// 下载过程中定期刷新已下载数
watch(() => task.value?.done, (v, old) => {
  if (v !== undefined && (v % 20 === 0 || !task.value?.running) && v !== old) loadOfflineIndexes()
})

const intro = computed(() =>
  (book.value?.customIntro || book.value?.intro || t('暂无简介'))
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean),
)

const kinds = computed(() =>
  (book.value?.kind || '')
    .split(/[,，\n]/)
    .map((s) => s.trim())
    .filter(Boolean),
)

function read(index?: number) {
  if (!book.value) return
  router.push({ name: book.value.isVideo ? 'watch' : book.value.isAudio ? 'listen' : 'read', params: { id: book.value.id }, query: index !== undefined ? { index } : {} })
}

async function toggleShelf() {
  if (!book.value) return
  const nb = await shelfApi.update(book.value.id, { inShelf: !book.value.inShelf })
  book.value = { ...book.value, ...nb }
  message.success(nb.inShelf ? t('已加入书架') : t('已移出书架'))
}

async function refresh(info = false) {
  if (!book.value) return
  refreshing.value = true
  try {
    book.value = await shelfApi.refresh(book.value.id, info)
    chapters.value = await shelfApi.chapters(book.value.id)
    message.success(t('目录已更新，共 {0} 章', [chapters.value.length]))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    refreshing.value = false
  }
}

/** “开始阅读 / 继续收听”等按钮文字（整句翻译，英文语序不同） */
const readLabel = computed(() => {
  const b = book.value
  if (!b) return ''
  const cont = b.durChapterIndex > 0 || b.durChapterPos > 0
  if (b.isVideo) return cont ? t('继续观看') : t('开始观看')
  if (b.isAudio) return cont ? t('继续收听') : t('开始收听')
  return cont ? t('继续阅读') : t('开始阅读')
})

/** 本地 TXT：可以指定目录规则重新分章 */
/** PDF 的目录按页数生成（每 10 页一章），不需要重新解析 */
const isPdf = computed(() => isPdfBook(book.value))
const isLocalTxt = computed(() => !!book.value?.isLocal && book.value.bookUrl.toLowerCase().endsWith('.txt'))
const showTocRule = ref(false)
async function onTocApplied(nb: Book) {
  book.value = { ...book.value!, ...nb }
  chapters.value = await shelfApi.chapters(nb.id)
}

const moreOptions = computed(() =>
  book.value?.isAudio || book.value?.isVideo
    ? [
        { label: t('刷新详情和目录'), key: 'refreshInfo' },
        { label: t('编辑书籍信息'), key: 'edit' },
        { label: t('删除书籍'), key: 'delete' },
      ]
    : book.value?.isLocal
    ? [
        ...(isLocalTxt.value ? [{ label: t('TXT 目录规则'), key: 'txtToc' }] : []),
        { label: t('编辑书籍信息'), key: 'edit' },
        { label: t('删除书籍（同时删除上传的文件）'), key: 'delete' },
      ]
    : [
        { label: t('刷新详情和目录'), key: 'refreshInfo' },
        { label: t('服务器缓存后续 50 章'), key: 'cache50' },
        { label: t('服务器缓存全部章节'), key: 'cacheAll' },
        { label: t('清除服务器缓存'), key: 'clearCache' },
        { label: t('编辑书籍信息'), key: 'edit' },
        { label: t('删除书籍'), key: 'delete' },
      ],
)

async function onMore(key: string) {
  const b = book.value
  if (!b) return
  try {
    if (key === 'refreshInfo') await refresh(true)
    if (key === 'cache50') {
      await shelfApi.cache(b.id, b.durChapterIndex, 50)
      message.success(t('已开始后台缓存'))
    }
    if (key === 'cacheAll') {
      await shelfApi.cache(b.id, 0, 200)
      message.success(t('已开始后台缓存（每次最多 200 章）'))
    }
    if (key === 'clearCache') {
      await shelfApi.clearCache(b.id)
      chapters.value.forEach((c) => (c.cached = false))
      message.success(t('缓存已清除'))
    }
    if (key === 'txtToc') showTocRule.value = true
    if (key === 'edit') openEdit()
    if (key === 'delete') {
      dialog.warning({
        title: t('删除书籍'),
        content: t('确定删除《{0}》？', [b.name]),
        positiveText: t('删除'),
        negativeText: t('取消'),
        onPositiveClick: async () => {
          await shelfApi.remove(b.id)
          router.replace('/')
        },
      })
    }
  } catch (e: any) {
    message.error(e.message)
  }
}

// 编辑
const showEdit = ref(false)
const editForm = ref({ name: '', author: '', customCoverUrl: '', customIntro: '', groupId: 0, canUpdate: true })
function openEdit() {
  const b = book.value!
  editForm.value = {
    name: b.name,
    author: b.author,
    customCoverUrl: b.customCoverUrl || '',
    customIntro: b.customIntro || '',
    groupId: b.groupId,
    canUpdate: b.canUpdate,
  }
  showEdit.value = true
}
async function saveEdit() {
  book.value = await shelfApi.update(book.value!.id, editForm.value)
  showEdit.value = false
}
const groupOptions = computed(() => [{ label: t('未分组'), value: 0 }, ...groups.value.map((g) => ({ label: g.name, value: g.id }))])

function onChanged(nb: Book) {
  book.value = nb
  shelfApi.chapters(nb.id).then(async (c) => {
    chapters.value = c
    await offline.refreshBook(nb, c)
    loadOfflineIndexes()
  })
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <n-button quaternary circle @click="goBack(router, { name: 'shelf' })">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <h2>{{ t('书籍详情') }}</h2>
    </div>

    <div v-if="loading && !book" class="card head">
      <n-skeleton :width="120" :height="160" />
      <div style="flex: 1"><n-skeleton text :repeat="4" /></div>
    </div>

    <template v-if="book">
      <div class="card head">
        <book-cover :url="book.customCoverUrl || book.coverUrl" :name="book.name" :author="book.author" :origin="book.origin" :width="120" />
        <div class="info">
          <h1>{{ book.name }}</h1>
          <div class="muted">{{ book.author || t('佚名') }}</div>
          <div class="tags">
            <n-tag v-for="k in kinds" :key="k" size="small" :bordered="false">{{ k }}</n-tag>
            <n-tag v-if="book.wordCount" size="small" :bordered="false">{{ book.wordCount }}</n-tag>
          </div>
          <div v-if="book.isLocal" class="muted small">
            <n-tag size="small" :bordered="false" type="info">{{ t('本地') }}</n-tag> {{ book.originName }}
          </div>
          <div v-else class="muted small">
            {{ t('来源：') }}<b class="accent">{{ book.originName }}</b>
            <span v-if="!book.sourceId" style="color: #d03050">{{ t('（书源已删除，请换源）') }}</span>
          </div>
          <div v-if="!book.isLocal" class="muted small">{{ t('最新：{0} · 更新于 {1}', [book.latestChapterTitle || '-', formatTime(book.lastCheckTime)]) }}</div>
          <div class="muted small">
            {{ t('共 {0} {1}', [book.totalChapterNum, book.isAudio || book.isVideo ? t('集') : t('章')]) }}<template v-if="!book.isAudio && !book.isVideo"><template v-if="!book.isLocal"> {{ t('· 服务器已缓存 {0} 章', [cachedCount]) }}</template> {{ t('· 本机已下载 {0} 章', [offlineCount]) }}</template>
          </div>
          <div v-if="task?.running" class="offline-task">
            <n-progress type="line" :percentage="task.total ? Math.round((task.done / task.total) * 100) : 100" :height="6" style="flex: 1" />
            <span class="muted small">{{ t('离线下载 {0}/{1}', [task.done, task.total]) }}</span>
            <n-button size="tiny" quaternary @click="offline.cancel(book.id)">{{ t('取消') }}</n-button>
          </div>
          <n-alert v-if="offlineMode" type="info" :bordered="false" style="margin-top: 10px">
            {{ t('当前离线，显示的是本机保存的数据，只能阅读已下载的章节') }}
          </n-alert>
          <n-space style="margin-top: 10px">
            <n-button type="primary" @click="read()">
              <template #icon><n-icon :component="BookOutline" /></template>
              {{ readLabel }}
            </n-button>
            <n-button :disabled="offlineMode" @click="toggleShelf">{{ book.inShelf ? t('移出书架') : t('加入书架') }}</n-button>
            <n-dropdown v-if="!book.isAudio && !book.isVideo && !book.isImage" :options="offlineOptions" :disabled="offlineMode || task?.running" @select="onOffline">
              <n-button :disabled="offlineMode" :loading="task?.running">
                {{ offlineCount && offlineCount >= readableCount ? t('已离线') : t('离线下载') }}
              </n-button>
            </n-dropdown>
            <n-button-group>
              <n-button v-if="!book.isLocal" :disabled="offlineMode" @click="showChange = true">{{ t('换源') }}</n-button>
              <n-button v-if="!isPdf" :disabled="offlineMode" :loading="refreshing" @click="refresh(false)">{{ book.isLocal ? t('重新解析目录') : t('更新目录') }}</n-button>
            </n-button-group>
            <n-dropdown :options="moreOptions" :disabled="offlineMode" @select="onMore">
              <n-button quaternary circle :disabled="offlineMode">
                <template #icon><n-icon :component="EllipsisHorizontal" /></template>
              </n-button>
            </n-dropdown>
          </n-space>
          <div v-if="book.durChapterTitle" class="muted small" style="margin-top: 8px">
            {{ book.isVideo ? t('上次看到') : book.isAudio ? t('上次听到') : t('上次读到') }}：{{ book.durChapterTitle }}
          </div>
        </div>
      </div>

      <div class="card section">
        <h3>{{ t('简介') }}</h3>
        <p v-for="(p, i) in intro" :key="i" class="intro">{{ p }}</p>
      </div>

      <div class="card section">
        <div class="toc-head">
          <h3>{{ t('目录') }} <span class="muted small">{{ t('{0} 章', [chapters.length]) }}</span></h3>
          <n-input v-model:value="tocFilter" size="small" :placeholder="t('搜索章节')" clearable class="toc-filter" />
          <n-button size="small" quaternary @click="reverse = !reverse">
            <template #icon><n-icon :component="SwapVerticalOutline" /></template>
            {{ reverse ? t('倒序') : t('正序') }}
          </n-button>
        </div>
        <div class="toc">
          <a
            v-for="c in shownChapters"
            :key="c.index"
            class="toc-item ellipsis"
            :class="{ volume: c.isVolume, current: c.index === book.durChapterIndex, cached: c.cached }"
            @click="!c.isVolume && read(c.index)"
          >
            {{ c.title }}
            <span v-if="c.isVip && !c.isPay" class="vip">VIP</span>
          </a>
        </div>
        <n-button v-if="shownChapters.length < (tocFilter ? chapters.filter((c) => c.title.includes(tocFilter)).length : chapters.length)"
          block quaternary style="margin-top: 8px" @click="showCount += 500">
          {{ t('显示更多') }}
        </n-button>
      </div>
    </template>

    <change-source-modal v-model:show="showChange" :book="book" @changed="onChanged" />
    <txt-toc-rule-modal v-if="book && isLocalTxt" v-model:show="showTocRule" :book="book" @applied="onTocApplied" />

    <n-modal v-model:show="showEdit" preset="card" :title="t('编辑书籍')" style="max-width: 460px">
      <n-form label-placement="left" label-width="80">
        <n-form-item :label="t('书名')"><n-input v-model:value="editForm.name" /></n-form-item>
        <n-form-item :label="t('作者')"><n-input v-model:value="editForm.author" /></n-form-item>
        <n-form-item :label="t('分组')"><n-select v-model:value="editForm.groupId" :options="groupOptions" /></n-form-item>
        <n-form-item :label="t('封面地址')"><n-input v-model:value="editForm.customCoverUrl" :placeholder="t('留空使用书源封面')" /></n-form-item>
        <n-form-item :label="t('简介')"><n-input v-model:value="editForm.customIntro" type="textarea" :placeholder="t('留空使用书源简介')" /></n-form-item>
        <n-form-item :label="t('允许更新')"><n-switch v-model:value="editForm.canUpdate" /></n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEdit = false">{{ t('取消') }}</n-button>
          <n-button type="primary" @click="saveEdit">{{ t('保存') }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.offline-task {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  max-width: 420px;
}

.head {
  display: flex;
  gap: 20px;
  padding: 20px;
  margin-bottom: 16px;
}

.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info h1 {
  margin: 0;
  font-size: 22px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.small {
  font-size: 13px;
}

.accent {
  color: var(--app-accent);
}

.section {
  padding: 16px 20px;
  margin-bottom: 16px;
}

.section h3 {
  margin: 0 0 10px;
  font-size: 16px;
}

.intro {
  margin: 0 0 6px;
  text-indent: 2em;
  line-height: 1.7;
  font-size: 14px;
}

.toc-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

/* 标题不换行，窄屏时让搜索框缩短 */
.toc-head h3 {
  flex-shrink: 0;
  margin: 0 auto 0 0;
  white-space: nowrap;
}

.toc-filter {
  flex: 0 1 180px;
  min-width: 0;
}

.toc {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(240px, 100%), 1fr));
  gap: 0 16px;
}

.toc-item {
  display: block;
  padding: 9px 4px;
  font-size: 14px;
  border-bottom: 1px dashed var(--app-border);
  cursor: pointer;
  text-decoration: none;
}

.toc-item:hover {
  color: var(--app-accent);
}

.toc-item.volume {
  font-weight: 600;
  cursor: default;
  grid-column: 1 / -1;
  color: var(--app-text-2);
}

.toc-item.current {
  color: var(--app-accent);
  font-weight: 600;
}

.toc-item.cached::before {
  content: '●';
  font-size: 8px;
  color: #18a058;
  margin-right: 6px;
  vertical-align: middle;
}

.vip {
  font-size: 10px;
  color: #f0a020;
  margin-left: 4px;
}

@media (max-width: 767px) {
  .head {
    padding: 14px;
    gap: 14px;
  }

  .toc {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
