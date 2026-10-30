<script setup lang="ts">
/**
 * 书源脚本的“内置浏览器”和图片验证码（java.startBrowserAwait / java.getVerificationCode）。
 *
 * 服务器通过 SSE 推送请求，这里弹窗显示；用户点“完成”后把网页最终的地址和源码（或输入的验证码）交回服务器，
 * 等待中的书源脚本拿到结果继续执行（例如保存书源设置），与 App 的行为一致。
 *
 * 安全：书源的网页放在 sandbox iframe 中，且不给 allow-same-origin——页面运行在隔离的来源里，
 * 读不到本站的登录信息；网页内容通过注入的小脚本用 postMessage 交回。
 *
 * 书源生成的网页、书源自己网站的网址（服务器标记为 proxy）：网页由这里渲染，网页中的请求、链接和表单
 * 经服务器转发（带书源的 Cookie），避免隔离的 iframe 请求书源网站时跨域失败。
 */
import { computed, onBeforeUnmount, ref, watch, nextTick } from 'vue'
import { NButton, NDrawer, NDrawerContent, NInput, NModal, NSpace, useMessage } from 'naive-ui'
import { http, getClientId, getToken, proxyImage } from '@/api/http'
import CookieHelp from '@/components/CookieHelp.vue'
import { copyText } from '@/utils/media'
import { buildPageShim, decodeBase64Text, pageCharset, rewriteNavigation } from '@/utils/pageShim'
import { useAuthStore } from '@/stores/auth'
import { t } from '@/i18n'
import RemoteScreen from './RemoteScreen.vue'

interface BrowserEvent {
  type: 'browser' | 'captcha' | 'sheet' | 'close' | 'notice'
  token: string
  sourceName: string
  sourceUrl: string
  title: string
  url: string
  html: string | null
  config?: string | null
  /** 其他网站的网页：打开后书源是否会读取 Cookie（null 为无法判断） */
  needCookie?: boolean | null
  /** 网页可以在这里渲染，网页中的请求经服务器转发 */
  proxy?: boolean
  /** 网页在服务器上的浏览器里打开，这里显示实时画面（远程浏览器） */
  remote?: boolean
  /** 登录、变量设置中：先选择打开方式（远程浏览器 / 下载网页在本页显示） */
  choose?: boolean
}

/** 弹窗中的一个网页（跳转后叠在上一个网页上，返回时移除） */
interface FramePage {
  id: number
  token: string
  /** 网页实际地址（含 #hash） */
  url: string
  srcdoc: string
}

interface ProxyResponse {
  status: number
  url: string
  headers: Record<string, string>
  body: string
  cookie?: string
}

let pageSeq = 0

const auth = useAuthStore()
const message = useMessage()

const queue = ref<BrowserEvent[]>([])
const current = computed(() => queue.value[0] ?? null)
/** 远程浏览器处于电脑版：弹窗加宽 */
const remoteDesktop = ref(false)
const submitting = ref(false)
const captcha = ref('')
/** 其他网站的网页（如番茄登录）：用户手动填写登录后的 Cookie */
const cookie = ref('')

// ---------------- SSE ----------------

let es: EventSource | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null

function connect() {
  disconnect()
  const token = getToken()
  if (!token) return
  es = new EventSource(`/api/events?token=${encodeURIComponent(token)}&client=${encodeURIComponent(getClientId())}`)
  es.addEventListener('browser', (e) => {
    try {
      onEvent(JSON.parse((e as MessageEvent).data) as BrowserEvent)
    } catch {
      /* ignore */
    }
  })
  es.onerror = () => {
    // 断线后浏览器会自动重连；连接被关闭（例如登录过期）时稍后手动重连
    if (es?.readyState === EventSource.CLOSED) {
      disconnect()
      retryTimer = setTimeout(connect, 15_000)
    }
  }
}

function disconnect() {
  if (retryTimer) clearTimeout(retryTimer)
  retryTimer = null
  es?.close()
  es = null
}

watch(() => auth.token, (t) => (t ? connect() : disconnect()), { immediate: true })
onBeforeUnmount(disconnect)

/** 同一个书源的提示 10 秒内只显示一次（搜索时多个请求可能接连触发） */
const noticeShown = new Map<string, number>()

