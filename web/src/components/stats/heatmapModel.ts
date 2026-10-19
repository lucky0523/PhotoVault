/**
 * 每日文件数量热力图的纯数据模型（不依赖 Vue，Playwright 在 Node 端直接做单元测试）。
 *
 * - 日期一律用 UTC 毫秒表示「某一天」（Date.UTC(y, m - 1, d)），只做日历运算，
 *   不涉及时区：服务端已经按统计时区把文件归到当地日期，并给出当地的 today；
 * - 网格按列（周）排列，每列 7 格，周一在最上面；第一列从范围起点当天或之前的周一开始；
 * - 颜色深浅分 5 级：0 级 = 没有文件，1–4 级按当前范围内非零天数的四分位数划分；
 * - 未知拍摄时间的文件（daily.unknown）不在这里出现，也不计入任何合计。
 */

const DAY_MS = 86_400_000

/** 当前显示范围：'recent' = 最近一年，数字 = 某个公历年 */
export type HeatmapRange = 'recent' | number

export type HeatmapLevel = 0 | 1 | 2 | 3 | 4

export interface HeatmapCell {
  /** YYYY-MM-DD */
  date: string
  count: number
  level: HeatmapLevel
  /** 提示文字，同时用作 aria-label 的开头 */
  label: string
}

export interface HeatmapMonthLabel {
  /** 所在列（周）的下标 */
  col: number
  /** 「N月」 */
  label: string
}

export interface HeatmapModel {
  /** 列 = 周（周一 … 周日）；范围以外和 today 之后的格子为 null（不画） */
  weeks: (HeatmapCell | null)[][]
  monthLabels: HeatmapMonthLabel[]
  /** 已画出的格子里的文件数合计 */
  total: number
  /** 范围起点 YYYY-MM-DD */
  start: string
  /** 范围终点 YYYY-MM-DD（年份范围为 12 月 31 日，可能晚于 today） */
  end: string
  /** 实际画到的最后一天 YYYY-MM-DD */
  drawEnd: string
}

export interface HeatmapMonthRow {
  /** YYYY-MM */
  key: string
  /** YYYY年M月 */
  label: string
  count: number
}

// ---- 日期工具（全部按 UTC 计算） ------------------------------------------------

