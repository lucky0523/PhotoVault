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
import type { ActiveDataPoint, BarElement, ChartConfiguration, Plugin, TooltipItem } from 'chart.js'
import type { Distribution, StatBucket } from '@/api/stats'
import { Chart, STAT_COLORS, familyShade, prefersReducedMotion, segmentInk } from './chartSetup'
import { formatBytes, formatShare } from './format'

export type ChartShape = 'bar' | 'hbar' | 'line' | 'stackedBar'
export type ChartMetric = 'count' | 'bytes'
export type TrendGranularity = 'month' | 'year'

export interface StatChartModel {
  /** 生成一份新的 Chart.js 配置 */
  buildConfig(): ChartConfiguration<any>
  /** canvas 的 aria-label */
  summary: string
  /** 可键盘浏览的项数（= labels 数；堆积条形图 = 段数） */
  count: number
  /** 第 index 项的朗读文本 */
  describe(index: number): string
  /**
   * 键盘浏览第 index 项时要高亮的数据点。缺省时高亮所有可见数据集的第 index 个点
   * （趋势图两条序列一起）；堆积条形图每段是一个数据集，第 index 项 = 第 index 个数据集。
   */
  activeAt?: (index: number) => ActiveDataPoint[]
  /** 堆积条形图和拍摄趋势的 HTML 图例，StatChartCanvas 画在图下（可换行，每段都显示）；其他图表没有 */
  legend?: StatLegendItem[]
}

