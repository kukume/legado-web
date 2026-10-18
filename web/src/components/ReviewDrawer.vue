<script setup lang="ts">
/**
 * 书源原生段评（ruleReview）的评论列表：点击段落后的评论数打开。
 * 配色跟随阅读主题（由阅读页传入），电脑上居中限宽。
 */
import { computed, ref, watch } from 'vue'
import { NButton, NDrawer, NDrawerContent, NIcon, NSkeleton } from 'naive-ui'
import { ChatbubbleEllipsesOutline, ChevronDown, CloseOutline, ThumbsUpOutline } from '@vicons/ionicons5'
import { reviewApi, type ReviewItem } from '@/api'
import { proxyImage } from '@/api/http'
import { compactNumber, t } from '@/i18n'

const props = defineProps<{
  show: boolean
  bookId: number
  chapterIndex: number
  /** 段落序号（-1 为本章说） */
  para: number
  paraData?: string | null
  total: number
  /** 被评论的段落文字 */
  excerpt?: string
  /** 阅读主题配色 */
  theme?: { bg: string; text: string; panel: string }
}>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const items = ref<ReviewItem[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const cursor = ref<string | null>(null)
const hasMore = ref(false)
/** 已展开全部回复的评论 */
const expanded = ref(new Set<string>())
/** 通过回复接口加载的更多回复 */
const moreReplies = ref(new Map<string, { items: ReviewItem[]; page: number; done: boolean; loading: boolean }>())

const isChapter = computed(() => props.para === -1)

const vars = computed(() => ({
  '--rv-bg': props.theme?.panel ?? 'var(--n-color)',
  '--rv-text': props.theme?.text ?? 'var(--n-text-color)',
}))

function reset() {
  items.value = []
  page.value = 1
  cursor.value = null
  hasMore.value = false
  error.value = ''
  expanded.value = new Set()
  moreReplies.value = new Map()
}

async function load(next = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const p = next ? page.value + 1 : 1
    const r = await reviewApi.detail(props.bookId, props.chapterIndex, props.para, props.paraData, p, next ? cursor.value : null)
    items.value = next ? [...items.value, ...r.items] : r.items
    page.value = p
    cursor.value = r.nextCursor
    hasMore.value = r.hasMore
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.show, props.bookId, props.chapterIndex, props.para] as const,
  ([show]) => {
    if (!show) return
    reset()
    load()
  },
  { immediate: true },
)

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  if (hasMore.value && !loading.value && !error.value && el.scrollHeight - el.scrollTop - el.clientHeight < 240) load(true)
}

function key(item: ReviewItem, i: number) {
  return item.id || `i${i}`
}

function allReplies(item: ReviewItem, i: number) {
  return [...item.replies, ...(moreReplies.value.get(key(item, i))?.items ?? [])]
}

function visibleReplies(item: ReviewItem, i: number) {
  const all = allReplies(item, i)
  return expanded.value.has(key(item, i)) ? all : all.slice(0, 2)
}

function hiddenReplyCount(item: ReviewItem, i: number) {
  if (expanded.value.has(key(item, i))) return 0
  return Math.max(0, allReplies(item, i).length - 2)
}

/** 回复数大于已加载的回复时，可以通过回复接口继续加载（书源配置了 reviewQuoteUrl 时） */
function canLoadMoreReplies(item: ReviewItem, i: number) {
  if (!item.id || hiddenReplyCount(item, i) > 0) return false
  const state = moreReplies.value.get(key(item, i))
  if (state?.done) return false
  return (item.replyCount ?? 0) > allReplies(item, i).length
}

function expand(item: ReviewItem, i: number) {
  expanded.value = new Set(expanded.value).add(key(item, i))
}

async function loadReplies(item: ReviewItem, i: number) {
  const k = key(item, i)
  const state = moreReplies.value.get(k) ?? { items: [], page: 0, done: false, loading: false }
  if (state.loading) return
  state.loading = true
  moreReplies.value = new Map(moreReplies.value).set(k, state)
  try {
    const r = await reviewApi.replies(props.bookId, props.chapterIndex, props.para, item.id!, props.paraData, state.page + 1)
    const seen = new Set(allReplies(item, i).map((x) => x.id))
    const fresh = r.items.filter((x) => !x.id || !seen.has(x.id))
    state.items = [...state.items, ...fresh]
    state.page++
    state.done = !r.hasMore || fresh.length === 0
  } catch {
    state.done = true
  } finally {
    state.loading = false
    moreReplies.value = new Map(moreReplies.value).set(k, state)
    expanded.value = new Set(expanded.value).add(k)
  }
}

