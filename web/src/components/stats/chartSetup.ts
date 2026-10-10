/**
 * Chart.js 的统一初始化（设计 §7.5）。
 *
 * 只被 stats 组件引用，所以 chart.js 只会进入统计页的懒加载 chunk，不会进入入口包。
 * 按需注册而不是 `chart.js/auto`：本期只用到柱状 / 横向条形 / 环形 / 折线四种图，
 * 全量注册会把用不到的控制器、比例尺和插件一起打进来。
 */
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  DoughnutController,
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
  DoughnutController,
  ArcElement,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend
)

/**
 * 配色（相对白底的对比度，图形元素都 ≥ 3:1）。
 * 视频不用品牌琥珀 #FFA91F（只有 1.92:1），改用更深的 #B86E00。
 */
export const STAT_COLORS = {
  photo: '#2F6BFF', // 4.50:1
  video: '#B86E00', // 3.99:1
  other: '#465265', // 7.91:1
  unknown: '#58677E', // 5.74:1，中性灰，「未知」「其他」共用
} as const

/** 正文与坐标轴文字颜色，不用 #909399（对比度只有 3.08:1） */
export const STAT_TEXT_COLOR = '#606266'

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