export interface StatLegendItem {
  key: string
  /** 类别 + 数值 + 占比（拍摄趋势只有序列名） */
  text: string
  /** 色标颜色（只用于色标，文字始终是正文色） */
  color: string
  /** 折线图例：线型 + 点形状；缺省为方形色块 */
  marker?: { dashed: boolean; pointStyle: 'circle' | 'triangle' }
  /** 该序列已被隐藏（拍摄趋势点图例切换显示 / 隐藏） */
  hidden?: boolean
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

/** 堆积条形图按类别 key 取色（类型占比的照片 / 视频 / 其他；格式占比的分组见 FORMAT_FAMILY） */
const KEY_COLORS: Record<string, string> = {
  photo: STAT_COLORS.photo,
  video: STAT_COLORS.video,
  other: STAT_COLORS.other,
}

/**
 * 格式占比：格式分组 key → 所属类别。必须和服务端 server/app/services/stats_buckets.py 的
 * FORMAT_GROUPS 保持一致；不认识的 key（服务端以后新增的分组）按中性处理。
 */
const FORMAT_FAMILY: Record<string, 'photo' | 'video' | 'neutral'> = {
  jpeg: 'photo',
  heic: 'photo',
  png: 'photo',
  webp: 'photo',
  gif: 'photo',
  raw: 'photo',
  mp4: 'video',
  mov: 'video',
  video_other: 'video',
  no_ext: 'neutral',
  misc: 'neutral',
}

/**
 * 堆积条形图的段内占比文字：居中，段宽放不下（文字宽 + 8px）或条高 < 14px 时不画，
 * 窄屏和窄段自动隐藏，数值仍在图例、tooltip 和表格里。墨色按段色逐段选择
 * （数据集上的 pvLabelColor，见 segmentInk），对段色的对比度都 ≥ 4.5:1。
 *
 * 内联插件只在创建 Chart 实例时读取一次，之后 StatChartCanvas 换 data / options 时它仍然
 * 挂着：这里只读 chart.data，不能引用任何闭包里的模型数据。
 */
const SEGMENT_LABELS: Plugin<'bar'> = {
  id: 'pvSegmentLabels',
  afterDatasetsDraw(chart) {
    const { ctx } = chart
    ctx.save()
    ctx.font = `600 12px ${Chart.defaults.font.family}`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    chart.data.datasets.forEach((dataset, i) => {
      if (!chart.isDatasetVisible(i)) return
      const el = chart.getDatasetMeta(i).data[0] as unknown as BarElement | undefined
      if (!el) return
      const p = el.getProps(['x', 'y', 'base', 'height'])
      const [x, y, base, height] = [p.x, p.y, p.base, p.height].map(Number)
      const text = formatShare(Number(dataset.data[0]))
      if (height >= 14 && ctx.measureText(text).width + 8 <= Math.abs(x - base)) {
        ctx.fillStyle =
          (dataset as { pvLabelColor?: string }).pvLabelColor ?? segmentInk(String(dataset.backgroundColor))
        ctx.fillText(text, (x + base) / 2, y)
      }
    })
    ctx.restore()
  },
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

  // 堆积条形图只画当前口径下 > 0 的段（0 宽的段没有意义，键盘也不该停在上面）；
  // 段、图例、摘要、键盘逐项都用这同一份列表
  const segments = chart === 'stackedBar' ? items.filter((i) => valueOf(i) > 0) : items

  const total = isBytes ? `共 ${formatBytes(d.total_bytes ?? 0)}` : `共 ${d.total} ${unit}`
  const summaryParts: string[] = [total]
  if (chart === 'stackedBar') {
    summaryParts.push(segments.length > 0 ? segments.map(describeItem).join('，') : '暂无数据')
  } else {
    let top: ChartItem | null = null
    for (const item of items) {
      if (item.neutral || valueOf(item) <= 0) continue
      if (!top || valueOf(item) > valueOf(top)) top = item
    }
    if (top) {
      summaryParts.push(`最多：${top.label} ${formatValue(valueOf(top))}（${formatShare(shareOf(top))}）`)
    }
    if (d.unknown > 0) summaryParts.push(`未知 ${d.unknown} ${unit}`)
  }
  const summary = summaryParts.join('；')

  // 堆积条形图每段的颜色：类型按类别；格式按所属类别取同色系的深浅（在同类格式里的位置，
  // 按 items 而不是当前口径的 segments 计算，切换「数量 / 存储」时颜色不变）；中性格式在
  // other / unknown 两种灰之间交替
  const segmentColors = new Map<string, string>()
  if (chart === 'stackedBar') {
    const positions = { photo: 0, video: 0, neutral: 0 }
    for (const item of items) {
      let c: string
      if (item.neutral) c = STAT_COLORS.unknown
      else if (KEY_COLORS[item.key]) c = KEY_COLORS[item.key]
      else {
        const family = FORMAT_FAMILY[item.key] ?? 'neutral'
        const pos = positions[family]++
        c =
          family === 'neutral'
            ? [STAT_COLORS.other, STAT_COLORS.unknown][pos % 2]
            : familyShade(STAT_COLORS[family], pos)
      }
      segmentColors.set(item.key, c)
    }
  }

  function colorOf(item: ChartItem): string {
    if (chart === 'stackedBar') return segmentColors.get(item.key) ?? STAT_COLORS.unknown
    if (item.neutral) return STAT_COLORS.unknown
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

    if (chart === 'stackedBar') {
      // 一根横向 100% 堆积条：每段一个数据集，段宽 = 占比（数量口径 share，存储口径 bytes_share；
      // 服务端的占比合计恰好 100.0%）。段之间 2px 白边，不只靠颜色区分
      const last = segments.length - 1
      const segColors = segments.map(colorOf)
      return {
        type: 'bar',
        data: {
          labels: ['占比'],
          datasets: segments.map((item, i) => ({
            label: item.label,
            data: [shareOf(item)],
            backgroundColor: segColors[i],
            // 段内占比文字的墨色（SEGMENT_LABELS 读取），对段色 ≥ 4.5:1
            pvLabelColor: segmentInk(segColors[i]),
            borderColor: '#fff',
            borderWidth: { top: 0, bottom: 0, left: 0, right: i < last ? 2 : 0 },
            borderSkipped: false,
            borderRadius: 0,
            barPercentage: 0.8,
            categoryPercentage: 1,
            maxBarThickness: 64,
          })),
        },
        options: {
          indexAxis: 'y',
          responsive: true,
          maintainAspectRatio: false,
          animation,
          interaction: { mode: 'nearest', intersect: true },
          plugins: {
            tooltip: {
              callbacks: {
                title: () => '',
                label: (ctx: TooltipItem<'bar'>) => {
                  const item = segments[ctx.datasetIndex]
                  return item ? describeItem(item) : ''
                },
              },
            },
            // 图例改为 HTML（model.legend，StatChartCanvas 画在图下）：Chart.js 图例在固定高度的
            // 画布里放不下时会丢项，格式多、屏幕窄时尤其如此
            legend: { display: false },
          },
          scales: {
            x: {
              stacked: true,
              min: 0,
              max: 100,
              // 不旋转刻度（旋转后占高度，挤扁条形）；放不下时自动隔一个显示
              ticks: {
                stepSize: 25,
                maxRotation: 0,
                autoSkip: true,
                autoSkipPadding: 8,
                callback: (v: number | string) => `${v}%`,
              },
              grid: { display: false },
            },
            // 只有一根条，类别轴不显示，条形占满宽度（375px 时也是）
            y: { stacked: true, display: false },
          },
        },
        plugins: [SEGMENT_LABELS],
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
    count: segments.length,
    describe: (index) => {
      const item = segments[index]
      return item ? describeItem(item) : ''
    },
    activeAt:
      chart === 'stackedBar'
        ? (index) => (segments[index] ? [{ datasetIndex: index, index: 0 }] : [])
        : undefined,
    // 图例带文字、数值和占比：段太窄画不下占比时，图例里仍能看到
    legend:
      chart === 'stackedBar'
        ? segments.map((item) => ({
            key: item.key,
            text: `${item.label} ${formatValue(valueOf(item))}（${formatShare(shareOf(item))}）`,
            color: colorOf(item),
          }))
        : undefined,
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
  /** 点图例隐藏的序列名（与原 Chart.js 图例点击切换相同） */
  hidden?: readonly string[]
}

export function buildTrendChart(input: TrendChartInput): StatChartModel {
  const { axis, series: list, granularity, hidden = [] } = input

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
          hidden: hidden.includes(s.name),
        })),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: prefersReducedMotion() ? false : undefined,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          // 图例改为 HTML（model.legend，线型 + 点形状），卡片里放在底部「查看数据」一行
          legend: { display: false },
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
            // 总览第二行趋势卡片只占半行（约 444px 起），默认 3px 间距会让月份标签首尾相接，
            // 与堆叠条形图一致留 8px 间距，让 autoSkip 选更稀疏的刻度
            ticks: { autoSkip: true, maxRotation: 0, autoSkipPadding: 8 },
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
    // 只有画出来的序列（文件数 > 0）；点图例项切换显示 / 隐藏
    legend: list.map((s) => ({
      key: s.name,
      text: s.name,
      color: s.color,
      marker: { dashed: s.dashed, pointStyle: s.pointStyle },
      hidden: hidden.includes(s.name),
    })),
  }
}

