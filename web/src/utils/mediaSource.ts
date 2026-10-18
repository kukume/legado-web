import { audioApi } from '@/api'
import { getToken } from '@/api/http'
import { t } from '@/i18n'

/**
 * 给 audio / video 元素加载章节的媒体（经服务器转发）。
 * m3u8 在不支持原生播放的浏览器中用 hls.js（按需加载）。返回释放资源的函数。
 */
export async function attachChapterMedia(
  el: HTMLMediaElement,
  bookId: number,
  index: number,
  refresh: boolean,
  onFatalError: () => void,
): Promise<() => void> {
  const info = await audioApi.info(bookId, index, refresh)
  const src = audioApi.src(bookId, index, refresh)
  if (info.hls && !el.canPlayType('application/vnd.apple.mpegurl')) {
    const { default: Hls } = await import('hls.js')
    if (!Hls.isSupported()) throw new Error(t('当前浏览器不支持播放 m3u8'))
    const hls = new Hls({
      xhrSetup: (xhr: XMLHttpRequest) => xhr.setRequestHeader('Authorization', `Bearer ${getToken()}`),
    })
    hls.on(Hls.Events.ERROR, (_: unknown, data: { fatal?: boolean }) => {
      if (data.fatal) onFatalError()
    })
    hls.loadSource(src)
    hls.attachMedia(el)
    return () => hls.destroy()
  }
  el.src = src
  el.load()
  return () => {
    el.removeAttribute('src')
    el.load()
  }
}
