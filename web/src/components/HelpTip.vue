<script setup lang="ts">
import { ref } from 'vue'
import { NIcon, NTooltip } from 'naive-ui'
import { InformationCircleOutline } from '@vicons/ionicons5'

/** 说明文字：只显示一个小图标，鼠标放上去（触摸屏点一下）才显示 */
const touch = typeof window !== 'undefined' && matchMedia('(pointer: coarse)').matches

/** 气泡离屏幕边缘至少留这么宽 */
const EDGE = 12
const MAX_WIDTH = 320

const iconRef = ref<{ $el?: Element } | null>(null)
const placement = ref<'top' | 'top-start' | 'top-end'>('top')
const maxWidth = ref(MAX_WIDTH)

/**
 * 打开时按图标的位置决定气泡往哪边展开：居中放得下就居中；放不下时图标在右半边向左展开、在左半边向右展开，
 * 宽度不超过图标到屏幕边缘的距离。naive-ui 的气泡只会上下翻转、不会左右避让，
 * 手机上图标靠近屏幕边缘时气泡会超出屏幕，页面可以左右滑动
 */
function onShow(show: boolean) {
  if (!show) return
  const el = iconRef.value?.$el
  if (!(el instanceof Element)) return
  const r = el.getBoundingClientRect()
  const vw = document.documentElement.clientWidth
  const center = r.left + r.width / 2
  const width = Math.min(MAX_WIDTH, vw - EDGE * 2)
  if (center - width / 2 >= EDGE && center + width / 2 <= vw - EDGE) {
    placement.value = 'top'
    maxWidth.value = width
  } else if (center > vw / 2) {
    placement.value = 'top-end'
    maxWidth.value = Math.min(width, r.right - EDGE)
  } else {
    placement.value = 'top-start'
    maxWidth.value = Math.min(width, vw - r.left - EDGE)
  }
}
</script>

<template>
  <n-tooltip
    :trigger="touch ? 'click' : 'hover'"
    :placement="placement"
    arrow-point-to-center
    :style="{ maxWidth: `${maxWidth}px` }"
    @update:show="onShow"
  >
    <template #trigger>
      <n-icon ref="iconRef" class="help-tip" :component="InformationCircleOutline" @click.stop />
    </template>
    <slot />
  </n-tooltip>
</template>

<style scoped>
.help-tip {
  margin-left: 4px;
  font-size: 15px;
  vertical-align: -2px;
  color: var(--app-text-2);
  cursor: help;
}
.help-tip:hover {
  color: var(--app-text);
}
</style>
