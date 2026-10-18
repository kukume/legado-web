import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'
import { cpSync, createReadStream, existsSync, statSync } from 'node:fs'
import { join, normalize, resolve } from 'node:path'

/**
 * PDF 阅读用到的 pdf.js 资源（中文 PDF 的字符映射表、标准字体、图像解码 wasm）：
 * 开发时直接从 node_modules 提供，构建时复制到 dist/pdfjs/。
 */
function pdfjsAssets(): Plugin {
  const src = fileURLToPath(new URL('./node_modules/pdfjs-dist', import.meta.url))
  const dirs = ['cmaps', 'standard_fonts', 'wasm', 'iccs']
  let outDir = 'dist'
  return {
    name: 'pdfjs-assets',
    configResolved(c) {
      outDir = resolve(c.root, c.build.outDir)
    },
    configureServer(server) {
      server.middlewares.use('/pdfjs/', (req, res, next) => {
        const rel = normalize(decodeURIComponent((req.url ?? '').split('?')[0])).replace(/^[\\/]+/, '')
        const file = join(src, rel)
        if (!dirs.includes(rel.split(/[\\/]/)[0]) || !file.startsWith(src) || !existsSync(file) || !statSync(file).isFile()) return next()
        if (file.endsWith('.wasm')) res.setHeader('Content-Type', 'application/wasm')
        createReadStream(file).pipe(res)
      })
    },
    closeBundle() {
      for (const d of dirs) cpSync(join(src, d), join(outDir, 'pdfjs', d), { recursive: true })
    },
  }
}

// 开发时把 /api 代理到后端（默认 18080，可用 LEGADO_API 覆盖）
const api = process.env.LEGADO_API || 'http://127.0.0.1:18080'

export default defineConfig({
  plugins: [
    vue({
      // Cap 人机验证的 <cap-widget> 是原生 Web Component
      template: { compilerOptions: { isCustomElement: (tag) => tag === 'cap-widget' } },
    }),
    pdfjsAssets(),
    VitePWA({
      // 有新版本时由页面提示用户刷新，避免阅读中被强制刷新
      registerType: 'prompt',
      includeAssets: ['favicon.svg', 'favicon.ico', 'apple-touch-icon-180x180.png'],
      manifest: {
        name: '阅读 Web',
        short_name: '阅读',
        description: '网页版阅读：书源、书架与离线阅读',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#f6f1e7',
        theme_color: '#f6f1e7',
        icons: [
          { src: 'pwa-64x64.png', sizes: '64x64', type: 'image/png' },
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,mjs,css,html,svg,png,ico,woff2}'],
        // m3u8 播放库只在听书时按需加载（离线时本来就无法播放在线音频），不预缓存
        globIgnores: ['**/hls-*.js', 'pdfjs/**'],
        maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
        // 页面路由离线时回退到 index.html；接口和开放接口文档不走回退
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//, /^\/openapi\//],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // pdf.js 的字符映射表、字体：用到时缓存，离线读 PDF 也能用
            urlPattern: ({ url }) => url.pathname.startsWith('/pdfjs/'),
            handler: 'CacheFirst',
            options: { cacheName: 'pdfjs', expiration: { maxEntries: 300 }, cacheableResponse: { statuses: [200] } },
          },
          {
            // 封面图片：离线时书架和详情页也能显示
            urlPattern: ({ url }) => url.pathname === '/api/proxy/image' && url.searchParams.get('cover') === 'true',
            handler: 'CacheFirst',
            options: {
              cacheName: 'covers',
              expiration: { maxEntries: 500, maxAgeSeconds: 60 * 60 * 24 * 60 },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: api, changeOrigin: true },
      '/openapi': { target: api, changeOrigin: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
})
