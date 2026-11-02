<script setup lang="ts">
import { NIcon, NTooltip } from 'naive-ui'
import { InformationCircleOutline } from '@vicons/ionicons5'

/** 说明文字：只显示一个小图标，鼠标放上去（触摸屏点一下）才显示 */
const touch = typeof window !== 'undefined' && matchMedia('(pointer: coarse)').matches
</script>

<template>
  <n-tooltip :trigger="touch ? 'click' : 'hover'" placement="top" style="max-width: 320px">
    <template #trigger>
      <n-icon class="help-tip" :component="InformationCircleOutline" @click.stop />
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
