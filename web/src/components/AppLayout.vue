<script setup lang="ts">
import { computed, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NDropdown, NButton } from 'naive-ui'
import {
  LibraryOutline, SearchOutline, CompassOutline, CodeSlashOutline, PersonCircleOutline,
  ShieldCheckmarkOutline, SwapHorizontalOutline, MoonOutline, SunnyOutline, ContrastOutline,
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { t } from '@/i18n'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

interface NavItem {
  name: string
  label: string
  icon: Component
  mobile?: boolean
}

const nav = computed<NavItem[]>(() => {
  const items: NavItem[] = [
    { name: 'shelf', label: t('书架'), icon: LibraryOutline, mobile: true },
    { name: 'search', label: t('搜索'), icon: SearchOutline, mobile: true },
    { name: 'explore', label: t('发现'), icon: CompassOutline, mobile: true },
    { name: 'sources', label: t('书源'), icon: CodeSlashOutline, mobile: true },
    { name: 'rules', label: t('规则'), icon: SwapHorizontalOutline, mobile: true },
    { name: 'settings', label: t('我的'), icon: PersonCircleOutline, mobile: true },
  ]
  if (auth.isAdmin) items.push({ name: 'admin', label: t('管理'), icon: ShieldCheckmarkOutline })
  return items
})

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
          v-for="item in nav"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: activeName === item.name }"
        >
          <n-icon size="20"><component :is="item.icon" /></n-icon>
          <span>{{ item.label }}</span>
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
      <router-view v-slot="{ Component }">
        <keep-alive :include="['ShelfView', 'SearchView', 'ExploreView', 'SourcesView']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <nav class="bottom-nav">
      <router-link
        v-for="item in nav.filter((n) => n.mobile)"
        :key="item.name"
        :to="{ name: item.name }"
        class="bottom-item"
        :class="{ active: activeName === item.name }"
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

@media (max-width: 767px) {
  .sidebar {
    display: none;
  }

  .bottom-nav {
    display: flex;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 100;
    background: var(--app-surface);
    border-top: 1px solid var(--app-border);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .bottom-item {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 6px 0 5px;
    font-size: 11px;
    text-decoration: none;
    color: var(--app-text-2);
  }

  /* 6 个入口，英文标签较长时省略 */
  .bottom-item span {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .bottom-item.active {
    color: var(--app-accent);
  }
}
</style>