function onEvent(ev: BrowserEvent) {
  if (ev.type === 'notice') {
    const key = ev.sourceUrl + '|' + ev.title
    const now = Date.now()
    if (now - (noticeShown.get(key) ?? 0) < 10_000) return
    noticeShown.set(key, now)
    if (ev.title === 'remote-browser-required') {
      message.warning(
        t('「{0}」需要打开网页才能继续（如人机验证、登录），但服务器没有配置远程浏览器。请联系管理员在“系统设置”中设置浏览器调试地址并开启远程浏览器。', [ev.sourceName]),
        { duration: 8000, closable: true },
      )
    } else if (ev.title === 'remote-browser-unreachable') {
      message.error(
        t('「{0}」需要打开网页，但服务器上的远程浏览器无法连接：{1}。请联系管理员检查浏览器是否在运行、“系统设置”中的浏览器调试地址是否正确。', [ev.sourceName, ev.url]),
        { duration: 10000, closable: true },
      )
    }
    return
  }
  if (ev.type === 'sheet') {
    sheet.value = ev
    return
  }
  if (ev.type === 'close') {
    queue.value = queue.value.filter((q) => q.token !== ev.token)
    return
  }
  // 同一个 token 的新事件（选择打开方式后）替换原来的
  if (queue.value.some((q) => q.token === ev.token)) queue.value = queue.value.map((q) => (q.token === ev.token ? ev : q))
  else queue.value = [...queue.value, ev]
}

watch(current, (ev, old) => {
  // 同一个网页：只有从“选择打开方式”变成正式网页时才重新准备
  if (ev?.token === old?.token && !(old?.choose && !ev?.choose)) return
  remoteDesktop.value = false
  captcha.value = ''
  cookie.value = ''
  openedExternal.value = false
  cookieExpanded.value = false
  submitting.value = false
  stack.value = []
  proxyError.value = ''
})

// ---------------- 网页 ----------------

/** 注入到书源网页中的脚本：收到“收集”消息时返回当前地址和页面源码 */
const HELPER = `<script data-legado>(function(){window.addEventListener('message',function(e){var d=e.data;if(!d||d.__legado!=='collect')return;var html='';try{var c=document.documentElement.cloneNode(true);c.querySelectorAll('[data-legado]').forEach(function(n){n.remove()});html='<!DOCTYPE html>'+c.outerHTML}catch(x){}var url='';try{url=String(location.href)}catch(x){}parent.postMessage({__legado:'result',token:d.token,url:url,html:html},'*')})})();<\/script>`

function escapeAttr(s: string) {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
}

/** 在网页 <head> 开头注入 base（相对路径按网页地址解析）、收集脚本和请求转发脚本 */
function injectPage(html: string, url: string, helper: boolean, shim: boolean, cookieStr = '') {
  const http = /^https?:/i.test(url)
  const base = http ? url.split('#')[0] : ''
  // 网页自己的编码（GBK 网站等）：iframe 里的文档是 UTF-8，网页加载的脚本、ajax 请求没写编码时要按网页的编码解码
  const charset = pageCharset(html)
  const inject = (base ? `<base data-legado href="${escapeAttr(base)}">` : '') + (helper ? HELPER : '') + (shim ? buildPageShim(http ? url : '', cookieStr, charset) : '')
  if (shim) html = rewriteNavigation(html)
  if (charset) html = html.replace(/<script\b(?![^>]*\bcharset\s*=)([^>]*\bsrc\s*=)/gi, `<script charset="${charset}"$1`)
  if (!inject) return html
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head[^>]*>/i, (m) => m + inject)
  if (/<html[^>]*>/i.test(html)) return html.replace(/<html[^>]*>/i, (m) => m + '<head>' + inject + '</head>')
  return inject + html
}

/** 经服务器加载后叠加显示的网页（最后一个在最上面） */
const stack = ref<FramePage[]>([])
const proxyLoading = ref(false)
const proxyError = ref('')
const frameEls = new Map<number, HTMLIFrameElement>()
function setFrameEl(id: number, el: unknown) {
  if (el) frameEls.set(id, el as HTMLIFrameElement)
  else frameEls.delete(id)
}

/** 书源给出源码的网页（第一页） */
const basePage = computed<FramePage | null>(() => {
  const ev = current.value
  if (!ev || ev.type !== 'browser' || ev.html == null) return null
  return { id: 0, token: ev.token, url: ev.url, srcdoc: injectPage(ev.html, ev.url, true, !!ev.proxy) }
})

