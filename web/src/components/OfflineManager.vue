<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NEmpty, NSpin, useDialog, useMessage } from 'naive-ui'
import { listOfflineBooks, offlineStats, type OfflineBook } from '@/offline/db'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const offline = useOfflineStore()

interface Row {
  rec: OfflineBook
  count: number
  size: number
}

const rows = ref<Row[]>([])
const loading = ref(false)
const usage = ref<{ usage: number; quota: number } | null>(null)
const persisted = ref<boolean | null>(null)

const totalSize = computed(() => rows.value.reduce((s, r) => s + r.size, 0))

function fmtSize(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

async function load() {
  loading.value = true
  try {
    const [books, stats] = await Promise.all([listOfflineBooks(), offlineStats()])
    rows.value = books
      .map((rec) => ({ rec, count: stats.get(rec.bookId)?.count ?? 0, size: stats.get(rec.bookId)?.size ?? 0 }))
      .sort((a, b) => b.size - a.size)
    if (navigator.storage?.estimate) {
      const e = await navigator.storage.estimate()
      usage.value = { usage: e.usage ?? 0, quota: e.quota ?? 0 }
    }
    if (navigator.storage?.persisted) persisted.value = await navigator.storage.persisted()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function requestPersist() {
  if (!navigator.storage?.persist) return message.warning(t('当前浏览器不支持'))
  persisted.value = await navigator.storage.persist()
  if (persisted.value) message.success(t('已开启，浏览器不会在空间不足时自动清理离线数据'))
  else message.warning(t('浏览器拒绝了请求。把网站添加到主屏幕（安装为应用）后再试，成功率更高'))
}

function remove(r: Row) {
  dialog.warning({
    title: t('删除离线数据'),
    content: t('删除《{0}》保存在本机的 {1} 章？', [r.rec.book.name, r.count]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await offline.remove(r.rec.bookId)
      await load()
    },
  })
}

function removeAll() {
  dialog.warning({
    title: t('清空离线数据'),
    content: t('删除本机保存的全部 {0} 本书？服务器上的书架和进度不受影响。', [rows.value.length]),
    positiveText: t('全部删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      for (const r of rows.value) await offline.remove(r.rec.bookId)
      await load()
    },
  })
}
</script>

<template>
  <div>
    <p class="muted small" style="margin-top: 0">
      {{ t('在书籍详情页点“离线下载”，把章节保存到这台设备，没有网络时也能阅读；离线期间的阅读进度会在联网后自动同步。') }}
    </p>
    <div class="summary small">
      <span>{{ t('离线书籍 {0} 本，约 {1}', [rows.length, fmtSize(totalSize)]) }}</span>
      <span v-if="usage" class="muted">{{ t('· 浏览器已用 {0} / 可用 {1}', [fmtSize(usage.usage), fmtSize(usage.quota)]) }}</span>
      <div style="flex: 1" />
      <template v-if="persisted !== null">
        <span v-if="persisted" class="muted">{{ t('已开启持久存储') }}</span>
        <n-button v-else size="tiny" @click="requestPersist">{{ t('防止浏览器自动清理') }}</n-button>
      </template>
      <n-button v-if="rows.length" size="tiny" type="error" quaternary @click="removeAll">{{ t('全部删除') }}</n-button>
    </div>
    <n-spin :show="loading">
      <n-empty v-if="!rows.length && !loading" :description="t('还没有离线下载的书')" size="small" style="padding: 16px 0" />
      <div v-for="r in rows" :key="r.rec.bookId" class="row">
        <div class="name ellipsis" @click="router.push({ name: 'book', params: { id: r.rec.bookId } })">
          {{ r.rec.book.name }}
          <span class="muted small">· {{ r.rec.book.author }}</span>
        </div>
        <span class="muted small nowrap">{{ t('{0}/{1} 章 · {2}', [r.count, r.rec.chapters.filter((c) => !c.isVolume).length, fmtSize(r.size)]) }}</span>
        <n-button size="tiny" quaternary type="error" @click="remove(r)">{{ t('删除') }}</n-button>
      </div>
    </n-spin>
  </div>
</template>

<style scoped>
.small {
  font-size: 13px;
}

.summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid rgba(128, 128, 128, 0.15);
}

.name {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.nowrap {
  white-space: nowrap;
}
</style>
