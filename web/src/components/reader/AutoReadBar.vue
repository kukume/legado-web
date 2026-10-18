<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { AddOutline, CloseOutline, PauseOutline, PlayOutline, RemoveOutline } from '@vicons/ionicons5'
import { t } from '@/i18n'

/** 自动阅读时底部的控制条：暂停 / 继续、调速、停止 */
defineProps<{
  paused: boolean
  label: string
  /** 翻页模式：本页停留时间的进度（0-100），滚动模式为 null */
  progress: number | null
}>()
defineEmits<{
  togglePause: []
  slower: []
  faster: []
  stop: []
}>()
</script>

<template>
  <div class="auto-bar" @click.stop>
    <div v-if="progress !== null" class="auto-progress" :style="{ width: `${progress}%` }" />
    <button :title="paused ? t('继续') : t('暂停')" @click="$emit('togglePause')">
      <n-icon :component="paused ? PlayOutline : PauseOutline" size="18" />
    </button>
    <button :title="t('慢一点')" @click="$emit('slower')"><n-icon :component="RemoveOutline" size="18" /></button>
    <span class="auto-speed">{{ label }}</span>
    <button :title="t('快一点')" @click="$emit('faster')"><n-icon :component="AddOutline" size="18" /></button>
    <button :title="t('停止自动阅读')" @click="$emit('stop')"><n-icon :component="CloseOutline" size="18" /></button>
  </div>
</template>

<style scoped>
.auto-bar {
  position: fixed;
  left: 50%;
  bottom: calc(16px + env(safe-area-inset-bottom));
  z-index: 25;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 6px;
  overflow: hidden;
  transform: translateX(-50%);
  border-radius: 20px;
  background: rgba(32, 32, 32, 0.82);
  color: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  user-select: none;
  -webkit-user-select: none;
}

.auto-bar button {
  display: flex;
  padding: 6px;
  border: none;
  border-radius: 50%;
  background: none;
  color: inherit;
  cursor: pointer;
}

.auto-bar button:hover {
  background: rgba(255, 255, 255, 0.15);
}

.auto-speed {
  min-width: 76px;
  font-size: 12px;
  text-align: center;
  white-space: nowrap;
}

/* 翻页模式：本页停留时间的进度 */
.auto-progress {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 2px;
  background: rgba(255, 255, 255, 0.6);
}
</style>
