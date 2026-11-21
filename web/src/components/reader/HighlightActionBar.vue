<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { t } from '@/i18n'

/** 点击手动标注后的操作（与 App 的标注菜单相同）：样式、备注、创建规则、复制、删除。x 为中点，y 为顶部 */
const props = defineProps<{ x: number; y: number }>()
defineEmits<{ style: []; note: []; rule: []; copy: []; remove: [] }>()

const el = ref<HTMLElement | null>(null)
const left = ref(props.x)
function clamp() {
  const half = (el.value?.offsetWidth ?? 0) / 2
  left.value = Math.min(window.innerWidth - half - 8, Math.max(half + 8, props.x))
}
onMounted(clamp)
watch(() => props.x, () => nextTick(clamp))
</script>

<template>
  <div ref="el" class="hl-bar" :style="{ left: `${left}px`, top: `${y}px` }" @pointerdown.stop @click.stop>
    <button @click="$emit('style')">{{ t('样式') }}</button>
    <button @click="$emit('note')">{{ t('备注') }}</button>
    <button @click="$emit('rule')">{{ t('创建规则') }}</button>
    <button @click="$emit('copy')">{{ t('复制') }}</button>
    <button class="danger" @click="$emit('remove')">{{ t('删除') }}</button>
  </div>
</template>

<style scoped>
.hl-bar {
  position: fixed;
  z-index: 30;
  display: flex;
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

.hl-bar button {
  flex-shrink: 0;
  padding: 8px 14px;
  border: none;
  background: none;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.hl-bar button + button {
  border-left: 1px solid rgba(255, 255, 255, 0.15);
}

.hl-bar button:hover {
  background: rgba(255, 255, 255, 0.12);
}

.hl-bar button.danger {
  color: #ff8a80;
}
</style>
