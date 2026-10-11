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
 * 配色：统计页所有照片 / 视频 / 其他 / 未知的颜色只在这里定义，其他地方一律引用 STAT_COLORS.*。
 *
 * 相对卡片 / 对话框白底（.pv-panel、el-dialog）的对比度都 ≥ 4.5:1，既满足图形元素 ≥ 3:1，
 * 用作文字时也满足 ≥ 4.5:1：
 * - photo：品牌蓝同色系加深一级（品牌蓝只有 4.499:1，差一点不到 4.5），看起来是同一个色相；
 * - video：珊瑚红，与照片蓝构成互补色对；在红绿 / 蓝黄色觉异常模拟下两者仍明显可分
 *   （青色在蓝黄色盲下、紫色在红绿色盲下会和蓝色混在一起，因此不用）；
 * - other、unknown：中性石板灰，「未知」和排行的「其他」共用 unknown。
 * 趋势图另外用线型和点形状区分照片 / 视频，不只靠颜色。
 */
export const STAT_COLORS = {
  photo: '#2B65F5', // 4.90:1
  video: '#D1432B', // 4.62:1
  other: '#465265', // 7.91:1
  unknown: '#58677E', // 5.74:1
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
