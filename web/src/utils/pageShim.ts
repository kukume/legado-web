/**
 * 注入到书源网页（隔离的 iframe，来源为 null）中的脚本：
 * - fetch / XMLHttpRequest 发往 http(s) 地址的请求交给外层页面，由服务器转发（带书源的 Cookie），解决跨域；
 *   服务器拒绝转发（不是书源自己的网站）时退回浏览器原生请求
 * - 点击链接、提交表单、location.href / assign / replace 跳转：交给外层页面经服务器加载新网页，叠在当前网页上显示；
 *   history.back() 回到上一个网页（上一个网页一直保留，状态不丢，与 App 中 WebView 的返回相同）
 * - 网页地址中的 #hash 保留（网页常用 location.hash 传参），history.replaceState 改 hash 正常工作
 * - document.cookie 写入的 Cookie 保存到服务器上该网站的 Cookie 中（App 中 WebView 与书源共用 Cookie，书源会读取）
 *
 * 与外层页面的消息：
 *   → { __legado: 'fetch', id, req: { url, method, headers, body(Base64), referer } }
 *   ← { __legado: 'fetchResult', id, res: { status, url, headers, body(Base64) } | { fallback: true }, error }
 *   → { __legado: 'navigate', url, method, body, contentType, replace }
 *   → { __legado: 'back' }
 *   → { __legado: 'cookie', url, cookie }
 */
