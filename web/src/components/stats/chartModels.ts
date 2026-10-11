/**
 * 统计图表的共用配置（设计 §7.6）。
 *
 * 卡片和「查看数据」对话框各画一个独立的 Chart.js 实例，两边都从这里拿同一个
 * StatChartModel：标签、颜色、图表类型、options、朗读摘要和键盘逐项文本只在这里
 * 生成一次，卡片和对话框不会画得不一样。
 *
 * buildConfig() 每次调用都返回新的 data / options 对象：Chart.js 会改写并代理传入的
 * 配置，两个实例不能共用同一份。
 */
import type { ChartConfiguration, TooltipItem } from 'chart.js'
import type { Distribution, StatBucket } from '@/api/stats'
import { Chart, STAT_COLORS, STAT_TEXT_COLOR, prefersReducedMotion } from './chartSetup'
import { formatBytes, formatShare } from './format'

export type ChartShape = 'bar' | 'hbar' | 'doughnut' | 'line'
export type ChartMetric = 'count' | 'bytes'
export type TrendGranularity = 'month' | 'year'

export interface StatChartModel {
  /** 生成一份新的 Chart.js 配置 */
  buildConfig(): ChartConfiguration<any>
  /** canvas 的 aria-label */
  summary: string
  /** 可键盘浏览的项数（= labels 数） */
  count: number
  /** 第 index 项的朗读文本 */
  describe(index: number): string
}

// ---- 「统计时排除未知项」 ----------------------------------------------------

/**
 * 一位小数的百分比，合计恰好 100.0%（与服务端 stats_buckets.allocate_shares 完全一致）。
 *
 * 按千分之一（1000 份）计算的最大余数法，余数相同时给下标小的；total = 0 时全为 0。
 * 调用方保证 Σcounts = total。
 */
export function allocateShares(counts: number[], total: number): number[] {
  if (total <= 0) return counts.map(() => 0)
  const base = counts.map((c) => Math.floor((c * 1000) / total))
  const rems = counts.map((c) => (c * 1000) % total)
  let left = 1000 - base.reduce((sum, v) => sum + v, 0)
  const order = counts.map((_, i) => i).sort((a, b) => rems[b] - rems[a] || a - b)
  for (const i of order) {
    if (left <= 0) break
    base[i] += 1
    left -= 1
  }
  return base.map((t) => t / 10)
}

/** applyUnknownPolicy 的结果：excludedUnknown 是被排除的「未知」数（未排除时为 0） */
export interface DistributionView extends Distribution {
  excludedUnknown: number
}

/**
 * 「统计时排除未知项」唯一的计算入口：卡片、对话框和表格都用这里的结果，三者不会不一致。
 *
 * - 不排除（或本来没有未知）：原样返回，数值不变；
 * - 排除：去掉「未知」，total 改为已知数，buckets（排行还有「其他」）的占比按已知数重新
 *   分配，合计恰好 100.0%；排行的「其他」是已知值的合计，保留。
 * 不改动传入的对象（同一份响应要同时服务两种开关状态）。
 */
export function applyUnknownPolicy(dist: Distribution, excludeUnknown: boolean): DistributionView {
  if (!excludeUnknown || dist.unknown <= 0) return { ...dist, excludedUnknown: 0 }
  const known = dist.total - dist.unknown
  const hasOther = dist.kind === 'ranking' && dist.other !== undefined
  const counts = [...dist.buckets.map((b) => b.count), ...(hasOther ? [dist.other ?? 0] : [])]
  const shares = allocateShares(counts, known)
  const pct = (value: number) => (known > 0 ? Math.round((value * 1000) / known) / 10 : 0)
  const view: DistributionView = {
    ...dist,
    buckets: dist.buckets.map((b, i) => ({
      ...b,
      share: shares[i],
      ...(b.children ? { children: b.children.map((c) => ({ ...c, share: pct(c.count) })) } : {}),
    })),
    unknown: 0,
    unknown_share: 0,
    total: known,
    excludedUnknown: dist.unknown,
  }
  if (hasOther) view.other_share = shares[shares.length - 1]
  return view
}

// ---- 分布 / 排行 ------------------------------------------------------------

