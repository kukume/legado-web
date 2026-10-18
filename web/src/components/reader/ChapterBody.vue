<script setup lang="ts">
import { NButton, NSpin } from 'naive-ui'
import type { ContentImage } from '@/utils/content'
import { pdfPageOf } from '@/utils/pdf'
import PdfPage from './PdfPage.vue'
import type { Segment } from './types'
import { t } from '@/i18n'

/**
 * 一章的正文：章节名、加载中 / 失败提示、段落和图片、段评数量。
 *
 * 多个根节点（不包一层元素）：上下翻页按正文容器的直接子元素逐行计算分页，
 * 朗读、书签按 data-para="章-段" 查找段落。
 */
defineProps<{
  seg: Segment
  reviewCount: (index: number, pn: number) => number
  fmtReview: (n: number) => string
  imgSrc: (src: string) => string
  /** 滚动模式：图片懒加载、加载失败时显示“换源” */
  scrollMode?: boolean
  /** PDF 本地书：正文里的 pdfpage:N 由浏览器渲染 */
  pdfBookId?: number
}>()

defineEmits<{
  /** 打开段评（pn 为 -1 时是本章说） */
  review: [pn: number, text?: string]
  imgTap: [e: MouseEvent, img: ContentImage]
  /** 段落图片加载完成（翻页模式重新分页） */
  imgLoad: []
  reload: []
  changeSource: []
}>()
</script>

<template>
  <h2 class="chapter-title">{{ seg.title }}<span v-if="reviewCount(seg.index, -1)" class="review-badge title" @click.stop="$emit('review', -1)">{{ t('本章说 {0}', [fmtReview(reviewCount(seg.index, -1))]) }}</span></h2>
  <div v-if="seg.loading" class="center small-pad"><n-spin size="small" /></div>
  <div v-else-if="seg.error" class="error">
    <p>{{ t('加载失败：{0}', [seg.error]) }}</p>
    <n-button size="small" @click.stop="$emit('reload')">{{ t('重试') }}</n-button>
    <n-button v-if="scrollMode" size="small" @click.stop="$emit('changeSource')">{{ t('换源') }}</n-button>
  </div>
  <template v-for="(p, i) in seg.paras" :key="i">
    <p v-if="p.type === 'text'" class="para" :data-para="`${seg.index}-${i}`"><template v-if="p.parts"><template v-for="(part, j) in p.parts" :key="j"><template v-if="'text' in part">{{ part.text }}</template><img v-else class="inline-img" :class="{ clickable: part.img.clickable }" :src="imgSrc(part.img.display)" alt="" @click="$emit('imgTap', $event, part.img)" /></template></template><template v-else>{{ p.text }}</template><span v-if="p.last && reviewCount(seg.index, p.pn)" class="review-badge" @click.stop="$emit('review', p.pn, p.text)">{{ fmtReview(reviewCount(seg.index, p.pn)) }}</span></p>
    <pdf-page
      v-else-if="pdfBookId && pdfPageOf(p.img.display) !== null"
      :book-id="pdfBookId"
      :page="pdfPageOf(p.img.display)!"
      @load="$emit('imgLoad')"
    />
    <img
      v-else
      class="para-img"
      :class="{ full: p.img.style === 'full', clickable: p.img.clickable }"
      :src="imgSrc(p.img.display)"
      :loading="scrollMode ? 'lazy' : undefined"
      alt=""
      @load="$emit('imgLoad')"
      @click="$emit('imgTap', $event, p.img)"
    />
  </template>
</template>

<style scoped>
.chapter-title {
  font-size: 1.4em;
  font-weight: 700;
  margin: 0.4em 0 1.2em;
  line-height: 1.4;
  letter-spacing: 0.02em;
}

.center {
  display: flex;
  align-items: center;
  justify-content: center;
}

.small-pad {
  height: 120px;
}

.para {
  margin: 0 0 var(--r-para-gap);
  text-indent: var(--r-indent);
  text-align: justify;
  word-break: break-word;
}

/* 朗读中的段落（由朗读逻辑加上 class） */
.para.tts-active {
  background: rgba(255, 190, 0, 0.18);
  border-radius: 4px;
  box-shadow: 0 0 0 3px rgba(255, 190, 0, 0.18);
  transition: background 0.2s;
}

/* 不支持 CSS 高亮时：高亮书签所在的段落 */
.para.text-flash {
  animation: text-flash 2.2s ease;
}

@keyframes text-flash {
  0%, 60% {
    background-color: rgba(255, 190, 0, 0.35);
  }
  100% {
    background-color: transparent;
  }
}

/* 书源原生段评的评论数（与 App 一样显示在段尾） */
.review-badge {
  display: inline-block;
  margin-left: 0.35em;
  min-width: 1.6em;
  padding: 0 0.5em;
  font-size: 0.6em;
  line-height: 1.7;
  text-align: center;
  text-indent: 0;
  letter-spacing: 0;
  font-weight: normal;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: inherit;
  border-radius: 0.85em 0.85em 0.85em 0.25em;
  background: color-mix(in srgb, currentColor 9%, transparent);
  opacity: 0.6;
  vertical-align: 0.3em;
  cursor: pointer;
  user-select: none;
  transition: opacity 0.15s, background 0.15s;
}

.review-badge:hover {
  opacity: 1;
  background: color-mix(in srgb, currentColor 16%, transparent);
}

.review-badge.title {
  font-size: 0.48em;
  vertical-align: 0.4em;
  margin-left: 0.6em;
}

/* 段评气泡等与文字排在一起的小图 */
.inline-img {
  display: inline-block;
  /* 书源的气泡 SVG 往往四周留白较多，比文字稍大一些才看得清；不超过行高，不会撑高这一行 */
  height: min(1.5em, calc(var(--r-line-height) * 1em));
  width: auto;
  max-width: 4em;
  margin: 0 0 0 0.15em;
  vertical-align: -0.35em;
}

.inline-img.clickable,
.para-img.clickable {
  cursor: pointer;
}

.inline-img.clickable:hover,
.para-img.clickable:hover {
  opacity: 0.8;
}

.para-img {
  display: block;
  max-width: 100%;
  margin: 0.5em auto;
  border-radius: 4px;
}

.para-img.full {
  width: 100%;
}

.error {
  padding: 24px 0;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 15px;
}
</style>
