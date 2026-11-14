<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton, NIcon, NInput, NInputGroup, NEmpty, NModal, NDrawer, NDrawerContent, NSelect,
  NProgress, NTag, NSpin, NSkeleton, useMessage, useDialog,
} from 'naive-ui'
import {
  SearchOutline, CloudDownloadOutline, LogInOutline, LogOutOutline,
  CheckmarkCircle, AlertCircleOutline,
} from '@vicons/ionicons5'
import { zlibApi } from '@/api'
import type { ZlibStatus, ZlibBook, ZlibJob, ZlibAccount } from '@/api/types'
import { t } from '@/i18n'

defineOptions({ name: 'ZLibraryView' })

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const status = ref<ZlibStatus | null>(null)
const loadingStatus = ref(true)

const keyword = ref('')
const order = ref('popular')
const searching = ref(false)
const results = ref<ZlibBook[]>([])
const searched = ref(false)

const jobs = ref<ZlibJob[]>([])
const showDownloads = ref(false)
const downloadingIds = ref<Set<string>>(new Set())

const showLogin = ref(false)
const loginForm = ref({ email: '', password: '' })
const loggingIn = ref(false)

// 已登录时联网获取账号额度（已用 / 额度、重置倒计时）
const account = ref<ZlibAccount | null>(null)
const loadingAccount = ref(false)
async function loadAccount() {
  if (!status.value?.loggedIn) {
    account.value = null
    return
  }
  loadingAccount.value = true
  try {
    account.value = (await zlibApi.account()).account
    // 会话已失效：刷新状态变回未登录
    if (!account.value) await loadStatus()
  } catch {
    /* 额度获取失败不影响使用 */
  } finally {
    loadingAccount.value = false
  }
}

/** 重置倒计时：把站点的英文原文（如 "5 hours 30 minutes"）换成中文 */
function resetText(s: string): string {
  return s
    .replace(/\bhours?\b/gi, '小时')
    .replace(/\bminutes?\b/gi, '分钟')
    .replace(/\bdays?\b/gi, '天')
    .replace(/\band\b/gi, ' ')
    .replace(/less than\s*/i, '不到 ')
    .replace(/\s+/g, ' ')
    .trim()
}

const orderOptions = [
  { label: t('最热门'), value: 'popular' },
  { label: t('最匹配'), value: 'bestmatch' },
  { label: t('最新'), value: 'date' },
  { label: t('年份'), value: 'year' },
  { label: t('文件大小'), value: 'filesize' },
]

const activeCount = computed(
  () => jobs.value.filter((j) => j.status === 'queued' || j.status === 'downloading' || j.status === 'importing').length,
)

async function loadStatus() {
  loadingStatus.value = true
  try {
    status.value = await zlibApi.config()
    loadAccount()
  } catch (e: any) {
    message.error(e.message || t('加载失败'))
  } finally {
    loadingStatus.value = false
  }
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) return
  searching.value = true
  searched.value = true
  try {
    results.value = await zlibApi.search({ q, order: order.value, ext: ['EPUB'] })
  } catch (e: any) {
    message.error(e.message || t('搜索失败'))
    results.value = []
  } finally {
    searching.value = false
  }
}

async function download(book: ZlibBook) {
  downloadingIds.value.add(book.id)
  try {
    const job = await zlibApi.download(book)
    jobs.value = [job, ...jobs.value.filter((j) => j.id !== job.id)]
    showDownloads.value = true
    startPolling()
    message.success(t('已开始下载，完成后自动导入书架'))
  } catch (e: any) {
    message.error(e.message || t('下载失败'))
  } finally {
    downloadingIds.value.delete(book.id)
  }
}

let timer: number | null = null
async function pollJobs() {
  const wasActive = activeCount.value > 0
  try {
    jobs.value = await zlibApi.jobs()
  } catch {
    /* 忽略一次轮询失败 */
  }
  if (activeCount.value === 0) {
    stopPolling()
    // 下载结束后刷新已用额度
    if (wasActive && status.value?.loggedIn) loadAccount()
  }
}
function startPolling() {
  if (timer != null) return
  timer = window.setInterval(pollJobs, 1500)
}
function stopPolling() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

async function doLogin() {
  if (!loginForm.value.email.trim() || !loginForm.value.password) {
    message.warning(t('请输入邮箱和密码'))
    return
  }
  loggingIn.value = true
  try {
    await zlibApi.login(loginForm.value.email.trim(), loginForm.value.password)
    showLogin.value = false
    loginForm.value = { email: '', password: '' }
    message.success(t('登录成功'))
    await loadStatus()
  } catch (e: any) {
    message.error(e.message || t('登录失败'))
  } finally {
    loggingIn.value = false
  }
}