const pages = computed<FramePage[]>(() => {
  const ev = current.value
  if (!ev || ev.type !== 'browser') return []
  return [...(basePage.value ? [basePage.value] : []), ...stack.value.filter((p) => p.token === ev.token)]
})
const topPage = computed(() => pages.value[pages.value.length - 1] ?? null)
/** 显示在最上面的是经服务器加载的网页 */
const topProxied = computed(() => (topPage.value && topPage.value.id !== 0 ? topPage.value : null))

/** 网页中请求的转发 */
async function proxyFetch(token: string, req: Record<string, unknown>): Promise<ProxyResponse> {
  return http.post<ProxyResponse>(`/api/browser/${encodeURIComponent(token)}/fetch`, req)
}

/**
 * 经服务器加载网页后在这里显示。mode：push 叠在当前网页上（打开、跳转），replace 替换最上面的网页（刷新、location.replace）
 */
async function loadProxied(
  target: 'modal' | 'sheet', token: string, url: string,
  method = 'GET', body: string | null = null, contentType: string | null = null, mode: 'push' | 'replace' = 'push',
) {
  const loadingRef = target === 'modal' ? proxyLoading : sheetLoading
  loadingRef.value = true
  if (target === 'modal') proxyError.value = ''
  const hash = url.includes('#') ? url.slice(url.indexOf('#')) : ''
  try {
    const referer = (target === 'modal' ? topPage.value?.url : sheetProxied.value?.url) || undefined
    const r = await proxyFetch(token, {
      url: url.split('#')[0], method, body, headers: contentType ? { 'content-type': contentType } : {},
      referer: referer && /^https?:/i.test(referer) ? referer.split('#')[0] : undefined,
    })
    const type = r.headers['content-type'] || ''
    const text = decodeBase64Text(r.body, type)
    const html = /html|xml/i.test(type) || /^\s*</.test(text)
      ? text
      : `<pre style="white-space:pre-wrap;word-break:break-all">${text.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</pre>`
    // 服务器请求时不带 #hash，显示时补回（网页常从 location.hash 读取参数）
    const finalUrl = (r.url || url).split('#')[0] + hash
    const page: FramePage = { id: ++pageSeq, token, url: finalUrl, srcdoc: injectPage(html, finalUrl, target === 'modal', true, r.cookie || '') }
    if (target === 'modal') {
      if (current.value?.token !== token) return
      const own = stack.value.filter((x) => x.token === token)
      stack.value = mode === 'replace' && own.length ? [...own.slice(0, -1), page] : [...own, page]
    } else if (sheet.value?.token === token) {
      sheetProxied.value = page
    }
  } catch (e: any) {
    if (target === 'modal') proxyError.value = e.message
    else message.error(e.message)
  } finally {
    loadingRef.value = false
  }
}

/** 网页发来的消息：请求转发、跳转、返回、写 Cookie */
async function onFrameMessage(e: MessageEvent) {
  const d = e.data
  if (!d || !['fetch', 'navigate', 'back', 'cookie'].includes(d.__legado)) return
  let target: 'modal' | 'sheet'
  let ev: BrowserEvent | null
  let pageId = -1
  const modalPage = pages.value.find((pg) => frameEls.get(pg.id)?.contentWindow === e.source)
  if (modalPage) {
    target = 'modal'
    ev = current.value
    pageId = modalPage.id
  } else if (sheetFrame.value && e.source === sheetFrame.value.contentWindow) {
    target = 'sheet'
    ev = sheet.value
  } else return
  if (!ev?.proxy) return
  const win = e.source as Window
  const token = ev.token
  if (d.__legado === 'navigate') {
    const url = String(d.url || '')
    if (!/^https?:/i.test(url)) return
    loadProxied(target, token, url, String(d.method || 'GET'), d.body ?? null, d.contentType ?? null, d.replace ? 'replace' : 'push')
    return
  }
  if (d.__legado === 'back') {
    // 回到上一个网页（上一个网页一直保留着，状态不变）
    if (target === 'modal' && pageId === topPage.value?.id && pages.value.length > 1) {
      stack.value = stack.value.filter((x) => x.id !== pageId)
    }
    return
  }
  if (d.__legado === 'cookie') {
    http.post(`/api/browser/${encodeURIComponent(token)}/cookie`, { url: String(d.url || ''), cookie: String(d.cookie || '') }).catch(() => {})
    return
  }
  try {
    const res = await proxyFetch(token, d.req || {})
    win.postMessage({ __legado: 'fetchResult', id: d.id, res }, '*')
  } catch (err: any) {
    // 不是书源自己的网站（服务器拒绝转发）：让网页用浏览器原生请求
    if (err?.status === 403) win.postMessage({ __legado: 'fetchResult', id: d.id, res: { fallback: true } }, '*')
    else win.postMessage({ __legado: 'fetchResult', id: d.id, error: err?.message || t('请求失败') }, '*')
  }
}
window.addEventListener('message', onFrameMessage)
onBeforeUnmount(() => window.removeEventListener('message', onFrameMessage))

