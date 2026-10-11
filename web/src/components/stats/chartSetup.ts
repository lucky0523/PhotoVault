/**
 * Chart.js 的统一初始化（设计 §7.5）。
 *
 * 只被 stats 组件引用，所以 chart.js 只会进入统计页的懒加载 chunk，不会进入入口包。
 * 按需注册而不是 `chart.js/auto`：本期只用到柱状 / 横向条形（含 100% 堆积）/ 折线，
 * 全量注册会把用不到的控制器、比例尺和插件一起打进来。
 */
import {
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js'

Chart.register(
  BarController,
  BarElement,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend
)

/**
 * 配色：统计页所有照片 / 视频 / 其他 / 未知的颜色只在这里定义，其他地方一律引用 STAT_COLORS.*。
 *
 * photo / video 是用户指定的颜色。相对卡片 / 对话框白底（.pv-panel、el-dialog）的对比度
 * 只有 3.12:1 和 2.34:1，照片与视频之间只有 1.33:1：视频色低于图形元素 3:1 的建议值，
 * 两者都低于文字要求的 4.5:1，所以 **不能用作文字颜色**，只用于色块、线条和图例色标；
 * 文字一律用 STAT_TEXT_COLOR / #303133。为弥补对比度不足：
 * - 堆积条形图的段内文字按段色逐段选墨色（segmentInk，≥ 4.5:1）；
 * - 堆积条形图相邻段之间留 2px 白边，图例带文字和数值，色标带细描边；
 * - 趋势图照片实线 + 圆点、视频虚线 + 三角点，不只靠颜色区分。
 * other（7.91:1）、unknown（4.42:1）是中性灰，同样只作图形用；「未知」和排行的「其他」共用 unknown。
 */
export const STAT_COLORS = {
  photo: '#4395EB', // 白底 3.12:1
  video: '#FF858A', // 白底 2.34:1
  other: '#465265', // 白底 7.91:1
  unknown: '#787878', // 白底 4.42:1
} as const

/** 正文与坐标轴文字颜色，不用 #909399（对比度只有 3.08:1） */
export const STAT_TEXT_COLOR = '#606266'

function channels(hex: string): [number, number, number] {
  const h = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number]
}

function luminance(hex: string): number {
  const [r, g, b] = channels(hex).map((v) => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2.x 对比度，参数为 #RRGGBB */
export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/**
 * 画在色块上的文字颜色：白色和 #1F1F1F 里对比度高的那个；两者都不到 4.5:1 时用纯黑
 * （任何背景上黑或白至少有一个 ≥ 4.58:1，结果总是 ≥ 4.5:1）。
 */
export function segmentInk(bg: string): string {
  const white = contrastRatio('#FFFFFF', bg)
  const dark = contrastRatio('#1F1F1F', bg)
  const best = white >= dark ? '#FFFFFF' : '#1F1F1F'
  if (Math.max(white, dark) >= 4.5) return best
  return contrastRatio('#000000', bg) >= white ? '#000000' : '#FFFFFF'
}

/**
 * 同一类别里多个格式的深浅：负数向黑混合、正数向白混合。第 0 位是原色，
 * 相邻位置之间对比度 ≥ 2.3:1，超过 6 个时循环。
 */
export const FAMILY_SHADE_STEPS = [0, -0.4, 0.35, -0.62, -0.2, -0.78] as const

/** base 在同类中第 position 个格式的颜色（#RRGGBB 大写） */
export function familyShade(base: string, position: number): string {
  const step = FAMILY_SHADE_STEPS[position % FAMILY_SHADE_STEPS.length]
  const target = step < 0 ? 0 : 255
  const k = Math.abs(step)
  return `#${channels(base)
    .map((v) => Math.round(v + (target - v) * k).toString(16).padStart(2, '0'))
    .join('')
    .toUpperCase()}`
}

Chart.defaults.color = STAT_TEXT_COLOR
Chart.defaults.font.family =
  "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', '微软雅黑', Arial, sans-serif"

/** 系统设置了「减少动态效果」时关闭所有图表动画。 */
export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

if (prefersReducedMotion()) {
  Chart.defaults.animation = false
}

export { Chart }
