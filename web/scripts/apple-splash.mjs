/**
 * iOS 主屏幕网页应用的启动图（apple-touch-startup-image）。
 *
 * 从桌面打开时 iOS 先显示启动图，再加载页面。启动图画的是 index.html 启动画面（#boot）里的图标，
 * 位置、大小与之相同，加载完成后无缝接上启动画面，不会先闪一下空白。
 *
 * - 生成图片：node scripts/apple-splash.mjs（npm run splash），输出到 public/splash/，生成后提交到仓库。
 *   改了启动画面图标的样式、颜色或位置后重新生成
 * - 页面里的 <link> 由 vite.config.ts 构建时按下面的尺寸表注入
 */
import { mkdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/** iOS 设备的屏幕尺寸（物理像素，竖屏）与缩放比例，同一尺寸的设备共用一张图 */
export const APPLE_SPLASH_SIZES = [
  // iPad：横竖屏都生成
  { width: 2048, height: 2732, scale: 2, ipad: true }, // iPad Pro 12.9"、iPad Air 13"
  { width: 1668, height: 2388, scale: 2, ipad: true }, // iPad Pro 11"、iPad Air 11"
  { width: 1640, height: 2360, scale: 2, ipad: true }, // iPad Air 10.9"、iPad 11"
  { width: 1668, height: 2224, scale: 2, ipad: true }, // iPad Pro 10.5"
  { width: 1620, height: 2160, scale: 2, ipad: true }, // iPad 10.2"
  { width: 1536, height: 2048, scale: 2, ipad: true }, // iPad 9.7"、iPad mini 7.9"
  { width: 1488, height: 2266, scale: 2, ipad: true }, // iPad mini 8.3"
  // iPhone：主屏幕应用基本都是竖着打开，只生成竖屏
  { width: 1320, height: 2868, scale: 3 }, // iPhone 16 Pro Max、17 Pro Max
  { width: 1260, height: 2736, scale: 3 }, // iPhone Air
  { width: 1206, height: 2622, scale: 3 }, // iPhone 16 Pro、17 Pro、17
  { width: 1290, height: 2796, scale: 3 }, // iPhone 14 Pro Max、15 Plus/Pro Max、16 Plus
  { width: 1179, height: 2556, scale: 3 }, // iPhone 14 Pro、15、15 Pro、16
  { width: 1284, height: 2778, scale: 3 }, // iPhone 12/13 Pro Max、14 Plus
  { width: 1170, height: 2532, scale: 3 }, // iPhone 12、13、14、16e
  { width: 1125, height: 2436, scale: 3 }, // iPhone X、XS、11 Pro、12/13 mini
  { width: 1242, height: 2688, scale: 3 }, // iPhone XS Max、11 Pro Max
  { width: 828, height: 1792, scale: 2 }, // iPhone XR、11
  { width: 1242, height: 2208, scale: 3 }, // iPhone 6/7/8 Plus
  { width: 750, height: 1334, scale: 2 }, // iPhone 6/7/8、SE 2/3
  { width: 640, height: 1136, scale: 2 }, // iPhone SE 1、5s
]

/** 与 index.html 启动画面相同的颜色 */
const THEMES = {
  light: { bg: '#f6f1e7', c: '#9c6b30', page: '#fff8ec', shadow: 'rgba(120,80,30,0.45)' },
  dark: { bg: '#16140f', c: '#d4a15c', page: '#2a251d', shadow: 'rgba(0,0,0,0.6)' },
}

/** 一张启动图的文件名（相对 public/） */
export function splashFile(size, landscape, dark) {
  const w = landscape ? size.height : size.width
  const h = landscape ? size.width : size.height
  return `splash/apple-splash-${landscape ? 'landscape' : 'portrait'}-${dark ? 'dark' : 'light'}-${w}x${h}.png`
}

/** 所有启动图：{ file, media, size, landscape, dark } */
export function splashEntries() {
  const list = []
  for (const size of APPLE_SPLASH_SIZES) {
    for (const landscape of size.ipad ? [false, true] : [false]) {
      for (const dark of [false, true]) {
        const media = [
          'screen',
          `(device-width: ${size.width / size.scale}px)`,
          `(device-height: ${size.height / size.scale}px)`,
          `(-webkit-device-pixel-ratio: ${size.scale})`,
          `(orientation: ${landscape ? 'landscape' : 'portrait'})`,
          `(prefers-color-scheme: ${dark ? 'dark' : 'light'})`,
        ].join(' and ')
        list.push({ file: splashFile(size, landscape, dark), media, size, landscape, dark })
      }
    }
  }
  return list
}

/**
 * 画一张启动图（SVG，单位为物理像素）。
 * 启动画面在网页可视区域里垂直居中，图标在整组内容（图标 76、间距 22、进度条 3、间距 22、文字约 17）的最上面，
 * 所以图标中心比可视区域中心高 32pt。网页从状态栏下面开始（状态栏样式为 default），可视区域中心比屏幕中心低半个状态栏
 */
function splashSvg(entry) {
  const { size, landscape, dark } = entry
  const t = THEMES[dark ? 'dark' : 'light']
  const s = size.scale
  const W = landscape ? size.height : size.width
  const H = landscape ? size.width : size.height
  const statusBar = size.ipad ? 24 : size.height / s >= 812 ? 47 : 20
  const cx = W / 2
  const cy = H / 2 + (statusBar / 2) * s - 32 * s
  const box = 76 * s
  const x = cx - box / 2
  const y = cy - box / 2
  // 书页：两页各 21×30，中间留 2 的书脊
  const pw = 21 * s
  const ph = 30 * s
  const py = cy - ph / 2
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <defs><filter id="sh" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="${15 * s}"/></filter></defs>
  <rect width="${W}" height="${H}" fill="${t.bg}"/>
  <rect x="${x + 8 * s}" y="${y + 18 * s}" width="${box - 16 * s}" height="${box - 16 * s}" rx="${12 * s}" fill="${t.shadow}" filter="url(#sh)"/>
  <rect x="${x}" y="${y}" width="${box}" height="${box}" rx="${20 * s}" fill="${t.c}"/>
  <rect x="${cx - 22 * s}" y="${py}" width="${pw}" height="${ph}" rx="${2 * s}" fill="${t.page}"/>
  <rect x="${cx + 1 * s}" y="${py}" width="${pw}" height="${ph}" rx="${2 * s}" fill="${t.page}"/>
</svg>`
}

async function main() {
  const { default: sharp } = await import('sharp')
  const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'public')
  rmSync(join(root, 'splash'), { recursive: true, force: true })
  mkdirSync(join(root, 'splash'), { recursive: true })
  const entries = splashEntries()
  for (const e of entries) {
    await sharp(Buffer.from(splashSvg(e)))
      .png({ compressionLevel: 9, palette: true, quality: 90 })
      .toFile(join(root, e.file))
  }
  console.log(`已生成 ${entries.length} 张启动图到 public/splash/`)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((e) => {
    console.error(e)
    process.exit(1)
  })
}