function doLogout() {
  dialog.warning({
    title: t('退出登录'),
    content: t('确定要退出 Z-Library 登录吗？'),
    positiveText: t('退出登录'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      try {
        await zlibApi.logout()
        await loadStatus()
      } catch (e: any) {
        message.error(e.message || t('操作失败'))
      }
    },
  })
}

function openBook(job: ZlibJob) {
  if (job.importedBookId != null) router.push({ name: 'book', params: { id: job.importedBookId } })
}

function jobPercent(j: ZlibJob) {
  if (j.status === 'done') return 100
  if (j.totalBytes > 0) return Math.min(99, Math.round((j.receivedBytes / j.totalBytes) * 100))
  return 0
}
function fmtBytes(n: number) {
  if (n <= 0) return ''
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

onMounted(() => {
  loadStatus()
  pollJobs().then(() => {
    if (activeCount.value > 0) startPolling()
  })
})
onActivated(() => {
  if (activeCount.value > 0) startPolling()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<template>
  <div class="page">
    <header class="top">
      <h2 class="title">Z-Library</h2>
      <div class="actions">
        <template v-if="status?.enabled">
          <n-button v-if="status.loggedIn" size="small" tertiary @click="doLogout">
            <template #icon><n-icon :component="LogOutOutline" /></template>
            {{ status.account?.email || t('退出登录') }}
          </n-button>
          <n-button v-else size="small" type="primary" tertiary @click="showLogin = true">
            <template #icon><n-icon :component="LogInOutline" /></template>
            {{ t('登录') }}
          </n-button>
        </template>
        <n-button size="small" type="primary" tertiary @click="(showDownloads = true), pollJobs()">
          <template #icon><n-icon :component="CloudDownloadOutline" /></template>
          {{ t('下载记录') }}<span v-if="activeCount" class="badge">{{ activeCount }}</span>
        </n-button>
      </div>
    </header>

    <div v-if="loadingStatus" class="center"><n-spin /></div>

    <n-empty v-else-if="!status?.enabled" class="center" :description="t('管理员未开启 Z-Library 功能')" />

    <!-- 未登录：搜索和下载都需要登录 -->
    <div v-else-if="!status.loggedIn" class="notice card">
      <n-icon :component="AlertCircleOutline" size="22" />
      <div class="notice-body">
        <p class="notice-title">{{ t('使用 Z-Library 需要登录') }}</p>
        <p class="muted small">{{ t('搜索和下载都需要登录 Z-Library 账号') }}</p>
      </div>
      <n-button size="small" type="primary" @click="showLogin = true">{{ t('登录') }}</n-button>
    </div>

    <template v-else>
      <!-- 已登录：下载额度（已用 / 额度、重置倒计时）。先占位，接口返回后再填入 -->
      <div class="quota card">
        <div class="quota-head">
          <span class="quota-title">
            {{ t('今日下载') }}
            <template v-if="account">
              <span class="quota-num">{{ account.downloadsLimit ? `${account.downloadsToday} / ${account.downloadsLimit}` : account.downloadsToday }}</span>
              <n-tag v-if="account.premium" size="tiny" type="success" :bordered="false">{{ t('会员') }}</n-tag>
            </template>
            <n-skeleton v-else-if="loadingAccount" text style="width: 56px" />
            <span v-else class="muted small">—</span>
          </span>
          <span v-if="account?.downloadsReset" class="muted small">{{ t('{0} 后重置', [resetText(account.downloadsReset)]) }}</span>
          <span v-else-if="loadingAccount" class="muted small">{{ t('加载中…') }}</span>
        </div>
        <n-progress
          v-if="account && account.downloadsLimit"
          :percentage="Math.min(100, Math.round((account.downloadsToday / account.downloadsLimit) * 100))"
          :height="6"
          :show-indicator="false"
          :status="account.downloadsToday >= account.downloadsLimit ? 'error' : 'default'"
          style="margin-top: 8px"
        />
        <n-skeleton v-else-if="loadingAccount" :height="6" :sharp="false" style="margin-top: 8px" />
      </div>

      <n-input-group class="search-row">
        <n-input
          v-model:value="keyword"
          :placeholder="t('搜索书名、作者')"
          clearable
          @keyup.enter="doSearch"
        >
          <template #prefix><n-icon :component="SearchOutline" /></template>
        </n-input>
        <n-select v-model:value="order" :options="orderOptions" style="width: 120px" />
        <n-button type="primary" :loading="searching" @click="doSearch">{{ t('搜索') }}</n-button>
      </n-input-group>
      <p class="muted small hint">{{ t('仅搜索可自动导入书架的 EPUB；下载在后台进行，完成后自动加入书架') }}</p>

      <div v-if="searching" class="center"><n-spin /></div>
      <n-empty v-else-if="searched && !results.length" class="center" :description="t('没有找到结果')" />

      <div v-else class="results">
        <div v-for="b in results" :key="b.id" class="book card">
          <div class="book-main">
            <p class="book-title ellipsis-2">{{ b.title }}</p>
            <p class="muted small ellipsis">{{ b.author || t('未知作者') }}</p>
            <div class="meta">
              <n-tag v-if="b.extension" size="tiny" :bordered="false">{{ b.extension.toUpperCase() }}</n-tag>
              <span v-if="b.filesize" class="muted small">{{ b.filesize }}</span>
              <span v-if="b.year" class="muted small">{{ b.year }}</span>
              <span v-if="b.language" class="muted small">{{ b.language }}</span>
            </div>
          </div>
          <n-button
            size="small"
            type="primary"
            :loading="downloadingIds.has(b.id)"
            @click="download(b)"
          >
            <template #icon><n-icon :component="CloudDownloadOutline" /></template>
            {{ t('下载') }}
          </n-button>
        </div>
      </div>
    </template>

    <!-- 登录 -->
    <n-modal v-model:show="showLogin" preset="card" :title="t('登录 Z-Library')" style="max-width: 400px">
      <p class="muted small login-tip">{{ t('搜索和下载都需要登录 Z-Library') }}</p>
      <div class="form">
        <n-input v-model:value="loginForm.email" :placeholder="t('邮箱')" @keyup.enter="doLogin" />
        <n-input
          v-model:value="loginForm.password"
          type="password"
          show-password-on="click"
          :placeholder="t('密码')"
          @keyup.enter="doLogin"
        />
        <n-button type="primary" block :loading="loggingIn" @click="doLogin">{{ t('登录') }}</n-button>
      </div>
    </n-modal>

    <!-- 下载记录（小页面） -->
    <n-drawer v-model:show="showDownloads" :width="380" placement="right">
      <n-drawer-content :title="t('下载记录')" closable>
        <n-empty v-if="!jobs.length" :description="t('还没有下载任务')" style="margin-top: 40px" />
        <div v-else class="jobs">
          <div v-for="j in jobs" :key="j.id" class="job">
            <div class="job-head">
              <p class="job-title ellipsis-2">{{ j.title || j.bookId }}</p>
              <n-icon v-if="j.status === 'done'" :component="CheckmarkCircle" color="#18a058" size="18" />
              <n-icon v-else-if="j.status === 'error'" :component="AlertCircleOutline" color="#d03050" size="18" />
            </div>
            <p v-if="j.author" class="muted small ellipsis">{{ j.author }}</p>
            <n-progress
              v-if="j.status === 'downloading' || j.status === 'importing'"
              :percentage="jobPercent(j)"
              :height="6"
              :show-indicator="false"
              style="margin: 6px 0"
            />
            <div class="job-foot">
              <span class="muted small">
                <template v-if="j.status === 'queued'">{{ t('排队中…') }}</template>
                <template v-else-if="j.status === 'downloading'">{{ t('下载中') }} {{ fmtBytes(j.receivedBytes) }}</template>
                <template v-else-if="j.status === 'importing'">{{ t('导入中…') }}</template>
                <template v-else-if="j.status === 'done'">{{ t('已导入书架') }}</template>
                <template v-else>{{ j.error || t('失败') }}</template>
              </span>
              <n-button v-if="j.status === 'done'" size="tiny" text type="primary" @click="openBook(j)">
                {{ t('查看') }}
              </n-button>
            </div>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.page {
  max-width: 820px;
  margin: 0 auto;
  padding: 20px 16px 80px;
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.title {
  margin: 0;
  font-size: 20px;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.badge {
  margin-left: 6px;
  background: var(--app-accent);
  color: #fff;
  border-radius: 10px;
  padding: 0 6px;
  font-size: 11px;
}
.center {
  display: flex;
  justify-content: center;
  padding: 48px 0;
}
.card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 10px;
}
.notice {
  display: flex;
  gap: 12px;
  padding: 16px;
  align-items: center;
  margin-bottom: 12px;
}
.notice-body {
  flex: 1;
  min-width: 0;
}
.notice-title {
  margin: 0 0 4px;
  font-weight: 600;
}
.quota {
  padding: 12px 14px;
  margin-bottom: 12px;
}
.quota-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.quota-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.quota-num {
  font-weight: 600;
  color: var(--app-accent);
}
.search-row {
  display: flex;
}
.hint {
  margin: 8px 2px 16px;
}
.results {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.book {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}
.book-main {
  flex: 1;
  min-width: 0;
}
.book-title {
  margin: 0 0 2px;
  font-weight: 600;
  font-size: 15px;
}
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.muted {
  color: var(--app-text-2);
}
.small {
  font-size: 12px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ellipsis-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.login-tip {
  margin: 0 0 12px;
}
.jobs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.job {
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 10px 12px;
}
.job-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.job-title {
  margin: 0;
  font-weight: 600;
  font-size: 14px;
}
.job-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
}
</style>
