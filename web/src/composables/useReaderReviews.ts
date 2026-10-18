import { reactive, ref, type Ref } from 'vue'
import { reviewApi, type ReviewSummary } from '@/api'
import type { Book } from '@/api/types'
import { useOfflineStore } from '@/stores/offline'
import { compactNumber } from '@/i18n'

/**
 * 段评（书源 ruleReview）：每章的段评数量、打开某一段的评论
 * onLoaded：某章的段评数量加载完成（数字会改变排版，翻页模式需要重新分页）
 */
export function useReaderReviews(opts: {
  book: Ref<Book | null>
  bookId: Ref<number>
  onLoaded: (index: number) => void
}) {
  const offline = useOfflineStore()
  const summaries = reactive(new Map<number, ReviewSummary>())
  const loading = new Set<number>()

  function loadReviewSummary(index: number) {
    if (!opts.book.value?.hasReview || !offline.online || summaries.has(index) || loading.has(index)) return
    loading.add(index)
    reviewApi
      .summary(opts.bookId.value, index)
      .then((r) => {
        summaries.set(index, r)
        if (summaries.size > 30) summaries.delete(summaries.keys().next().value!)
        opts.onLoaded(index)
      })
      .catch(() => {})
      .finally(() => loading.delete(index))
  }

  /** 第 index 章第 pn 段的评论数（pn 为 -1 时是本章说） */
  function reviewCount(index: number, pn: number): number {
    return summaries.get(index)?.counts[String(pn)] ?? 0
  }

  function fmtReview(n: number) {
    return n > 999 && n < 10000 ? '999+' : compactNumber(n)
  }

  const reviewTarget = ref<{ chapterIndex: number; para: number; paraData: string | null; total: number; excerpt: string } | null>(null)
  const reviewOpen = ref(false)

  function openReview(index: number, pn: number, text?: string) {
    const s = summaries.get(index)
    reviewTarget.value = {
      chapterIndex: index,
      para: pn,
      paraData: s?.keys[String(pn)] ?? null,
      total: reviewCount(index, pn),
      excerpt: (text ?? '').slice(0, 300),
    }
    reviewOpen.value = true
  }

  return { loadReviewSummary, reviewCount, fmtReview, reviewTarget, reviewOpen, openReview }
}