function reloadProxied() {
  const ev = current.value
  const top = topProxied.value
  if (ev && top) loadProxied('modal', ev.token, top.url, 'GET', null, null, 'replace')
}

/** 返回上一个网页（弹窗上方的按钮） */
function backProxied() {
  const top = topProxied.value
  if (top && pages.value.length > 1) stack.value = stack.value.filter((x) => x.id !== top.id)
}

function openProxiedInNewTab() {
  const u = topProxied.value?.url || current.value?.url
  if (u && /^https?:/i.test(u)) window.open(u, '_blank', 'noopener')
}

/**
 * 只有网址、没有源码：其他网站的网页（多为登录页）。
 * 不嵌入显示——大多数网站禁止被嵌入，且嵌入后登录也拿不到 Cookie；改为在新标签页打开，回来粘贴 Cookie。
 */
const externalUrl = computed(() => {
  const ev = current.value
  if (!ev || ev.type !== 'browser' || ev.remote || ev.html != null || !/^https?:/i.test(ev.url)) return null
  if (pages.value.length || proxyLoading.value) return null
  return ev.url
})

/** 书源生成的网页放在隔离的 iframe 中（不给 allow-same-origin，读不到本站数据） */
const sandbox = 'allow-scripts allow-forms allow-popups allow-modals'

const cookieInput = ref<InstanceType<typeof NInput> | null>(null)
/** 已在新标签页打开：切回本页时把光标放到 Cookie 输入框 */
const openedExternal = ref(false)
/** 无法判断是否需要 Cookie 时，用户手动展开输入框 */
const cookieExpanded = ref(false)
const showCookieInput = computed(() => current.value?.needCookie === true || cookieExpanded.value)

function openExternal() {
  if (!externalUrl.value) return
  window.open(externalUrl.value, '_blank', 'noopener')
  openedExternal.value = true
}

function onWindowFocus() {
  if (openedExternal.value && externalUrl.value && showCookieInput.value) nextTick(() => cookieInput.value?.focus())
}
window.addEventListener('focus', onWindowFocus)
onBeforeUnmount(() => window.removeEventListener('focus', onWindowFocus))

async function copyUrl() {
  if (!externalUrl.value) return
  ;(await copyText(externalUrl.value)) ? message.success(t('已复制链接')) : message.warning(t('复制失败，请手动复制'))
}

function collectPage(token: string): Promise<{ url: string; html: string }> {
  return new Promise((resolve) => {
    const top = topPage.value
    const win = top ? frameEls.get(top.id)?.contentWindow : null
    if (!win) return resolve({ url: '', html: '' })
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMsg)
      resolve({ url: '', html: '' })
    }, 1500)
    function onMsg(e: MessageEvent) {
      if (e.source !== win || e.data?.__legado !== 'result' || e.data.token !== token) return
      clearTimeout(timer)
      window.removeEventListener('message', onMsg)
      resolve({ url: String(e.data.url || ''), html: String(e.data.html || '') })
    }
    window.addEventListener('message', onMsg)
    win.postMessage({ __legado: 'collect', token }, '*')
  })
}

async function submit(body: { url?: string; body?: string; cancel?: boolean; cookie?: string }) {
  const ev = current.value
  if (!ev) return
  submitting.value = true
  try {
    await http.post(`/api/browser/${encodeURIComponent(ev.token)}`, body)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    // 服务器也会推送 close，这里先移除，避免重复提交
    queue.value = queue.value.filter((q) => q.token !== ev.token)
    submitting.value = false
  }
}

