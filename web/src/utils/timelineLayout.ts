/**
 * 时间线窗口化渲染的布局计算（纯函数）。
 *
 * 服务端的 summary 给出了每个月的精确张数，所以整条时间线的高度不需要测量任何
 * 瓦片就能算出来：每个月 = 分组标题 + rows 行正方形瓦片。页面只渲染视口附近的
 * 行，其余位置靠容器高度撑开，DOM 数量因此与图库大小无关。
 *
 * 列数与瓦片宽度的算法与 CSS `repeat(auto-fill, minmax(tileMin, 1fr))` 相同，
 * 这样算出来的位置和浏览器实际排出来的网格逐像素对齐。
 */

export interface MonthCount {
  key: string
  count: number
}

export interface SectionLayout {
  key: string
  /** 区块顶部相对 .photos-content 的偏移 */
  top: number
  /** 分组标题 + 全部瓦片行的高度（不含分组间距） */
  height: number
  rows: number
}

export interface TimelineLayout {
  cols: number
  tile: number
  /** 行距 = 瓦片边长 + 间距 */
  pitch: number
  gap: number
  headerBlock: number
  groupGap: number
  sections: SectionLayout[]
  /** 底部垫高：保证最后一个月（哪怕只有一行）也能滚到视口顶部 */
  bottomSpacer: number
  /** .photos-content 的总高度（含底部垫高） */
  totalHeight: number
}

/** 列数与瓦片边长；与 CSS auto-fill + minmax(tileMin, 1fr) 的结果一致。 */
export function computeColumns(
  width: number,
  tileMin: number,
  gap: number
): { cols: number; tile: number } {
  const cols = Math.max(1, Math.floor((width + gap) / (tileMin + gap)))
  // 窄于一列最小宽度时，CSS 给出一列 tileMin 宽并横向溢出
  const tile = cols === 1 ? Math.max(width, tileMin) : (width - (cols - 1) * gap) / cols
  return { cols, tile }
}

/**
 * 计算每个月份区块的位置与高度。
 *
 * months 为年月倒序、count > 0 的月份。viewportHeight 是滚动容器的 clientHeight，
 * bodyPaddingBottom 是滚动容器的下内边距，二者用于底部垫高：
 * 跳到最后一个月时目标 scrollTop = offsetTop + last.top，必须不超过
 * scrollHeight − clientHeight，所以总高至少是 last.top + clientHeight − paddingBottom。
 */
export function buildLayout(
  months: ReadonlyArray<MonthCount>,
  width: number,
  viewportHeight: number,
  headerBlock: number,
  tileMin: number,
  gap: number,
  groupGap = 24,
  bodyPaddingBottom = 0
): TimelineLayout {
  const { cols, tile } = computeColumns(width, tileMin, gap)
  const pitch = tile + gap
  const sections: SectionLayout[] = []
  let top = 0
  for (const m of months) {
    const rows = Math.ceil(m.count / cols)
    const height = headerBlock + rows * tile + Math.max(0, rows - 1) * gap
    sections.push({ key: m.key, top, height, rows })
    top += height + groupGap
  }
  const last = sections[sections.length - 1]
  const bottomSpacer = last ? Math.max(0, viewportHeight - last.height - bodyPaddingBottom) : 0
  const totalHeight = last ? last.top + last.height + bottomSpacer : 0
  return { cols, tile, pitch, gap, headerBlock, groupGap, sections, bottomSpacer, totalHeight }
}

/**
 * 与区间 [from, to) 相交的区块下标范围 [start, end)（二分查找，区块按 top 递增）。
 */
export function findVisibleSections(
  layout: TimelineLayout,
  from: number,
  to: number
): [number, number] {
  const s = layout.sections
  // 第一个底边 > from 的区块
  let lo = 0
  let hi = s.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (s[mid].top + s[mid].height > from) hi = mid
    else lo = mid + 1
  }
  const start = lo
  // 第一个顶边 >= to 的区块
  lo = start
  hi = s.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (s[mid].top >= to) hi = mid
    else lo = mid + 1
  }
  return [start, lo]
}

/** [from, to) 内所有连续未加载的下标段 [s, e)，按下标递增。 */
export function missingRuns(
  items: ReadonlyArray<unknown>,
  from: number,
  to: number
): Array<[number, number]> {
  const runs: Array<[number, number]> = []
  const end = Math.min(to, items.length)
  let i = Math.max(0, from)
  while (i < end) {
    if (items[i] !== undefined) {
      i++
      continue
    }
    const s = i
    while (i < end && items[i] === undefined) i++
    runs.push([s, i])
  }
  return runs
}