/** 'YYYY-MM-DD' → 当天 0 点的 UTC 毫秒 */
export function parseDay(day: string): number {
  const [y, m, d] = day.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

export function addDays(ms: number, days: number): number {
  return ms + days * DAY_MS
}

/** UTC 毫秒 → 'YYYY-MM-DD' */
export function formatDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** 周一 = 0 … 周日 = 6 */
export function mondayIndex(ms: number): number {
  return (new Date(ms).getUTCDay() + 6) % 7
}

// ---- 范围 ----------------------------------------------------------------------

/**
 * 范围的起止日（UTC 毫秒，含两端）。
 * - 最近一年：一年前的同一天的后一天 … today（共 365 或 366 天）。today 是 2 月 29 日时
 *   Date.UTC 会把前一年的 2 月 29 日溢出成 3 月 1 日，起点就是 3 月 2 日；
 * - 某一年：1 月 1 日 … 12 月 31 日（today 之后的格子由 buildHeatmap 处理）。
 */
export function heatmapBounds(today: string, range: HeatmapRange): { start: number; end: number } {
  if (range === 'recent') {
    const [y, m, d] = today.split('-').map(Number)
    return { start: addDays(Date.UTC(y - 1, m - 1, d), 1), end: parseDay(today) }
  }
  return { start: Date.UTC(range, 0, 1), end: Date.UTC(range, 11, 31) }
}

// ---- 深浅等级 ------------------------------------------------------------------

/**
 * 1–4 级的分界：非零计数升序排列后的四分位数 t_k = sorted[ceil(k·n/4) − 1]（k = 1..3）。
 * 没有非零计数时返回 [0, 0, 0]。
 */
export function computeThresholds(counts: number[]): [number, number, number] {
  const sorted = counts.filter((c) => c > 0).sort((a, b) => a - b)
  const n = sorted.length
  if (n === 0) return [0, 0, 0]
  const at = (k: number) => sorted[Math.ceil((k * n) / 4) - 1]
  return [at(1), at(2), at(3)]
}

/** 0 个文件为 0 级，否则 1 + 小于该计数的分界数 */
export function levelOf(count: number, thresholds: [number, number, number]): HeatmapLevel {
  if (count <= 0) return 0
  return (1 + thresholds.filter((t) => t < count).length) as HeatmapLevel
}

// ---- 文案 ----------------------------------------------------------------------

/** '2026年10月19日：12 个文件' / '2026年10月19日：无文件' */
export function formatDayTooltip(date: string, count: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const head = `${y}年${m}月${d}日`
  return count > 0 ? `${head}：${count.toLocaleString()} 个文件` : `${head}：无文件`
}

/** '过去一年共 N 个文件' / '2025年共 N 个文件' */
export function rangeSummary(range: HeatmapRange, total: number): string {
  const prefix = range === 'recent' ? '过去一年' : `${range}年`
  return `${prefix}共 ${total.toLocaleString()} 个文件`
}

/** 有文件的年份，新的在前（年份选择器只列这些） */
export function availableYears(days: Record<string, number>): number[] {
  const years = new Set<number>()
  for (const [day, count] of Object.entries(days)) {
    if (count > 0) years.add(Number(day.slice(0, 4)))
  }
  return [...years].sort((a, b) => b - a)
}

// ---- 网格 ----------------------------------------------------------------------

/** 两个月份标签之间至少隔这么多列，否则后一个不显示（避免文字重叠） */
const MIN_LABEL_GAP = 3

export function buildHeatmap(
  days: Record<string, number>,
  today: string,
  range: HeatmapRange
): HeatmapModel {
  const { start, end } = heatmapBounds(today, range)
  // 只画到 today；如果某一年在 today 之后还有文件（相机时钟错误等），画到最后一个有文件的日子，
  // 否则年份选择器里列出的年份会显示成空网格、合计为 0
  let drawEnd = Math.min(end, parseDay(today))
  for (const [day, count] of Object.entries(days)) {
    const ms = parseDay(day)
    if (count > 0 && ms > drawEnd && ms <= end) drawEnd = ms
  }

  const countAt = (ms: number) => (ms >= start && ms <= drawEnd ? days[formatDay(ms)] ?? 0 : 0)
  const inRange: number[] = []
  for (let ms = start; ms <= drawEnd; ms = addDays(ms, 1)) inRange.push(countAt(ms))
  const thresholds = computeThresholds(inRange)
  const total = inRange.reduce((sum, c) => sum + c, 0)

  const firstMonday = addDays(start, -mondayIndex(start))
  const columns = Math.floor((addDays(end, 6 - mondayIndex(end)) - firstMonday) / (7 * DAY_MS)) + 1

  const weeks: (HeatmapCell | null)[][] = []
  const firstOfMonthCols: HeatmapMonthLabel[] = []
  for (let col = 0; col < columns; col++) {
    const week: (HeatmapCell | null)[] = []
    for (let row = 0; row < 7; row++) {
      const ms = addDays(firstMonday, col * 7 + row)
      const date = formatDay(ms)
      if (ms >= start && ms <= end && new Date(ms).getUTCDate() === 1) {
        firstOfMonthCols.push({ col, label: `${new Date(ms).getUTCMonth() + 1}月` })
      }
      if (ms < start || ms > drawEnd) {
        week.push(null)
        continue
      }
      const count = countAt(ms)
      week.push({ date, count, level: levelOf(count, thresholds), label: formatDayTooltip(date, count) })
    }
    weeks.push(week)
  }

  // 月份标签：放在包含该月 1 日的列上，离上一个标签不足 3 列的丢掉
  const monthLabels: HeatmapMonthLabel[] = []
  for (const item of firstOfMonthCols) {
    const prev = monthLabels[monthLabels.length - 1]
    if (prev && item.col - prev.col < MIN_LABEL_GAP) continue
    monthLabels.push(item)
  }
  // 起点不是 1 日时，第 0 列标上起点所在的月份（下一个标签足够远时）
  if (monthLabels[0]?.col !== 0) {
    const next = monthLabels[0]
    if (!next || next.col >= MIN_LABEL_GAP) {
      monthLabels.unshift({ col: 0, label: `${new Date(start).getUTCMonth() + 1}月` })
    }
  }

  return { weeks, monthLabels, total, start: formatDay(start), end: formatDay(end), drawEnd: formatDay(drawEnd) }
}

/** 「查看数据」表格：范围内每个月一行（从起点所在月到最后画出的那天所在月） */
export function monthlyRows(model: HeatmapModel): HeatmapMonthRow[] {
  const rows = new Map<string, HeatmapMonthRow>()
  const first = parseDay(model.start)
  const last = parseDay(model.drawEnd)
  for (let ms = first; ms <= last; ms = addDays(ms, 1)) {
    const key = formatDay(ms).slice(0, 7)
    if (!rows.has(key)) {
      const [y, m] = key.split('-').map(Number)
      rows.set(key, { key, label: `${y}年${m}月`, count: 0 })
    }
  }
  for (const week of model.weeks) {
    for (const cell of week) {
      if (cell) rows.get(cell.date.slice(0, 7))!.count += cell.count
    }
  }
  return [...rows.values()]
}
