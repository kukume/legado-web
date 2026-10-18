<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { t } from '@/i18n'

/** 选中正文文字后的工具栏（位置由 useTextSelection 计算，x 为工具栏中点） */
const props = defineProps<{ x: number; y: number }>()
defineEmits<{
  /** 按下工具栏（不让选区因为点击而消失） */
  press: []
  copy: []
  bookmark: []
  replace: []
  dict: []
  /** 从选中处开始朗读 */
  speak: []
}>()

const el = ref<HTMLElement | null>(null)
/** 按工具栏实际宽度限制在屏幕内的中点位置（不同语言的按钮宽度不同） */
const left = ref(props.x)

function clamp() {
  const w = el.value?.offsetWidth ?? 0
  const half = w / 2
  left.value = Math.min(window.innerWidth - half - 8, Math.max(half + 8, props.x))
}

onMounted(clamp)
watch(() => props.x, () => nextTick(clamp))
</script>

<template>
  <div ref="el" class="sel-bar" :style="{ left: `${left}px`, top: `${y}px` }" @pointerdown.prevent="$emit('press')" @click.stop>
    <button @click="$emit('copy')">{{ t('复制') }}</button>
    <button @click="$emit('bookmark')">{{ t('书签') }}</button>
    <button @click="$emit('replace')">{{ t('替换') }}</button>
    <button @click="$emit('dict')">{{ t('字典') }}</button>
    <button @click="$emit('speak')">{{ t('朗读') }}</button>
  </div>
</template>

<style scoped>
.sel-bar {
  position: fixed;
  z-index: 30;
  display: flex;
  /* 固定定位的元素靠近屏幕右边时会被压窄，按钮就会竖着排；保持按钮所需的宽度 */
  width: max-content;
  white-space: nowrap;
  transform: translateX(-50%);
  border-radius: 8px;
  overflow: hidden;
  background: rgba(32, 32, 32, 0.92);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  user-select: none;
  -webkit-user-select: none;
}

.sel-bar button {
  flex-shrink: 0;
  padding: 8px 14px;
  border: none;
  background: none;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.sel-bar button + button {
  border-left: 1px solid rgba(255, 255, 255, 0.15);
}

.sel-bar button:hover {
  background: rgba(255, 255, 255, 0.12);
}
</style>
