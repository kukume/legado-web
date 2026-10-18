import type { Paragraph } from '@/utils/content'

/** 待保存的书签（选中文字后点“书签”） */
export interface BookmarkDraft {
  chapterIndex: number
  chapterName: string
  chapterPos: number
  bookText: string
  content: string
}

/** 一章正文（滚动模式下同时显示多章，翻页模式下只有当前章） */
export interface Segment {
  index: number
  title: string
  paras: Paragraph[]
  error?: string
  loading?: boolean
}
