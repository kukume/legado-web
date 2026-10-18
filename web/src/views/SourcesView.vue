<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { mobileColumns, useSelectAll } from '@/utils/mobileTable'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NDataTable, NDropdown, NIcon, NInput, NModal, NSelect, NSpace, NSwitch, NTabs, NTabPane, NTag,
  NCheckbox, NUpload, NUploadDragger, NAlert, NEmpty, NTooltip,
  useDialog, useMessage, type DataTableColumns, type UploadFileInfo,
} from 'naive-ui'
import { AddOutline, CloudDownloadOutline, CloudUploadOutline, SearchOutline, KeyOutline, CreateOutline, TrashOutline, RefreshOutline } from '@vicons/ionicons5'
import SourceConfigModal from '@/components/SourceConfigModal.vue'
import { sourceApi, subscriptionApi } from '@/api'
import type { ImportResult, SourceSummary, Subscription } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { getToken } from '@/api/http'
import { locale } from '@/i18n'
import { downloadText, formatTime } from '@/utils/media'
import { t } from '@/i18n'

defineOptions({ name: 'SourcesView' })

const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const tab = ref('sources')
const sources = ref<SourceSummary[]>([])
const loading = ref(false)
const keyword = ref('')
const group = ref<string | null>(null)
const status = ref<string | null>(null)
const checked = ref<number[]>([])

const canAdd = computed(() => auth.me?.canAddSource ?? false)

async function load() {
  loading.value = true
  try {
    sources.value = await sourceApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  load()
  loadSubs()
})

const groups = computed(() => {
  const set = new Set<string>()
  sources.value.forEach((s) => s.bookSourceGroup?.split(/[,;，；]/).forEach((g) => g.trim() && set.add(g.trim())))
  return [...set].sort()
})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return sources.value.filter((s) => {
    if (k && !s.bookSourceName.toLowerCase().includes(k) && !s.bookSourceUrl.toLowerCase().includes(k)
      && !(s.bookSourceGroup || '').toLowerCase().includes(k)) return false
    if (group.value && !(s.bookSourceGroup || '').split(/[,;，；]/).map((g) => g.trim()).includes(group.value)) return false
    if (status.value === 'enabled' && !s.enabled) return false
    if (status.value === 'disabled' && s.enabled) return false
    if (status.value === 'login' && !s.hasLogin) return false
    if (status.value === 'explore' && !s.hasExplore) return false
    if (status.value === 'mine' && !s.owned) return false
    if (status.value === 'public' && !s.isPublic) return false
    return true
  })
})

/**
 * 分页：自己保存页码和每页条数。naive-ui 的表格在 pagination 里写了 pageSize 时由外部控制，
 * 不处理切换事件的话，选择“每页 N 条”不会生效
 */
const PAGE_SIZE_KEY = 'legado_sources_page_size'
const pageSizes = [20, 50, 100, 500]
const pagination = reactive({
  page: 1,
  pageSize: pageSizes.includes(Number(localStorage.getItem(PAGE_SIZE_KEY))) ? Number(localStorage.getItem(PAGE_SIZE_KEY)) : 50,
  showSizePicker: true,
  pageSizes,
  onChange: (page: number) => {
    pagination.page = page
  },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
    localStorage.setItem(PAGE_SIZE_KEY, String(size))
  },
})
// 筛选条件变化后回到第一页（否则可能停在不存在的页上）
watch([keyword, group, status], () => {
  pagination.page = 1
})

async function toggle(s: SourceSummary, v: boolean) {
  try {
    await sourceApi.batch([s.id], v ? 'enable' : 'disable')
    s.enabled = v
  } catch (e: any) {
    message.error(e.message)
  }
}

const configId = ref<number | null>(null)
const showConfig = ref(false)
function openConfig(s: SourceSummary) {
  configId.value = s.id
  showConfig.value = true
}

