<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NDropdown, NButton, useNotification } from 'naive-ui'
import { ChevronBack, MoonOutline, SunnyOutline, ContrastOutline } from '@vicons/ionicons5'
import { useNavBars } from '@/composables/useNavBars'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useUpdateStore, versionName } from '@/stores/update'
import { useZlibStore } from '@/stores/zlib'
import { t } from '@/i18n'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const updates = useUpdateStore()
const notification = useNotification()

// 管理员打开网页时检查一次更新：有新版本时侧边栏“管理”上显示提示点，
// 并弹出一次通知（同一个新版本只提示一次，手机上没有“管理”入口也能看到）
const NOTIFIED_KEY = 'legado_update_notified'
watch(
  () => auth.isAdmin,
  async (admin) => {
    if (!admin) return
    await updates.autoCheck()
    const s = updates.status
    if (!s?.hasUpdate || !s.latestTime) return
    try {
      if (localStorage.getItem(NOTIFIED_KEY) === String(s.latestTime)) return
      localStorage.setItem(NOTIFIED_KEY, String(s.latestTime))
    } catch {
      /* 无法保存时每次打开都提示 */
    }
    const n = notification.info({
      title: t('发现新版本'),
      content: t('新版本 {0}，当前版本 {1}', [versionName(s.latestTime), versionName(s.buildTime)]),
      action: () =>
        h(NButton, { size: 'small', type: 'primary', onClick: () => { n.destroy(); router.push({ name: 'admin' }) } }, () => t('查看')),
      duration: 15000,
    })
  },
  { immediate: true },
)

// Z-Library 下载：打开网页时查一次（刷新页面前还在下载的任务继续跟踪），
// 下载完成 / 失败时在任何页面都弹出提示，完成的可以直接打开
const zlib = useZlibStore()
watch(
  () => auth.me?.zlibEnabled,
  (on) => {
    if (on) zlib.refresh()
  },
  { immediate: true },
)
const offZlib = zlib.onFinished((job) => {
  const title = job.title || t('未知书名')
  if (job.status === 'done') {
    const n = notification.success({
      title: t('已导入书架'),
      content: title,
      action:
        job.importedBookId == null
          ? undefined
          : () =>
              h(NButton, { size: 'small', type: 'primary', onClick: () => { n.destroy(); router.push({ name: 'book', params: { id: job.importedBookId! } }) } }, () => t('打开')),
      duration: 8000,
    })
  } else {
    notification.error({ title: t('下载失败'), content: job.error ? `${title}：${job.error}` : title, duration: 10000 })
  }
})
onUnmounted(offZlib)

// 导航入口：按“我的 → 导航栏”的设置显示或隐藏（见 useNavBars）
const { sideNav, bottomNav, hiddenHere } = useNavBars()

onMounted(() => {
  ui.loadNavLayout()
})

/**
 * 底栏选中项：点击时立即切换（高亮滑块马上滑过去，不等页面加载完），路由切换完成后以实际页面为准
 */
const tappedName = ref<string | null>(null)
// 跳转完成或被取消（如路由守卫拦下）都恢复为实际页面
const offAfterEach = router.afterEach(() => (tappedName.value = null))
onUnmounted(offAfterEach)
const bottomActive = computed(() => tappedName.value ?? activeName.value)
/** 底栏里选中项的位置（高亮滑块的位置）；当前页面不在底栏里时为 -1 */
const bottomIndex = computed(() => bottomNav.value.findIndex((i) => i.name === bottomActive.value))

/** 当前页面的入口没有显示在导航栏里（从“我的”进入的）：页面顶部显示返回“我的”的按钮 */
const showBack = computed(() => hiddenHere.value.some((i) => i.name === activeName.value))

function backToSettings() {
  // 从“我的”进来的直接后退（保留“我的”的滚动位置），否则跳转
  if ((window.history.state as { back?: string } | null)?.back === '/settings') router.back()
  else router.push({ name: 'settings' })
}

const activeName = computed(() => {
  const n = String(route.name ?? '')
  if (n === 'source-edit') return 'sources'
  if (n === 'book') return 'shelf'
  return n
})

const themeOptions = [
  { label: t('跟随系统'), key: 'auto' },
  { label: t('浅色'), key: 'light' },
  { label: t('深色'), key: 'dark' },
]
const themeIcon = computed(() =>
  ui.themeMode === 'auto' ? ContrastOutline : ui.themeMode === 'dark' ? MoonOutline : SunnyOutline,
)

