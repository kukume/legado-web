<script setup lang="ts">
import { NButton, NIcon, NSlider } from 'naive-ui'
import {
  ArrowBackOutline, ListOutline, SettingsOutline, RefreshOutline, SwapHorizontalOutline, InformationCircleOutline,
  ContractOutline, ExpandOutline, HeadsetOutline,
} from '@vicons/ionicons5'
import type { Book, Chapter } from '@/api/types'
import { t } from '@/i18n'

/** 阅读页点击屏幕中间呼出的顶部、底部菜单（颜色用阅读主题的 --r-panel / --r-text） */
defineProps<{
  visible: boolean
  book: Book | null
  chapters: Chapter[]
  curIndex: number
  curTitle: string
  progressPercent: string
  reloading: boolean
  isComic: boolean
  isFullscreen: boolean
  isNight: boolean
  /** 主题色块（夜间 / 日间按钮） */
  themeBg: string
}>()

defineEmits<{
  back: []
  reload: []
  changeSource: []
  info: []
  prevChapter: []
  nextChapter: []
  goChapter: [index: number]
  toc: []
  settings: []
  readAloud: []
  fullscreen: []
  night: []
}>()
</script>

<template>
  <transition name="slide-down">
    <header v-if="visible" class="topbar">
      <n-button quaternary circle @click="$emit('back')">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <div class="top-title">
        <div class="ellipsis">{{ book?.name }}</div>
        <div class="ellipsis sub">{{ curTitle }} · {{ book?.originName }}</div>
      </div>
      <n-button quaternary circle :title="reloading ? t('正在刷新…') : t('刷新本章')" :disabled="reloading" @click="$emit('reload')">
        <template #icon><n-icon :component="RefreshOutline" :class="{ spinning: reloading }" /></template>
      </n-button>
      <n-button quaternary circle :title="t('换源')" @click="$emit('changeSource')">
        <template #icon><n-icon :component="SwapHorizontalOutline" /></template>
      </n-button>
      <n-button quaternary circle :title="t('书籍详情')" @click="$emit('info')">
        <template #icon><n-icon :component="InformationCircleOutline" /></template>
      </n-button>
    </header>
  </transition>

  <transition name="slide-up">
    <footer v-if="visible" class="bottombar">
      <div class="progress-row">
        <n-button text :disabled="curIndex <= 0" @click="$emit('prevChapter')">{{ t('上一章') }}</n-button>
        <n-slider
          :value="curIndex"
          :min="0"
          :max="Math.max(0, chapters.length - 1)"
          :step="1"
          :format-tooltip="(v: number) => chapters[v]?.title ?? ''"
          style="flex: 1"
          @update:value="(v: number) => $emit('goChapter', v)"
        />
        <n-button text :disabled="curIndex >= chapters.length - 1" @click="$emit('nextChapter')">{{ t('下一章') }}</n-button>
      </div>
      <div class="tool-row">
        <button @click="$emit('toc')"><n-icon :component="ListOutline" size="22" /><span>{{ t('目录') }}</span></button>
        <button @click="$emit('settings')"><n-icon :component="SettingsOutline" size="22" /><span>{{ t('设置') }}</span></button>
        <button v-if="!isComic" @click="$emit('readAloud')"><n-icon :component="HeadsetOutline" size="22" /><span>{{ t('朗读') }}</span></button>
        <button @click="$emit('fullscreen')">
          <n-icon :component="isFullscreen ? ContractOutline : ExpandOutline" size="22" /><span>{{ isFullscreen ? t('退出全屏') : t('全屏') }}</span>
        </button>
        <button @click="$emit('night')">
          <span class="theme-dot" :style="{ background: themeBg }" /><span>{{ isNight ? t('日间') : t('夜间') }}</span>
        </button>
      </div>
      <div class="progress-text">{{ progressPercent }}% · {{ curIndex + 1 }}/{{ chapters.length }}</div>
    </footer>
  </transition>
</template>

<style scoped>
.topbar,
.bottombar {
  position: fixed;
  left: 0;
  right: 0;
  background: var(--r-panel);
  color: var(--r-text);
  z-index: 20;
  box-shadow: 0 0 12px rgba(0, 0, 0, 0.12);
}

.topbar {
  top: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  padding-top: calc(8px + env(safe-area-inset-top));
}

.top-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
}

.top-title .sub {
  font-size: 12px;
  font-weight: 400;
  opacity: 0.7;
}

/* 刷新本章时图标转圈 */
.spinning {
  animation: reader-spin 0.8s linear infinite;
}

@keyframes reader-spin {
  to {
    transform: rotate(360deg);
  }
}

.bottombar {
  bottom: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 720px;
  margin: 0 auto;
}

.tool-row {
  display: flex;
  justify-content: space-around;
  max-width: 520px;
  margin: 10px auto 4px;
}

.tool-row button {
  border: none;
  background: none;
  color: inherit;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 10px;
}

.theme-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid currentColor;
}

.progress-text {
  text-align: center;
  font-size: 12px;
  opacity: 0.6;
}

.slide-down-enter-active,
.slide-down-leave-active,
.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.2s ease, opacity 0.2s;
}

.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}

.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
  opacity: 0;
}
</style>