export interface DistributionChartInput {
  dist: Distribution
  chart: ChartShape
  /** 数量单位：张 / 个 */
  unit: string
  xLabel: string
  yLabel: string
  /** 普通柱的颜色 */
  color: string
  /** 数量 / 存储口径 */
  metric: ChartMetric
}

interface ChartItem {
  key: string
  label: string
  count: number
  share: number
  bytes: number
  bytesShare: number
  range?: string
  /** 「其他」「未知」：中性灰 */
  neutral: boolean
}

/** 环形图（类型占比）按类别取色 */
const KEY_COLORS: Record<string, string> = {
  photo: STAT_COLORS.photo,
  video: STAT_COLORS.video,
  other: STAT_COLORS.other,
}

const LABEL_MAX_CHARS = 18

function truncate(label: string): string {
  const chars = Array.from(label)
  return chars.length > LABEL_MAX_CHARS ? `${chars.slice(0, LABEL_MAX_CHARS).join('')}…` : label
}

export function buildDistributionChart(input: DistributionChartInput): StatChartModel {
  const { dist: d, chart, unit, xLabel, yLabel, color } = input
  const isBytes = input.metric === 'bytes'

  // 「其他」（仅排行，other > 0）和「未知」（unknown > 0）排在最后
  const items: ChartItem[] = d.buckets.map((b) => ({
    key: b.key,
    label: b.label,
    count: b.count,
    share: b.share,
    bytes: b.bytes ?? 0,
    bytesShare: b.bytes_share ?? 0,
    range: b.range,
    neutral: false,
  }))
  if (d.kind === 'ranking' && (d.other ?? 0) > 0) {
    items.push({
      key: '__other',
      label: '其他',
      count: d.other ?? 0,
      share: d.other_share ?? 0,
      bytes: 0,
      bytesShare: 0,
      neutral: true,
    })
  }
  // 折线图（星期 / 小时）的横轴是时间序列，「未知」不是其中一点：只在说明和表格里出现
  if (d.unknown > 0 && chart !== 'line') {
    items.push({
      key: '__unknown',
      label: '未知',
      count: d.unknown,
      share: d.unknown_share,
      bytes: 0,
      bytesShare: 0,
      neutral: true,
    })
  }

  const valueOf = (item: ChartItem): number => (isBytes ? item.bytes : item.count)
  const shareOf = (item: ChartItem): number => (isBytes ? item.bytesShare : item.share)
  const formatValue = (value: number): string => (isBytes ? formatBytes(value) : `${value} ${unit}`)

  function describeItem(item: ChartItem): string {
    const text = `${item.label}：${formatValue(valueOf(item))}（${formatShare(shareOf(item))}）`
    return item.range ? `${text}，区间 ${item.range}` : text
  }

  const summaryParts: string[] = [
    isBytes ? `共 ${formatBytes(d.total_bytes ?? 0)}` : `共 ${d.total} ${unit}`,
  ]
  let top: ChartItem | null = null
  for (const item of items) {
    if (item.neutral || valueOf(item) <= 0) continue
    if (!top || valueOf(item) > valueOf(top)) top = item
  }
  if (top) {
    summaryParts.push(`最多：${top.label} ${formatValue(valueOf(top))}（${formatShare(shareOf(top))}）`)
  }
  if (d.unknown > 0) summaryParts.push(`未知 ${d.unknown} ${unit}`)
  const summary = summaryParts.join('；')

  function colorOf(item: ChartItem, index: number): string {
    if (item.neutral) return STAT_COLORS.unknown
    if (chart === 'doughnut') {
      const palette = [STAT_COLORS.photo, STAT_COLORS.video, STAT_COLORS.other]
      return KEY_COLORS[item.key] ?? palette[index % palette.length]
    }
    return color
  }

  function buildConfig(): ChartConfiguration<any> {
    const list = items
    const labels = list.map((i) => i.label)
    const values = list.map(valueOf)
    const colors = list.map(colorOf)
    const tooltip = {
      callbacks: {
        title: () => '',
        label: (ctx: TooltipItem<any>) => {
          const item = list[ctx.dataIndex]
          return item ? `${item.label}：${formatValue(valueOf(item))}（${formatShare(shareOf(item))}）` : ''
        },
        afterLabel: (ctx: TooltipItem<any>) => {
          const range = list[ctx.dataIndex]?.range
          return range ? `区间 ${range}` : ''
        },
      },
    }
    const animation = prefersReducedMotion() ? false : undefined

    if (chart === 'doughnut') {
      return {
        type: 'doughnut',
        data: {
          labels,
          datasets: [{ data: values, backgroundColor: colors, borderColor: '#fff', borderWidth: 2 }],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation,
          plugins: {
            tooltip,
            legend: {
              position: 'bottom',
              labels: {
                color: STAT_TEXT_COLOR,
                // 图例带文字和数量，不只靠颜色区分
                generateLabels: (c: Chart<any>) =>
                  list.map((item, index) => ({
                    text: `${item.label} ${formatValue(valueOf(item))}`,
                    fillStyle: colors[index],
                    strokeStyle: '#fff',
                    lineWidth: 0,
                    fontColor: STAT_TEXT_COLOR,
                    hidden: !c.getDataVisibility(index),
                    index,
                  })),
              },
            },
          },
        },
      }
    }

    if (chart === 'line') {
      // 单序列折线：实线 + 圆点，点常显，单个值也能看清
      return {
        type: 'line',
        data: {
          labels,
          datasets: [
            {
              data: values,
              borderColor: color,
              backgroundColor: color,
              pointBackgroundColor: color,
              pointBorderColor: color,
              pointStyle: 'circle',
              pointRadius: 3,
              pointHoverRadius: 6,
              pointHitRadius: 8,
              borderWidth: 2,
              tension: 0,
              fill: false,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: { display: false },
            tooltip: { ...tooltip, mode: 'index', intersect: false },
          },
          scales: {
            x: {
              title: { display: !!xLabel, text: xLabel },
              ticks: { autoSkip: true, maxRotation: 0 },
              grid: { display: false },
            },
            y: {
              title: { display: !!yLabel, text: yLabel },
              beginAtZero: true,
              ticks: { precision: 0 },
            },
          },
        },
      }
    }

    const horizontal = chart === 'hbar'
    const valueAxis = horizontal ? 'x' : 'y'
    const xTitle = isBytes && valueAxis === 'x' ? '存储' : xLabel
    const yTitle = isBytes && valueAxis === 'y' ? '存储' : yLabel
    const valueTicks = isBytes
      ? { callback: (v: number | string) => formatBytes(Number(v)) }
      : { precision: 0 }
    const categoryTicks = horizontal
      ? {
          autoSkip: false,
          callback(this: { getLabelForValue: (v: number) => string }, v: number | string) {
            return truncate(this.getLabelForValue(Number(v)))
          },
        }
      : { autoSkip: true, maxRotation: 0 }

    return {
      type: 'bar',
      data: {
        labels,
        datasets: [{ data: values, backgroundColor: colors, borderRadius: 3, maxBarThickness: 36 }],
      },
      options: {
        indexAxis: horizontal ? 'y' : 'x',
        responsive: true,
        maintainAspectRatio: false,
        animation,
        plugins: { legend: { display: false }, tooltip },
        scales: {
          x: {
            title: { display: !!xTitle, text: xTitle },
            beginAtZero: true,
            ticks: valueAxis === 'x' ? valueTicks : categoryTicks,
            grid: { display: valueAxis === 'x' },
          },
          y: {
            title: { display: !!yTitle, text: yTitle },
            beginAtZero: true,
            ticks: valueAxis === 'y' ? valueTicks : categoryTicks,
            grid: { display: valueAxis === 'y' },
          },
        },
      },
    }
  }

  return {
    buildConfig,
    summary,
    count: items.length,
    describe: (index) => {
      const item = items[index]
      return item ? describeItem(item) : ''
    },
  }
}

// ---- 拍摄趋势 ---------------------------------------------------------------

export interface TrendSeries {
  name: '照片' | '视频'
  unit: string
  dist: DistributionView
  color: string
  dashed: boolean
  pointStyle: 'circle' | 'triangle'
}

/** 只画文件数 > 0 的类别（含已排除的未知，切换开关时图例不跳动） */
export function buildTrendSeries(photo: DistributionView, video: DistributionView): TrendSeries[] {
  const list: TrendSeries[] = []
  const hasFiles = (d: DistributionView) => d.total + d.excludedUnknown > 0
  if (hasFiles(photo)) {
    list.push({
      name: '照片',
      unit: '张',
      dist: photo,
      color: STAT_COLORS.photo,
      dashed: false,
      pointStyle: 'circle',
    })
  }
  if (hasFiles(video)) {
    list.push({
      name: '视频',
      unit: '个',
      dist: video,
      color: STAT_COLORS.video,
      dashed: true,
      pointStyle: 'triangle',
    })
  }
  return list
}

export interface TrendChartInput {
  /** 共同轴：照片和视频的 bucket 完全一致 */
  axis: StatBucket[]
  series: TrendSeries[]
  granularity: TrendGranularity
}

export function buildTrendChart(input: TrendChartInput): StatChartModel {
  const { axis, series: list, granularity } = input

  function pointText(s: TrendSeries, index: number): string {
    const b = s.dist.buckets[index]
    return b ? `${s.name}：${b.count} ${s.unit}（${formatShare(b.share)}）` : ''
  }

  const summaryParts = list.map((s) => `${s.name}共 ${s.dist.total} ${s.unit}`)
  const first = axis[0]
  const last = axis[axis.length - 1]
  if (first && last) summaryParts.push(`时间范围 ${first.label} 至 ${last.label}`)
  const summary = summaryParts.join('；')

  function buildConfig(): ChartConfiguration<'line'> {
    return {
      type: 'line',
      data: {
        labels: axis.map((b) => b.label),
        datasets: list.map((s) => ({
          label: s.name,
          data: s.dist.buckets.map((b) => b.count),
          borderColor: s.color,
          backgroundColor: s.color,
          borderDash: s.dashed ? [6, 4] : [],
          pointStyle: s.pointStyle,
          pointRadius: 3,
          pointHoverRadius: 6,
          borderWidth: 2,
          tension: 0,
        })),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: prefersReducedMotion() ? false : undefined,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'bottom', labels: { usePointStyle: true } },
          tooltip: {
            mode: 'index',
            intersect: false,
            usePointStyle: true,
            callbacks: {
              label: (ctx: TooltipItem<'line'>) => {
                const s = list[ctx.datasetIndex]
                return s ? pointText(s, ctx.dataIndex) : ''
              },
            },
          },
        },
        scales: {
          x: {
            title: { display: true, text: granularity === 'month' ? '月份' : '年份' },
            ticks: { autoSkip: true, maxRotation: 0 },
            grid: { display: false },
          },
          y: {
            title: { display: true, text: '数量' },
            beginAtZero: true,
            ticks: { precision: 0 },
          },
        },
      },
    }
  }

  return {
    buildConfig,
    summary,
    count: axis.length,
    describe: (index) => {
      const label = axis[index]?.label ?? ''
      const parts = list.map((s) => `${s.name} ${s.dist.buckets[index]?.count ?? 0} ${s.unit}`)
      return `${label}：${parts.join('，')}`
    },
  }
}

// ---- 高度 -------------------------------------------------------------------

/** 卡片里：横向条形图的高度随条数变化，竖向图、环形图和折线图固定 240px */
export function cardChartHeight(shape: ChartShape, count: number): string {
  return shape === 'hbar' ? `${Math.max(160, 28 * count + 48)}px` : '240px'
}

/**
 * 对话框里放大显示：桌面 360px，窄屏按 70vw 缩小，不低于 240px（375px 宽时约 262px）。
 * 横向条形图另外按条数给出下限 30·条数 + 56px，在任何宽度下都随条数变高，且总比卡片的
 * 28·条数 + 48px（最低 160px）高：长排行在窄屏不会被 70vw 压扁，对话框内容可以纵向滚动。
 */
export function dialogChartHeight(shape: ChartShape, count: number): string {
  const base = 'clamp(240px, 70vw, 360px)'
  return shape === 'hbar' ? `max(${30 * count + 56}px, ${base})` : base
}