function remove(ids: number[]) {
  dialog.warning({
    title: t('删除书源'),
    content: t('确定删除选中的 {0} 个书源？', [ids.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      try {
        await sourceApi.batch(ids, 'delete')
        checked.value = []
        await load()
      } catch (e: any) {
        message.error(e.message)
      }
    },
  })
}

const typeNames = [t('文本'), t('音频'), t('图片'), t('文件')]

const isMobile = useIsMobile()
const selectAll = useSelectAll(filtered, checked, (s: SourceSummary) => s.id)

/** 手机上与 App 一样只显示多选、名称、启用和菜单 */
const mobileCols = computed(() =>
  mobileColumns<SourceSummary>({
    name: (s) => [
      h('span', s.bookSourceName),
      s.isPublic ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, () => t('公共')) : null,
      s.hasLogin ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, () => t('登录')) : null,
    ],
    sub: (s) => [s.bookSourceGroup, s.respondTime < 180000 ? `${(s.respondTime / 1000).toFixed(1)}s` : null, s.bookSourceUrl].filter(Boolean).join(' · '),
    enabled: (s) => s.enabled,
    onToggle: toggle,
    actions: (s) => [
      { key: 'edit', label: s.canEdit ? t('编辑 / 调试') : t('查看 / 调试'), onSelect: () => router.push({ name: 'source-edit', params: { id: s.id } }) },
      { key: 'config', label: t('登录 / 变量'), onSelect: () => openConfig(s) },
      ...(s.canEdit ? [{ key: 'delete', label: t('删除'), danger: true, onSelect: () => remove([s.id]) }] : []),
    ],
  }),
)

const columns = computed<DataTableColumns<SourceSummary>>(() => isMobile.value ? mobileCols.value : [
  { type: 'selection' },
  {
    title: t('启用'),
    key: 'enabled',
    width: 70,
    render: (s) => h(NSwitch, { size: 'small', value: s.enabled, onUpdateValue: (v: boolean) => toggle(s, v) }),
  },
  {
    title: t('书源'),
    key: 'bookSourceName',
    minWidth: 220,
    render: (s) =>
      h('div', { class: 'src-cell' }, [
        h('div', { class: 'src-name' }, [
          h('span', s.bookSourceName),
          s.isPublic ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, () => t('公共')) : null,
          s.hasLogin ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, () => t('登录')) : null,
          s.hasExplore ? h(NTag, { size: 'tiny', bordered: false }, () => t('发现')) : null,
          s.bookSourceType ? h(NTag, { size: 'tiny', bordered: false }, () => typeNames[s.bookSourceType] ?? '') : null,
        ]),
        h('div', { class: 'src-url muted ellipsis' }, s.bookSourceUrl),
      ]),
  },
  {
    title: t('分组'),
    key: 'bookSourceGroup',
    width: 140,
    ellipsis: { tooltip: true },
    render: (s) => s.bookSourceGroup || '-',
  },
  {
    title: t('响应'),
    key: 'respondTime',
    width: 80,
    sorter: (a, b) => a.respondTime - b.respondTime,
    render: (s) => (s.respondTime >= 180000 ? '-' : `${(s.respondTime / 1000).toFixed(1)}s`),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 132,
    render: (s) =>
      h(NSpace, { size: 4, wrap: false }, () => [
        h(NTooltip, null, {
          trigger: () => h(NButton, { size: 'tiny', quaternary: true, onClick: () => router.push({ name: 'source-edit', params: { id: s.id } }) },
            { icon: () => h(NIcon, null, () => h(CreateOutline)) }),
          default: () => (s.canEdit ? t('编辑 / 调试') : t('查看 / 调试')),
        }),
        h(NTooltip, null, {
          trigger: () => h(NButton, { size: 'tiny', quaternary: true, onClick: () => openConfig(s) },
            { icon: () => h(NIcon, null, () => h(KeyOutline)) }),
          default: () => t('登录 / 变量'),
        }),
        s.canEdit
          ? h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => remove([s.id]) },
            { icon: () => h(NIcon, null, () => h(TrashOutline)) })
          : null,
      ]),
  },
])

