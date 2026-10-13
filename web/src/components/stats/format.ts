/**
 * 统计页专用的格式化函数。
 *
 * 不复用 api/files.ts::formatFileSize：它的单位表只到 GB，≥ 1 TiB 时下标越界显示
 * undefined，而整库统计很容易超过 1 TiB。也不去改那个函数，避免影响其他页面。
 */

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/** 字节数 → 「1.5 GB」（1024 进制，1 位小数；B 不带小数；超过 TB 仍以 TB 显示）。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024
    unit++
  }
  return unit === 0 ? `${value} B` : `${value.toFixed(1)} ${BYTE_UNITS[unit]}`
}

/** 服务端返回的百分数（已保留 1 位小数）→ 「12.5%」。 */
export function formatShare(share: number): string {
  if (!Number.isFinite(share)) return '0.0%'
  return `${share.toFixed(1)}%`
}

/** 场景亮度 EV100 → 「12.5」「−3.2」（1 位小数，负号用 U+2212，与桶标签一致）；无值时为「—」。 */
export function formatEv(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  let text = v.toFixed(1)
  if (text === '-0.0') text = '0.0'
  return text.startsWith('-') ? `\u2212${text.slice(1)}` : text
}