const SHIM = String.raw`(function(){
if (window.__legadoShim) return; window.__legadoShim = true;
var PAGE = window.__legadoPage || '';
var PAGE_NO_HASH = PAGE.split('#')[0];
var pending = {}, seq = 0;
function abs(u){ try { return new URL(String(u), document.baseURI).href } catch(e){ return String(u) } }
function isHttp(u){ return /^https?:/i.test(u) }
function call(req){ return new Promise(function(resolve, reject){ var id = ++seq; pending[id] = { resolve: resolve, reject: reject }; req.referer = PAGE_NO_HASH; parent.postMessage({ __legado: 'fetch', id: id, req: req }, '*') }) }
// ---- 地址中的 #hash、history ----
// 网页的实际地址是 about:srcdoc；有 <base> 时 replaceState('#x') 会按网页原地址解析而失败，这里统一改为只修改 hash
var SELF = String(location.href).split('#')[0];
var nativeReplace = history.replaceState.bind(history), nativePush = history.pushState.bind(history);
function hashOnly(u){ if (u == null) return u; u = String(u); var i = u.indexOf('#'); return SELF + (i >= 0 ? u.slice(i) : '') }
history.replaceState = function(st, t, u){ try { return nativeReplace(st, t, hashOnly(u)) } catch(e){} };
history.pushState = function(st, t, u){ try { return nativePush(st, t, hashOnly(u)) } catch(e){} };
if (PAGE.indexOf('#') >= 0) { try { nativeReplace(null, '', SELF + PAGE.slice(PAGE.indexOf('#'))) } catch(e){} }
history.back = function(){ parent.postMessage({ __legado: 'back' }, '*') };
var nativeGo = history.go.bind(history);
history.go = function(n){ n = Number(n) || 0; if (n < 0) parent.postMessage({ __legado: 'back' }, '*'); else if (n > 0) { try { nativeGo(n) } catch(e){} } else location.reload() };
// ---- 跳转 ----
function goTo(u, replace){
  u = String(u);
  if (u.charAt(0) === '#') { location.hash = u.slice(1); return }
  var a = abs(u);
  if (!isHttp(a)) { try { window.location.assign(u) } catch(e){} return }
  parent.postMessage({ __legado: 'navigate', url: a, method: 'GET', replace: !!replace }, '*');
}
window.__legadoNav = {
  get href(){ return PAGE_NO_HASH + location.hash }, set href(u){ goTo(u) },
  assign: function(u){ goTo(u) }, replace: function(u){ goTo(u, true) }, toString: function(){ return PAGE_NO_HASH + location.hash }
};
// 支持 Navigation API 的浏览器：拦截所有跳转（包括外部脚本中的 location.href = ...）
if (window.navigation && window.navigation.addEventListener) {
  window.navigation.addEventListener('navigate', function(e){
    try {
      if (!e.cancelable || e.hashChange || e.formData || (e.destination && e.destination.sameDocument)) return;
      var u = e.destination && e.destination.url; if (!u || !isHttp(u)) return;
      e.preventDefault(); goTo(u, e.navigationType === 'replace');
    } catch(x){}
  });
}
// ---- document.cookie ----
var jar = {};
String(window.__legadoCookie || '').split(';').forEach(function(p){ var i = p.indexOf('='); if (i > 0) jar[p.slice(0, i).trim()] = p.slice(i + 1).trim() });
try {
  Object.defineProperty(document, 'cookie', {
    configurable: true,
    get: function(){ return Object.keys(jar).map(function(k){ return k + '=' + jar[k] }).join('; ') },
    set: function(v){
      v = String(v); var pair = v.split(';')[0]; var i = pair.indexOf('='); if (i <= 0) return;
      var k = pair.slice(0, i).trim(), val = pair.slice(i + 1).trim();
      var expired = /max-ages*=s*(0|-)/i.test(v) || /expiress*=[^;]*1970/i.test(v);
      if (expired) delete jar[k]; else jar[k] = val;
      if (isHttp(PAGE_NO_HASH)) parent.postMessage({ __legado: 'cookie', url: PAGE_NO_HASH, cookie: k + '=' + (expired ? '' : val) }, '*');
    }
  });
} catch(e){}
window.addEventListener('message', function(e){ var d = e.data; if (!d || d.__legado !== 'fetchResult') return; var p = pending[d.id]; if (!p) return; delete pending[d.id]; if (d.error) p.reject(new TypeError(d.error)); else p.resolve(d.res) });
function b64ToBytes(b){ var s = atob(b || ''), a = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a }
function bufToB64(buf){ var b = new Uint8Array(buf), s = ''; for (var i = 0; i < b.length; i += 32768) s += String.fromCharCode.apply(null, b.subarray(i, i + 32768)); return btoa(s) }
function strToB64(str){ return bufToB64(new TextEncoder().encode(String(str))) }
function formToQuery(fd){ var u = new URLSearchParams(); fd.forEach(function(v, k){ if (typeof v === 'string') u.append(k, v) }); return u.toString() }
function bodyToB64(body){
  if (body == null) return Promise.resolve(null);
  if (typeof body === 'string') return Promise.resolve(strToB64(body));
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return Promise.resolve(strToB64(body.toString()));
  if (typeof FormData !== 'undefined' && body instanceof FormData) return Promise.resolve(strToB64(formToQuery(body)));
  if (typeof Blob !== 'undefined' && body instanceof Blob) return body.arrayBuffer().then(bufToB64);
  if (body instanceof ArrayBuffer) return Promise.resolve(bufToB64(body));
  if (ArrayBuffer.isView(body)) return Promise.resolve(bufToB64(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)));
  return Promise.resolve(strToB64(String(body)));
}
function defaultType(body){
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return 'application/x-www-form-urlencoded;charset=UTF-8';
  if (typeof FormData !== 'undefined' && body instanceof FormData) return 'application/x-www-form-urlencoded;charset=UTF-8';
  if (typeof body === 'string') return 'text/plain;charset=UTF-8';
  return null;
}
var nativeFetch = window.fetch ? window.fetch.bind(window) : null;
window.fetch = function(input, init){
  init = init || {};
  var isReq = typeof Request !== 'undefined' && input instanceof Request;
  var url = abs(isReq ? input.url : input);
  if (!isHttp(url)) return nativeFetch(input, init);
  var method = String(init.method || (isReq ? input.method : 'GET')).toUpperCase();
  var headers = {};
  new Headers(init.headers || (isReq ? input.headers : {})).forEach(function(v, k){ headers[k] = v });
  var body = init.body;
  var ready = body == null && isReq && method !== 'GET' && method !== 'HEAD' ? input.clone().arrayBuffer() : Promise.resolve(body);
  return ready.then(function(b){
    if (b != null && !headers['content-type']) { var t = defaultType(b); if (t) headers['content-type'] = t }
    return bodyToB64(b);
  }).then(function(b64){ return call({ url: url, method: method, headers: headers, body: b64 }) }).then(function(r){
    if (r.fallback) return nativeFetch(input, init);
    var noBody = r.status === 204 || r.status === 304 || method === 'HEAD';
    var resp = new Response(noBody ? null : b64ToBytes(r.body), { status: r.status < 200 ? 200 : r.status, headers: r.headers });
    try { Object.defineProperty(resp, 'url', { value: r.url }) } catch(e){}
    return resp;
  });
};
var NX = window.XMLHttpRequest;
function PX(){
  this._n = null; this._h = {}; this._l = {}; this._rh = {};
  this.readyState = 0; this.status = 0; this.statusText = ''; this.responseText = ''; this.response = null;
  this.responseURL = ''; this.responseType = ''; this.timeout = 0; this.withCredentials = false;
  this.upload = { addEventListener: function(){}, removeEventListener: function(){} };
}
PX.UNSENT = 0; PX.OPENED = 1; PX.HEADERS_RECEIVED = 2; PX.LOADING = 3; PX.DONE = 4;
PX.prototype.addEventListener = function(t, f){ (this._l[t] = this._l[t] || []).push(f) };
PX.prototype.removeEventListener = function(t, f){ var a = this._l[t]; if (a) this._l[t] = a.filter(function(x){ return x !== f }) };
PX.prototype._emit = function(t){ var ev = { type: t, target: this, currentTarget: this, lengthComputable: false, loaded: 0, total: 0 }; var h = this['on' + t]; if (typeof h === 'function') { try { h.call(this, ev) } catch(e){ setTimeout(function(){ throw e }) } } (this._l[t] || []).slice().forEach(function(f){ try { f.call(this, ev) } catch(e){ setTimeout(function(){ throw e }) } }, this) };
PX.prototype._state = function(s){ this.readyState = s; this._emit('readystatechange') };
PX.prototype.open = function(method, url, async){
  this._m = String(method || 'GET').toUpperCase(); this._u = abs(url); this._h = {}; this._aborted = false;
  if (!isHttp(this._u)) { var self = this; var n = this._n = new NX(); n.open(method, url, async !== false);
    ['readystatechange', 'load', 'error', 'loadend', 'abort', 'timeout', 'progress'].forEach(function(t){ n.addEventListener(t, function(){ self.readyState = n.readyState; try { self.status = n.status; self.statusText = n.statusText; self.responseURL = n.responseURL; if (n.readyState === 4) { self.response = n.response; if (n.responseType === '' || n.responseType === 'text') self.responseText = n.responseText } } catch(e){} self._emit(t) }) });
    return;
  }
  this._n = null; this._state(1);
};
PX.prototype.setRequestHeader = function(k, v){ if (this._n) return this._n.setRequestHeader(k, v); this._h[String(k).toLowerCase()] = String(v) };
PX.prototype.overrideMimeType = function(){};
PX.prototype.getResponseHeader = function(k){ if (this._n) return this._n.getResponseHeader(k); var v = this._rh[String(k).toLowerCase()]; return v == null ? null : v };
PX.prototype.getAllResponseHeaders = function(){ if (this._n) return this._n.getAllResponseHeaders(); var h = this._rh; return Object.keys(h).map(function(k){ return k + ': ' + h[k] }).join('\r\n') };
PX.prototype.abort = function(){ if (this._n) return this._n.abort(); this._aborted = true; this.readyState = 0; this._emit('abort'); this._emit('loadend') };
PX.prototype.send = function(body){
  if (this._n) { this._n.responseType = this.responseType; this._n.timeout = this.timeout; this._n.withCredentials = this.withCredentials; return this._n.send(body) }
  var self = this, headers = this._h;
  if (body != null && !headers['content-type']) { var t = defaultType(body); if (t) headers['content-type'] = t }
  var timer = this.timeout > 0 ? setTimeout(function(){ self._aborted = true; self.readyState = 4; self._emit('timeout'); self._emit('loadend') }, this.timeout) : null;
  bodyToB64(body).then(function(b64){ return call({ url: self._u, method: self._m, headers: headers, body: b64 }) }).then(function(r){
    if (timer) clearTimeout(timer);
    if (self._aborted) return;
    if (r.fallback) { self.status = 0; self.readyState = 4; self._emit('readystatechange'); self._emit('error'); self._emit('loadend'); return }
    self._rh = r.headers || {}; self.status = r.status; self.statusText = String(r.status); self.responseURL = r.url;
    self._state(2); self._state(3);
    var bytes = b64ToBytes(r.body), text = '';
    // 响应头没写编码时按网页的编码（GBK 网站的 ajax 接口常不写）
    var cs = (/charset=([\w-]+)/i.exec(self._rh['content-type'] || '') || [])[1] || window.__legadoCharset || 'utf-8';
    try { text = new TextDecoder(cs).decode(bytes) } catch(e){ try { text = new TextDecoder().decode(bytes) } catch(e2){} }
    var rt = self.responseType;
    if (rt === 'arraybuffer') self.response = bytes.buffer;
    else if (rt === 'blob') self.response = new Blob([bytes], { type: self._rh['content-type'] || '' });
    else if (rt === 'json') { try { self.response = JSON.parse(text) } catch(e){ self.response = null } }
    else { self.responseText = text; self.response = text }
    self._state(4); self._emit('load'); self._emit('loadend');
  }, function(){
    if (timer) clearTimeout(timer);
    if (self._aborted) return;
    self.status = 0; self._state(4); self._emit('error'); self._emit('loadend');
  });
};
window.XMLHttpRequest = PX;
function navigate(url, method, body, contentType){ parent.postMessage({ __legado: 'navigate', url: url, method: method || 'GET', body: body || null, contentType: contentType || null }, '*') }
document.addEventListener('click', function(e){
  if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return;
  var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
  if (!a || (a.target && a.target !== '_self') || a.hasAttribute('download')) return;
  var href = a.getAttribute('href') || '';
  if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) return;
  var u = abs(href); if (!isHttp(u)) return;
  e.preventDefault(); navigate(u, 'GET');
});
document.addEventListener('submit', function(e){
  var f = e.target; if (!f || f.tagName !== 'FORM' || e.defaultPrevented) return;
  if (f.target && f.target !== '_self') return;
  var action = abs(f.getAttribute('action') || PAGE || document.baseURI);
  if (!isHttp(action)) return;
  e.preventDefault();
  var data = formToQuery(new FormData(f, e.submitter || undefined));
  var method = String(f.getAttribute('method') || 'GET').toUpperCase();
  if (method === 'GET') { var u = new URL(action); u.search = data; navigate(u.href, 'GET') }
  else navigate(action, 'POST', strToB64(data), 'application/x-www-form-urlencoded');
});
})();`