function img(url: string | null) {
  return url ? proxyImage(url, { bookId: props.bookId }) : ''
}

function fmtCount(n: number | null | undefined) {
  if (!n) return ''
  return compactNumber(n)
}

/** 头像加载失败时显示昵称首字 */
const brokenAvatars = ref(new Set<string>())
function onAvatarError(url: string) {
  brokenAvatars.value = new Set(brokenAvatars.value).add(url)
}

function close() {
  emit('update:show', false)
}
</script>

<template>
  <n-drawer
    :show="show"
    placement="bottom"
    height="min(80vh, 760px)"
    :auto-focus="false"
    class="review-drawer"
    :style="vars"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-drawer-content body-content-style="padding: 0; height: 100%" :native-scrollbar="true">
      <div class="sheet">
        <div class="grip" />
        <header class="top">
          <div class="title">
            <span class="kind">{{ isChapter ? t('本章说') : t('段评') }}</span>
            <span v-if="!isChapter" class="chip">{{ t('第 {0} 段', [para]) }}</span>
            <span class="total">{{ t('{0} 条评论', [fmtCount(total)]) }}</span>
          </div>
          <button class="close" :aria-label="t('关闭')" @click="close"><n-icon :component="CloseOutline" size="20" /></button>
        </header>
        <blockquote v-if="excerpt" class="quote">{{ excerpt }}</blockquote>

        <div class="list" @scroll.passive="onScroll">
          <article v-for="(item, i) in items" :key="key(item, i)" class="item">
            <img
              v-if="item.avatar && !brokenAvatars.has(item.avatar)"
              class="avatar"
              :src="img(item.avatar)"
              alt=""
              loading="lazy"
              @error="onAvatarError(item.avatar!)"
            />
            <div v-else class="avatar letter">{{ (item.name || '?').slice(0, 1) }}</div>
            <div class="body">
              <div class="meta">
                <span class="name">{{ item.name || t('匿名读者') }}</span>
                <span v-for="b in item.badges" :key="b" class="badge">{{ b }}</span>
              </div>
              <div class="content">{{ item.content }}</div>
              <img v-if="item.imageUrl" class="content-img" :src="img(item.imageUrl)" alt="" loading="lazy" />
              <audio v-if="item.audioUrl" class="content-audio" :src="item.audioUrl" controls preload="none" />
              <div class="actions">
                <span class="time">{{ item.time }}</span>
                <span class="spacer" />
                <span v-if="item.replyCount" class="act"><n-icon :component="ChatbubbleEllipsesOutline" />{{ fmtCount(item.replyCount) }}</span>
                <span class="act"><n-icon :component="ThumbsUpOutline" />{{ fmtCount(item.likeCount) || t('赞') }}</span>
              </div>

              <div v-if="allReplies(item, i).length" class="replies">
                <div v-for="(r, j) in visibleReplies(item, i)" :key="r.id || j" class="reply">
                  <span class="r-name">{{ r.name || t('匿名读者') }}</span>
                  <template v-if="r.replyToName">
                    <span class="r-to">{{ t('回复') }}</span><span class="r-name">{{ r.replyToName }}</span>
                  </template>
                  <span class="r-sep">：</span>
                  <span class="r-content">{{ r.content }}</span>
                </div>
                <button v-if="hiddenReplyCount(item, i)" class="more-replies" @click="expand(item, i)">
                  {{ t('展开 {0} 条回复', [hiddenReplyCount(item, i)]) }}<n-icon :component="ChevronDown" />
                </button>
                <button
                  v-else-if="canLoadMoreReplies(item, i)"
                  class="more-replies"
                  :disabled="moreReplies.get(key(item, i))?.loading"
                  @click="loadReplies(item, i)"
                >
                  {{ moreReplies.get(key(item, i))?.loading ? t('加载中…') : t('查看更多回复') }}<n-icon :component="ChevronDown" />
                </button>
              </div>
            </div>
          </article>

          <template v-if="loading">
            <div v-for="n in items.length ? 1 : 4" :key="`s${n}`" class="item skeleton">
              <n-skeleton circle width="36px" height="36px" />
              <div class="body">
                <n-skeleton text width="30%" />
                <n-skeleton text :repeat="2" />
                <n-skeleton text width="45%" />
              </div>
            </div>
          </template>
          <div v-else-if="error" class="end error">
            {{ t('加载失败：{0}', [error]) }}
            <n-button size="tiny" round @click="load(page > 1 && items.length > 0)">{{ t('重试') }}</n-button>
          </div>
          <div v-else-if="!items.length" class="end">{{ t('还没有评论') }}</div>
          <div v-else-if="!hasMore" class="end">{{ t('— 已经到底了 —') }}</div>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--rv-bg);
  color: var(--rv-text);
}

