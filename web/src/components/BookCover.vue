<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { proxyImage } from '@/api/http'

const props = defineProps<{
  url?: string | null
  name?: string
  author?: string
  sourceId?: number | null
  origin?: string
  width?: number
}>()

const failed = ref(false)
watch(() => props.url, () => (failed.value = false))

const src = computed(() =>
  props.url && !failed.value ? proxyImage(props.url, { sourceId: props.sourceId, origin: props.origin, cover: true }) : '',
)

/** 没有封面时根据书名生成一个稳定的底色 */
const hue = computed(() => {
  let h = 0
  for (const c of props.name || '') h = (h * 31 + c.charCodeAt(0)) % 360
  return h
})
</script>

<template>
  <div class="cover" :style="{ width: width ? width + 'px' : undefined }">
    <img v-if="src" :src="src" loading="lazy" alt="" @error="failed = true" />
    <div v-else class="placeholder" :style="{ background: `linear-gradient(160deg, hsl(${hue} 32% 46%), hsl(${(hue + 30) % 360} 36% 30%))` }">
      <span class="title">{{ name }}</span>
      <span v-if="author" class="author">{{ author }}</span>
    </div>
    <slot />
  </div>
</template>

<style scoped>
.cover {
  position: relative;
  aspect-ratio: 3 / 4;
  border-radius: 6px;
  overflow: hidden;
  background: var(--app-border);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
  flex-shrink: 0;
}

img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 10px;
  color: #fff;
  text-align: center;
  gap: 6px;
}

.title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

.author {
  font-size: 11px;
  opacity: 0.8;
}
</style>