// ---- 高度 -------------------------------------------------------------------

/**
 * 卡片里：横向条形图的高度随条数变化；堆积条形图（类型占比、格式占比）只有一根条，
 * 绘图区 72px（条 + 刻度），图例是图下的 HTML，整行宽的卡片不留大片空白；
 * 竖向图和折线图固定 240px
 */
export function cardChartHeight(shape: ChartShape, count: number): string {
  if (shape === 'stackedBar') return '72px'
  return shape === 'hbar' ? `${Math.max(160, 28 * count + 48)}px` : '240px'
}

/**
 * 对话框里放大显示：桌面 360px，窄屏按 70vw 缩小，不低于 240px（375px 宽时约 262px）。
 * 横向条形图另外按条数给出下限 30·条数 + 56px，在任何宽度下都随条数变高，且总比卡片的
 * 28·条数 + 48px（最低 160px）高：长排行在窄屏不会被 70vw 压扁，对话框内容可以纵向滚动。
 * 堆积条形图在任何宽度下绘图区固定 120px（图例在图下）：比卡片的 72px 高、段内文字更容易
 * 放下，下面的表格仍然在视野里。
 */
export function dialogChartHeight(shape: ChartShape, count: number): string {
  if (shape === 'stackedBar') return '120px'
  const base = 'clamp(240px, 70vw, 360px)'
  return shape === 'hbar' ? `max(${30 * count + 56}px, ${base})` : base
}