function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand" @click="router.push('/')">
        <img src="/favicon.svg" alt="" />
        <span>{{ auth.siteName }}</span>
      </div>
      <nav>
        <router-link
          v-for="item in sideNav"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: activeName === item.name }"
        >
          <n-icon size="20"><component :is="item.icon" /></n-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.name === 'admin' && updates.hasUpdate" class="nav-dot" :title="t('发现新版本')" />
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <n-dropdown :options="themeOptions" trigger="click" @select="(k: any) => (ui.themeMode = k)">
          <n-button quaternary circle :render-icon="renderIcon(themeIcon)" :title="t('主题')" />
        </n-dropdown>
        <span class="muted user ellipsis">{{ auth.user?.username }}</span>
      </div>
    </aside>

    <main class="main">
      <div v-if="showBack" class="back-bar">
        <n-button text @click="backToSettings">
          <template #icon><n-icon :component="ChevronBack" /></template>
          {{ t('我的') }}
        </n-button>
      </div>
      <router-view v-slot="{ Component }">
        <keep-alive :include="['ShelfView', 'SearchView', 'ExploreView', 'SourcesView', 'RulesView', 'ZLibraryView']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <nav
      class="bottom-nav"
      :style="{ '--n': bottomNav.length, '--i': Math.max(bottomIndex, 0) }"
      :class="{ 'has-active': bottomIndex >= 0 }"
    >
      <!-- 选中项后面的高亮，切换时滑过去 -->
      <span class="bottom-pill" aria-hidden="true" />
      <router-link
        v-for="item in bottomNav"
        :key="item.name"
        :to="{ name: item.name }"
        class="bottom-item"
        :class="{ active: bottomActive === item.name }"
        @click="tappedName = item.name"
      >
        <n-icon size="22"><component :is="item.icon" /></n-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: 208px;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--app-border);
  background: var(--app-surface);
  padding: 16px 12px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  font-size: 17px;
  padding: 4px 8px 20px;
  cursor: pointer;
}

.brand img {
  width: 28px;
  height: 28px;
}

.sidebar nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  text-decoration: none;
  color: var(--app-text-2);
  font-size: 15px;
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
  color: var(--app-text);
}

.nav-dot {
  width: 7px;
  height: 7px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--app-accent);
}

.nav-item.active {
  background: color-mix(in srgb, var(--app-accent) 14%, transparent);
  color: var(--app-accent);
  font-weight: 600;
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border);
}

.user {
  flex: 1;
  font-size: 13px;
}

.main {
  flex: 1;
  min-width: 0;
  /*
   * 内容稍微超出屏幕（如封面角标）时，手机上整页会被拖着左右晃动。裁掉横向超出的部分：
   * clip 不会把 main 变成滚动容器（hidden 会），整页滚动、浏览器底栏自动收起都不受影响（iOS 16 起支持）
   */
  overflow-x: clip;
}

.bottom-nav {
  display: none;
}

/* 从“我的”进入的页面：返回“我的” */
.back-bar {
  padding: 12px 16px 0;
}

@media (max-width: 767px) {
  .sidebar {
    display: none;
  }

  /*
   * 悬浮的玻璃底栏（仿 iOS 26）：胶囊形状，不贴屏幕边缘；背后的内容模糊透出，边缘有高光；
   * 选中项后面的高亮滑块切换时带回弹滑过去。页面底部留白见 base.css 的 --bottom-nav-space
   */
  .bottom-nav {
    --pad: 5px;
    display: flex;
    position: fixed;
    left: 12px;
    right: 12px;
    bottom: max(10px, env(safe-area-inset-bottom) - 6px);
    z-index: 100;
    height: 60px;
    padding: var(--pad);
    border-radius: 30px;
    background: var(--glass-bg);
    -webkit-backdrop-filter: blur(22px) saturate(180%);
    backdrop-filter: blur(22px) saturate(180%);
    border: 1px solid var(--glass-edge);
    box-shadow: var(--glass-shadow), inset 0 1px 0 var(--glass-highlight);
  }

  /* 不支持背景模糊、或系统开了“降低透明度”：用不透明的底色，保证文字清楚 */
  @supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
    .bottom-nav {
      background: var(--app-surface);
    }
  }
  @media (prefers-reduced-transparency: reduce) {
    .bottom-nav {
      background: var(--app-surface);
      -webkit-backdrop-filter: none;
      backdrop-filter: none;
    }
  }

  .bottom-pill {
    position: absolute;
    top: var(--pad);
    bottom: var(--pad);
    left: var(--pad);
    width: calc((100% - 2 * var(--pad)) / var(--n));
    border-radius: 25px;
    background: color-mix(in srgb, var(--app-accent) 16%, transparent);
    box-shadow: inset 0 1px 0 var(--glass-highlight);
    transform: translateX(calc(var(--i) * 100%));
    opacity: 0;
    transition:
      transform 0.45s cubic-bezier(0.34, 1.4, 0.64, 1),
      opacity 0.2s;
  }

  .bottom-nav.has-active .bottom-pill {
    opacity: 1;
  }

  .bottom-item {
    position: relative;
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 1px;
    border-radius: 25px;
    font-size: 10.5px;
    text-decoration: none;
    color: var(--app-text-2);
    -webkit-tap-highlight-color: transparent;
    transition:
      color 0.2s,
      transform 0.2s cubic-bezier(0.34, 1.4, 0.64, 1);
  }

  /* 按下时轻轻缩小，松开回弹 */
  .bottom-item:active {
    transform: scale(0.9);
  }

  /* 入口较多（最多 7 个）、英文标签较长时省略 */
  .bottom-item span {
    max-width: 100%;
    padding: 0 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .bottom-item.active {
    color: var(--app-accent);
    font-weight: 600;
  }

  @media (prefers-reduced-motion: reduce) {
    .bottom-pill,
    .bottom-item {
      transition: none;
    }
  }
}
</style>