/** 选择打开方式：服务器随后推送正式的网页事件（同一个 token） */
const choosing = ref(false)
async function chooseMode(mode: 'remote' | 'page') {
  const ev = current.value
  if (!ev) return
  choosing.value = true
  try {
    await http.post(`/api/browser/${encodeURIComponent(ev.token)}/mode`, { mode })
  } catch (e: any) {
    message.error(e.message)
  } finally {
    choosing.value = false
  }
}

async function finish() {
  const ev = current.value
  if (!ev) return
  if (ev.type === 'captcha') {
    if (!captcha.value.trim()) return message.warning(t('请输入验证码'))
    return submit({ url: ev.url, body: captcha.value.trim() })
  }
  submitting.value = true
  // 远程浏览器：网址、源码和 Cookie 由服务器从浏览器取
  if (ev.remote) {
    await submit({})
    return
  }
  if (!topPage.value) {
    // 其他网站的网页读不到内容，只能交回手动填写的 Cookie（服务器在脚本继续前保存）
    await submit({ url: ev.url, body: '', cookie: (showCookieInput.value && cookie.value.trim()) || undefined })
    return
  }
  const page = await collectPage(ev.token)
  // 经服务器加载的网页：地址是 about:srcdoc，交回网页的实际地址
  // 经服务器加载的网页：地址是 about:srcdoc，交回网页的实际地址（带网页修改后的 #hash）
  const top = topPage.value
  const hash = page.url.includes('#') ? page.url.slice(page.url.indexOf('#')) : ''
  const pageUrl = page.url.startsWith('about:')
    ? top && /^https?:/i.test(top.url) ? top.url.split('#')[0] + hash : hash ? page.url : ''
    : page.url
  await submit({ url: pageUrl, body: page.html })
}

function cancel() {
  submit({ cancel: true })
}



// ---------------- 底部弹窗网页（java.showBrowser：段评、本章说等） ----------------

const sheet = ref<BrowserEvent | null>(null)
const sheetFrame = ref<HTMLIFrameElement | null>(null)
const sheetProxied = ref<FramePage | null>(null)
const sheetLoading = ref(false)
watch(sheet, () => (sheetProxied.value = null))

const sheetHeight = computed(() => {
  let pct = 0.8
  try {
    const c = sheet.value?.config ? JSON.parse(sheet.value.config) : null
    const v = Number(c?.heightPercentage)
    if (v > 0.2 && v <= 1) pct = v
  } catch {
    /* ignore */
  }
  return `${Math.round(pct * 100)}vh`
})

/** 其他网站的网址：直接加载（网页能读到自己的地址参数、使用自己的存储），与本站隔离 */
const sheetUrl = computed(() => {
  const u = sheet.value?.url
  if (!u || !/^https?:/i.test(u)) return null
  try {
    return new URL(u).origin !== location.origin ? u : null
  } catch {
    return null
  }
})

const sheetSrcdoc = computed(() => {
  const ev = sheet.value
  if (!ev) return null
  if (sheetProxied.value?.token === ev.token) return sheetProxied.value.srcdoc
  if (sheetUrl.value || ev.html == null) return null
  return injectPage(ev.html, ev.url, false, !!ev.proxy)
})

function closeSheet() {
  sheet.value = null
}

function openSheetInNewWindow() {
  if (sheet.value?.url && /^https?:/i.test(sheet.value.url)) window.open(sheet.value.url, '_blank', 'noopener')
}

const siteHost = computed(() => {
  try {
    return current.value ? new URL(current.value.url).hostname : ''
  } catch {
    return ''
  }
})

const captchaSrc = computed(() =>
  current.value?.type === 'captcha' ? proxyImage(current.value.url, { origin: current.value.sourceUrl }) : '',
)
</script>