.grip {
  width: 36px;
  height: 4px;
  margin: 8px auto 0;
  border-radius: 2px;
  background: currentColor;
  opacity: 0.18;
  flex-shrink: 0;
}

.top {
  display: flex;
  align-items: center;
  padding: 6px 12px 6px 20px;
  flex-shrink: 0;
}

.title {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.kind {
  font-size: 17px;
  font-weight: 600;
}

.chip {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
  background: color-mix(in srgb, currentColor 10%, transparent);
}

.total {
  font-size: 12px;
  opacity: 0.55;
}

.close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
}

.close:hover {
  opacity: 1;
  background: color-mix(in srgb, currentColor 8%, transparent);
}

.quote {
  margin: 2px 20px 8px;
  padding: 6px 12px;
  border-left: 3px solid color-mix(in srgb, currentColor 30%, transparent);
  border-radius: 0 6px 6px 0;
  background: color-mix(in srgb, currentColor 5%, transparent);
  font-size: 13px;
  line-height: 1.6;
  opacity: 0.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex-shrink: 0;
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 0 20px 28px;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
}

.item {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid color-mix(in srgb, currentColor 8%, transparent);
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  background: color-mix(in srgb, currentColor 10%, transparent);
}

.avatar.letter {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  opacity: 0.7;
}

.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.name {
  font-size: 13px;
  opacity: 0.6;
}

.badge {
  font-size: 10px;
  line-height: 16px;
  padding: 0 5px;
  border-radius: 4px;
  color: #b7791f;
  background: rgba(221, 164, 72, 0.16);
}

.content {
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.content-img {
  display: block;
  max-width: min(260px, 100%);
  max-height: 260px;
  margin-top: 4px;
  border-radius: 8px;
}

.content-audio {
  max-width: 100%;
}

.actions {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  opacity: 0.5;
  margin-top: 2px;
}

.spacer {
  flex: 1;
}

.act {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.replies {
  margin-top: 6px;
  padding: 8px 12px;
  border-radius: 8px;
  background: color-mix(in srgb, currentColor 5%, transparent);
  font-size: 13px;
  line-height: 1.65;
}

.reply + .reply {
  margin-top: 4px;
}

.r-name {
  opacity: 0.65;
}

.r-to {
  margin: 0 4px;
  opacity: 0.45;
}

.r-sep {
  opacity: 0.65;
}

.r-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.more-replies {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font-size: 12px;
  opacity: 0.6;
  cursor: pointer;
}

.more-replies:hover {
  opacity: 0.9;
}

.skeleton {
  opacity: 0.6;
}

.end {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px 0 8px;
  font-size: 12px;
  opacity: 0.5;
}

.end.error {
  opacity: 1;
  color: #d03050;
}
</style>

<style>
/* 电脑上居中限宽、顶部圆角（抽屉挂在 body 下，需要非 scoped 样式） */
.n-drawer.review-drawer {
  max-width: 720px;
  margin: 0 auto;
  border-radius: 16px 16px 0 0;
  overflow: hidden;
}

.n-drawer.review-drawer .n-drawer-content,
.n-drawer.review-drawer .n-drawer-body,
.n-drawer.review-drawer .n-drawer-body-content-wrapper {
  background: var(--rv-bg);
}
</style>