// ---------- 批量操作 ----------
const batchOptions = computed(() => [
  { label: t('启用'), key: 'enable' },
  { label: t('禁用'), key: 'disable' },
  { label: t('启用发现'), key: 'enableExplore' },
  { label: t('禁用发现'), key: 'disableExplore' },
  { label: t('添加分组'), key: 'addGroup' },
  { label: t('移除分组'), key: 'removeGroup' },
  ...(auth.isAdmin ? [{ label: t('设为公共'), key: 'setPublic' }, { label: t('设为私有'), key: 'setPrivate' }] : []),
  { label: t('导出选中'), key: 'export' },
  { label: t('删除'), key: 'delete' },
])

const groupInput = ref('')
const groupAction = ref<'addGroup' | 'removeGroup' | null>(null)

async function onBatch(key: string) {
  if (!checked.value.length) return message.warning(t('请先勾选书源'))
  if (key === 'delete') return remove(checked.value)
  if (key === 'export') return exportSources(checked.value)
  if (key === 'addGroup' || key === 'removeGroup') {
    groupAction.value = key
    groupInput.value = ''
    return
  }
  try {
    await sourceApi.batch(checked.value, key)
    message.success(t('操作成功'))
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

async function doGroupAction() {
  if (!groupAction.value || !groupInput.value.trim()) return
  await sourceApi.batch(checked.value, groupAction.value, groupInput.value.trim())
  groupAction.value = null
  await load()
}

async function exportSources(ids?: number[]) {
  const res = await fetch(sourceApi.exportUrl(ids), { headers: { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale } })
  if (!res.ok) return message.error(t('导出失败'))
  downloadText(`bookSource-${new Date().toISOString().slice(0, 10)}.json`, await res.text())
}

// ---------- 导入 ----------
const showImport = ref(false)
const importTab = ref('url')
const importUrl = ref('')
const importText = ref('')
const importPublic = ref(false)
const importing = ref(false)

function showResult(r: ImportResult) {
  const msg = t('新增 {0}，更新 {1}{2}', [r.added, r.updated, r.failed ? t('，失败 {0}', [r.failed]) : ''])
  if (r.failed) {
    dialog.warning({ title: t('导入完成'), content: msg + '\n' + r.errors.join('\n'), positiveText: t('好的') })
  } else {
    message.success(t('导入完成：{0}', [msg]))
  }
}

async function doImport() {
  importing.value = true
  try {
    let r: ImportResult
    if (importTab.value === 'url') {
      if (!importUrl.value.trim()) return message.warning(t('请输入链接'))
      r = await sourceApi.importUrl(importUrl.value.trim(), importPublic.value)
    } else {
      if (!importText.value.trim()) return message.warning(t('请粘贴书源 JSON'))
      r = await sourceApi.importText(importText.value, importPublic.value)
    }
    showResult(r)
    showImport.value = false
    importText.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    importing.value = false
  }
}

async function onFile(opts: { file: UploadFileInfo }) {
  const f = opts.file.file
  if (!f) return
  importText.value = await f.text()
  importTab.value = 'text'
}

// ---------- 订阅 ----------
const subs = ref<Subscription[]>([])
const showSub = ref(false)
const subForm = ref({ name: '', url: '', isPublic: false, autoUpdate: true })
const refreshingSub = ref<number | null>(null)

async function loadSubs() {
  try {
    subs.value = await subscriptionApi.list()
  } catch {
    /* ignore */
  }
}

async function addSub() {
  if (!subForm.value.url.trim()) return message.warning(t('请输入订阅链接'))
  try {
    await subscriptionApi.create(subForm.value)
    showSub.value = false
    subForm.value = { name: '', url: '', isPublic: false, autoUpdate: true }
    await Promise.all([loadSubs(), load()])
    message.success(t('订阅已添加并完成首次同步'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function refreshSub(s: Subscription) {
  refreshingSub.value = s.id
  try {
    showResult(await subscriptionApi.refresh(s.id))
    await Promise.all([loadSubs(), load()])
  } catch (e: any) {
    message.error(e.message)
  } finally {
    refreshingSub.value = null
  }
}

async function removeSub(s: Subscription) {
  await subscriptionApi.remove(s.id)
  await loadSubs()
}

const statusOptions = [
  { label: t('全部'), value: '' },
  { label: t('已启用'), value: 'enabled' },
  { label: t('已禁用'), value: 'disabled' },
  { label: t('需登录'), value: 'login' },
  { label: t('有发现'), value: 'explore' },
  { label: t('我的'), value: 'mine' },
  { label: t('公共'), value: 'public' },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>{{ t('书源管理') }}</h2>
    </div>

    <n-alert v-if="!canAdd" type="info" :bordered="false" style="margin-bottom: 12px">
      {{ t('管理员未开放普通用户添加书源，你可以使用管理员提供的公共书源，并可按需启用/禁用、配置登录信息。') }}
    </n-alert>

    <n-tabs v-model:value="tab" type="line">
      <n-tab-pane name="sources" :tab="t('书源 ({0})', [sources.length])">
        <div class="toolbar">
          <n-input v-model:value="keyword" :placeholder="t('搜索名称 / 地址 / 分组')" clearable size="small" style="width: 220px">
            <template #prefix><n-icon :component="SearchOutline" /></template>
          </n-input>
          <n-select v-model:value="group" :options="groups.map((g) => ({ label: g, value: g }))" :placeholder="t('分组')" clearable size="small" style="width: 130px" />
          <n-select v-model:value="status" :options="statusOptions" :placeholder="t('状态')" clearable size="small" style="width: 110px" />
          <div style="flex: 1" />
          <n-checkbox v-if="isMobile" class="select-all" :checked="selectAll.all.value" :indeterminate="selectAll.some.value" @update:checked="selectAll.toggleAll">{{ t('全选') }}</n-checkbox>
          <n-dropdown :options="batchOptions" @select="onBatch">
            <n-button size="small" :disabled="!checked.length">{{ t('批量操作 ({0})', [checked.length]) }}</n-button>
          </n-dropdown>
          <n-button size="small" @click="exportSources()">
            <template #icon><n-icon :component="CloudDownloadOutline" /></template>{{ t('导出') }}
          </n-button>
          <n-button v-if="canAdd" size="small" @click="showImport = true">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>{{ t('导入') }}
          </n-button>
          <n-button v-if="canAdd" size="small" type="primary" @click="router.push({ name: 'source-edit' })">
            <template #icon><n-icon :component="AddOutline" /></template>{{ t('新建') }}
          </n-button>
        </div>
        <n-data-table
          v-model:checked-row-keys="checked"
          :columns="columns"
          :data="filtered"
          :row-key="(r: SourceSummary) => r.id"
          :loading="loading"
          :pagination="pagination"
          size="small"
          :class="{ 'm-table': isMobile }"
          :scroll-x="isMobile ? undefined : 720"
          :table-layout="isMobile ? 'fixed' : 'auto'"
        />
      </n-tab-pane>

      <n-tab-pane name="subs" :tab="t('订阅 ({0})', [subs.length])">
        <div class="toolbar">
          <span class="muted small">{{ t('书源订阅会每天自动同步一次，也可以手动更新。') }}</span>
          <div style="flex: 1" />
          <n-button v-if="canAdd" size="small" type="primary" @click="showSub = true">
            <template #icon><n-icon :component="AddOutline" /></template>{{ t('添加订阅') }}
          </n-button>
        </div>
        <n-empty v-if="!subs.length" :description="t('暂无订阅')" style="padding: 40px 0" />
        <div v-for="s in subs" :key="s.id" class="sub card">
          <div class="sub-main">
            <div>
              <b>{{ s.name }}</b>
              <n-tag v-if="s.isPublic" size="tiny" type="info" :bordered="false" style="margin-left: 6px">{{ t('公共') }}</n-tag>
              <n-tag v-if="s.autoUpdate" size="tiny" :bordered="false" style="margin-left: 6px">{{ t('自动更新') }}</n-tag>
            </div>
            <div class="muted small ellipsis">{{ s.url }}</div>
            <div class="muted small">{{ formatTime(s.lastUpdateAt) }} · {{ s.lastResult || t('未同步') }}</div>
          </div>
          <n-space v-if="s.owned || auth.isAdmin">
            <n-button size="small" :loading="refreshingSub === s.id" @click="refreshSub(s)">
              <template #icon><n-icon :component="RefreshOutline" /></template>{{ t('更新') }}
            </n-button>
            <n-button size="small" type="error" ghost @click="removeSub(s)">{{ t('删除') }}</n-button>
          </n-space>
        </div>
      </n-tab-pane>
    </n-tabs>

    <!-- 导入 -->
    <n-modal v-model:show="showImport" preset="card" :title="t('导入书源')" style="max-width: 600px">
      <n-tabs v-model:value="importTab" type="segment">
        <n-tab-pane name="url" :tab="t('网络链接')">
          <n-input v-model:value="importUrl" placeholder="https://example.com/bookSource.json" />
          <p class="muted small">{{ t('支持阅读 3.0 书源 JSON（单个或数组）。需要定期同步请使用“订阅”。') }}</p>
        </n-tab-pane>
        <n-tab-pane name="text" :tab="t('粘贴 / 文件')">
          <n-upload :show-file-list="false" accept=".json,.txt" :custom-request="() => {}" @change="onFile">
            <n-upload-dragger style="padding: 12px">{{ t('点击或拖拽 JSON 文件到这里') }}</n-upload-dragger>
          </n-upload>
          <n-input v-model:value="importText" type="textarea" :autosize="{ minRows: 6, maxRows: 14 }" :placeholder="t('粘贴书源 JSON')" class="mono" style="margin-top: 10px" />
        </n-tab-pane>
      </n-tabs>
      <template #footer>
        <n-space justify="space-between" align="center">
          <n-checkbox v-if="auth.isAdmin" v-model:checked="importPublic">{{ t('设为公共书源（所有用户可用）') }}</n-checkbox>
          <span v-else />
          <n-button type="primary" :loading="importing" @click="doImport">{{ t('导入') }}</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 分组 -->
    <n-modal :show="!!groupAction" preset="dialog" :title="groupAction === 'addGroup' ? t('添加分组') : t('移除分组')"
      :positive-text="t('确定')" :negative-text="t('取消')" @positive-click="doGroupAction" @update:show="(v: boolean) => !v && (groupAction = null)">
      <n-input v-model:value="groupInput" :placeholder="t('分组名，多个用逗号分隔')" />
    </n-modal>

    <!-- 订阅 -->
    <n-modal v-model:show="showSub" preset="card" :title="t('添加书源订阅')" style="max-width: 480px">
      <n-space vertical>
        <n-input v-model:value="subForm.name" :placeholder="t('名称（可选）')" />
        <n-input v-model:value="subForm.url" :placeholder="t('订阅链接（返回书源 JSON）')" />
        <n-checkbox v-model:checked="subForm.autoUpdate">{{ t('每天自动更新') }}</n-checkbox>
        <n-checkbox v-if="auth.isAdmin" v-model:checked="subForm.isPublic">{{ t('导入为公共书源') }}</n-checkbox>
      </n-space>
      <template #footer>
        <n-space justify="end"><n-button type="primary" @click="addSub">{{ t('添加') }}</n-button></n-space>
      </template>
    </n-modal>

    <source-config-modal v-model:show="showConfig" :source-id="configId" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0 12px;
}

:deep(.src-name) {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
}

:deep(.src-url) {
  font-size: 12px;
  max-width: 360px;
}

.small {
  font-size: 12.5px;
}

.sub {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.sub-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
</style>