const js = (v: string) => JSON.stringify(v).replace(/</g, '\\u003c')

/**
 * 注入到网页 &lt;head&gt; 开头的脚本
 * @param pageUrl 网页的实际地址（含 #hash），作为 Referer、表单默认提交地址
 * @param cookie 该网址已有的 Cookie（网页读取 document.cookie 时返回）
 */
export function buildPageShim(pageUrl: string, cookie = '', charset: string | null = null): string {
  return `<script data-legado>window.__legadoPage=${js(pageUrl)};window.__legadoCookie=${js(cookie)};window.__legadoCharset=${js(charset || '')};${SHIM}<\/script>`
}

/** 网页声明的编码（不是 UTF-8 时返回，如 gbk），从网页开头的 meta 中读取 */
export function pageCharset(html: string): string | null {
  const c = /<meta[^>]+charset\s*=\s*["']?\s*([\w-]+)/i.exec(html.slice(0, 4096))?.[1]?.toLowerCase()
  return c && c !== 'utf-8' && c !== 'utf8' ? c : null
}

/**
 * 不支持 Navigation API 的浏览器无法拦截 location.href = ... 跳转：把网页内联脚本中的写法改为交给外层页面处理
 */
export function rewriteNavigation(html: string): string {
  return html.replace(/(<script\b(?![^>]*\bsrc\s*=)[^>]*>)([\s\S]*?)(<\/script>)/gi, (_m, open: string, code: string, close: string) => {
    // 只处理 JavaScript（type="application/json" 等数据块不动）
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(open)?.[1]
    if (type && !/javascript|ecmascript|module/i.test(type)) return open + code + close
    // 不匹配 obj.location.href（其他对象的属性）
    const fixed = code
      .replace(/(?<![\w$.])(?:(?:window|document|self)\.)?location\.href\s*=(?!=)/g, '__legadoNav.href=')
      .replace(/(?<![\w$.])(?:(?:window|document|self)\.)?location\.(assign|replace)\s*\(/g, '__legadoNav.$1(')
      .replace(/(?<![\w$.])(?:window|document|self)\.location\s*=(?!=)/g, '__legadoNav.href=')
    return open + fixed + close
  })
}

/** Base64 → UTF-8 文本（网页源码） */
/** 网页开头的 <meta charset="…"> 或 <meta http-equiv="Content-Type" content="…charset=…">（BOM 优先） */
function sniffMetaCharset(bytes: Uint8Array): string | null {
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) return 'utf-8'
  // 编码声明都是 ASCII，按 latin1 读前 2KB 即可
  const head = new TextDecoder('latin1').decode(bytes.subarray(0, 2048))
  return /<meta[^>]+charset\s*=\s*["']?\s*([\w-]+)/i.exec(head)?.[1] ?? null
}

export function decodeBase64Text(b64: string, contentType?: string): string {
  const bin = atob(b64 || '')
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  // 响应头没写编码时（很多 GBK 网站只在网页里写 <meta charset="gbk">），从网页开头的 meta 里找
  const charset = /charset=([\w-]+)/i.exec(contentType || '')?.[1] || sniffMetaCharset(bytes) || 'utf-8'
  try {
    return new TextDecoder(charset).decode(bytes)
  } catch {
    return new TextDecoder().decode(bytes)
  }
}
