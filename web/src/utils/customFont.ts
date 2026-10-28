import { fontApi } from '@/api'
import { customFontName } from '@/stores/reader'

/** 已加载（或正在加载）的字体，同一个字体只下载一次 */
const loading = new Map<number, Promise<boolean>>()

/**
 * 加载用户上传的字体并注册到页面（document.fonts），成功返回 true。
 * 字体文件由浏览器长期缓存，再次打开不会重新下载
 */
export function loadCustomFont(id: number): Promise<boolean> {
  let p = loading.get(id)
  if (!p) {
    p = fontApi
      .file(id)
      .then(async (buf) => {
        const face = new FontFace(customFontName(id), buf)
        await face.load()
        document.fonts.add(face)
        return true
      })
      .catch(() => {
        loading.delete(id)
        return false
      })
    loading.set(id, p)
  }
  return p
}
