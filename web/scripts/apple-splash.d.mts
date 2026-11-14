export interface AppleSplashSize {
  width: number
  height: number
  scale: number
  ipad?: boolean
}

export interface AppleSplashEntry {
  /** 相对 public/ 的路径 */
  file: string
  media: string
  size: AppleSplashSize
  landscape: boolean
  dark: boolean
}

export declare const APPLE_SPLASH_SIZES: AppleSplashSize[]
export declare function splashFile(size: AppleSplashSize, landscape: boolean, dark: boolean): string
export declare function splashEntries(): AppleSplashEntry[]