<template>
  <n-modal
    :show="!!current"
    preset="card"
    :title="current ? `${current.title} · ${current.sourceName}` : ''"
    :style="current?.type === 'captcha' || current?.choose ? 'max-width: 460px' : current?.remote ? (remoteDesktop ? 'width: min(1100px, 100vw - 24px)' : 'width: min(480px, 100vw - 24px)') : externalUrl ? 'width: min(560px, 100vw - 24px)' : 'width: min(960px, 100vw - 24px)'"
    :mask-closable="false"
    :close-on-esc="false"
    :closable="false"
    :class="{ 'remote-modal': current?.remote }"
  >
    <div v-if="current?.type === 'browser' && current.choose" class="choose-mode">
      <p class="muted">{{ t('书源要打开网页：') }}<span class="mono">{{ current.url }}</span></p>
      <p>{{ t('请选择打开方式：') }}</p>
      <div class="choose-buttons">
        <n-button type="primary" :loading="choosing" @click="chooseMode('remote')">{{ t('远程浏览器') }}</n-button>
        <n-button :disabled="choosing" @click="chooseMode('page')">{{ t('新标签页打开') }}</n-button>
      </div>
      <p class="muted small">{{ t('远程浏览器：在服务器上的真实浏览器里打开网页，登录后的 Cookie 由服务器直接取回，适合人机验证、需要登录的网站。新标签页打开：在你自己的浏览器里打开网页，需要读取登录状态的书源要你手动粘贴 Cookie。') }}</p>
    </div>
    <remote-screen
      v-else-if="current?.type === 'browser' && current.remote"
      :key="current.token"
      :token="current.token"
      @mode="(d: boolean) => (remoteDesktop = d)"
    />
    <template v-else-if="current?.type === 'browser'">
      <div v-if="topProxied" class="proxy-bar">
        <n-button v-if="pages.length > 1" size="tiny" quaternary @click="backProxied">{{ t('返回') }}</n-button>
        <span class="mono ellipsis" :title="topProxied.url">{{ topProxied.url }}</span>
        <n-button size="tiny" quaternary :loading="proxyLoading" @click="reloadProxied">{{ t('刷新') }}</n-button>
        <n-button size="tiny" quaternary @click="openProxiedInNewTab">{{ t('在新标签页打开') }}</n-button>
      </div>
      <div v-if="proxyLoading && !pages.length" class="proxy-loading muted">{{ t('正在通过服务器加载网页…') }}</div>
      <div v-if="proxyError && pages.length" class="proxy-error">{{ t('加载失败：{0}', [proxyError]) }}</div>
      <div v-if="pages.length" class="frames">
        <iframe
          v-for="(pg, i) in pages"
          v-show="i === pages.length - 1"
          :key="pg.id + '-' + pg.token"
          :ref="(el) => setFrameEl(pg.id, el)"
          class="frame"
          :sandbox="sandbox"
          :srcdoc="pg.srcdoc"
          referrerpolicy="no-referrer"
        />
      </div>
      <template v-else>
        <div class="external">
          <!-- 打开网页后书源要读取 Cookie（如番茄登录） -->
          <template v-if="current.needCookie === true">
            <p class="external-tip">
              {{ t('书源需要你在') }} <b>{{ siteHost || t('该网站') }}</b> {{ t('登录，之后读取该网站的 Cookie。网页版读不到你浏览器里的 Cookie，请：') }}
            </p>
            <ol class="external-steps">
              <li>{{ t('点下方按钮在新标签页打开并完成登录；') }}</li>
              <li>{{ t('回到这里，把该网站的 Cookie 粘贴到输入框；') }}</li>
              <li>{{ t('点“完成”，书源会读取 Cookie 继续执行。') }}</li>
            </ol>
          </template>
          <!-- 只是打开网页（如用户后台），书源之后不读取任何内容 -->
          <p v-else class="external-tip">
            {{ t('书源要打开') }} <b>{{ siteHost || t('该网站') }}</b> {{ t('的网页。请在新标签页中打开并操作，完成后回到这里点“完成”。') }}
          </p>
          <div v-if="proxyError" class="proxy-error">{{ t('在本页加载失败：{0}', [proxyError]) }}</div>
          <div class="external-url">
            <n-button type="primary" @click="openExternal">{{ openedExternal ? t('再次打开') : t('在新标签页打开') }}</n-button>
            <span class="mono ellipsis" :title="externalUrl || ''">{{ externalUrl }}</span>
            <n-button size="tiny" quaternary @click="copyUrl">{{ t('复制链接') }}</n-button>
          </div>
        </div>
        <template v-if="showCookieInput">
          <n-input
            ref="cookieInput"
            v-model:value="cookie"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            :placeholder="t('{0} 的 Cookie，如 sessionid=xxx; uid=yyy', [siteHost || t('该网站')])"
            class="mono"
            style="margin-top: 8px"
          />
          <cookie-help :site="siteHost" />
        </template>
        <!-- 无法判断时不默认显示，需要时再展开 -->
        <n-button v-else-if="current.needCookie == null" text size="small" class="cookie-toggle" @click="cookieExpanded = true">
          {{ t('需要填写 Cookie？') }}
        </n-button>
      </template>
      <p v-if="pages.length" class="muted small" style="margin: 8px 0 0">{{ t('书源的网页，操作完成后点“完成”，书源会读取网页中的结果并保存。') }}</p>
    </template>

    <template v-else-if="current?.type === 'captcha'">
      <div class="captcha">
        <img :src="captchaSrc" :alt="t('验证码')" @click="($event.target as HTMLImageElement).src = captchaSrc + '&_=' + Date.now()" />
        <span class="muted small">{{ t('看不清？点击图片刷新') }}</span>
      </div>
      <n-input v-model:value="captcha" :placeholder="t('请输入验证码')" autofocus @keyup.enter="finish" />
    </template>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="submitting" @click="cancel">{{ t('取消') }}</n-button>
        <n-button v-if="!current?.choose" type="primary" :loading="submitting" @click="finish">{{ t('完成') }}</n-button>
      </n-space>
    </template>
  </n-modal>

  <n-drawer
    :show="!!sheet"
    placement="bottom"
    :height="sheetHeight"
    class="source-sheet"
    :auto-focus="false"
    @update:show="(v: boolean) => !v && closeSheet()"
  >
    <n-drawer-content :title="sheet?.title || t('书源网页')" closable body-content-style="padding: 0; height: 100%">
      <template #header>
        <div class="sheet-head">
          <span class="ellipsis">{{ sheet?.title || t('书源网页') }}</span>
          <n-button v-if="sheet && /^https?:/i.test(sheet.url)" size="tiny" quaternary @click="openSheetInNewWindow">{{ t('新窗口打开') }}</n-button>
        </div>
      </template>
      <iframe
        v-if="sheetUrl"
        :key="sheet!.token"
        ref="sheetFrame"
        class="sheet-frame"
        sandbox="allow-scripts allow-forms allow-popups allow-modals allow-same-origin"
        :src="sheetUrl"
        referrerpolicy="no-referrer"
      />
      <iframe
        v-else-if="sheetSrcdoc != null"
        :key="sheet!.token"
        ref="sheetFrame"
        class="sheet-frame"
        sandbox="allow-scripts allow-forms allow-popups allow-modals"
        :srcdoc="sheetSrcdoc"
        referrerpolicy="no-referrer"
      />
      <div v-else class="sheet-empty muted">{{ t('书源没有提供可显示的网页') }}</div>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.choose-mode p {
  margin: 0 0 10px;
  word-break: break-all;
}

.choose-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.choose-mode .small {
  font-size: 12.5px;
  margin: 0;
}

.frame {
  width: 100%;
  height: min(70vh, 720px);
  border: 1px solid rgba(128, 128, 128, 0.25);
  border-radius: 6px;
  background: #fff;
}

.proxy-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 12px;
  opacity: 0.8;
}

.proxy-bar .mono {
  flex: 1;
  min-width: 0;
}

.proxy-loading {
  height: min(70vh, 720px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.proxy-error {
  margin-bottom: 8px;
  color: #d03050;
  font-size: 12px;
}

.external {
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(128, 128, 128, 0.08);
  font-size: 13px;
}

.cookie-toggle {
  margin-top: 8px;
  opacity: 0.7;
}

.external-tip {
  margin: 0 0 6px;
}

.external-steps {
  margin: 0 0 10px;
  padding-left: 20px;
  line-height: 1.8;
}

.external-url {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.external-url .mono {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  opacity: 0.7;
}

.sheet-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}

.sheet-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.sheet-empty {
  padding: 40px;
  text-align: center;
}

.captcha {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
}

.captcha img {
  max-width: 100%;
  min-height: 40px;
  cursor: pointer;
  border-radius: 4px;
}

.small {
  font-size: 12px;
}
</style>
