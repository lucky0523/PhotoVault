<!--
  StatHeatmapCard —— 每日文件数量热力图卡片（类似 GitHub 的贡献图）。

  读 overview.daily（kind = "calendar"）：服务端已按统计时区把照片和视频归到当地拍摄日期，
  并给出当地的 today，这里只做日历运算（heatmapModel.ts，纯函数）。
  - 默认显示最近一年，年份选择器只列当前统计范围内有文件的年份；新数据到达时回到最近一年；
  - 7 行（周一在上）× 53–54 列（周），手写 inline SVG，不引入图表依赖；
  - 卡片宽度 = 网格宽度（当前 pitch 下所有可选范围里最宽的一个，切换年份时卡片宽度不变）+ 左右内边距 32，
    最多 100%，不被拉宽；
  - 格子间距 pitch 是 9–14 的整数，按父元素的内容宽度 − 32（ResizeObserver）取放得下所有可选范围的
    最大值，文字大小不变：放得下时为 14，更窄时缩小 pitch，卡片仍贴着网格；pitch 9 也放不下时
    网格在自己的滚动容器里横向滚动（最近一年滚到最右，某一年滚到最左），不撑宽页面；
  - 卡片内部是网格：网格靠上，紧跟在合计下面；底部一行贴在卡片底边，卡片被拉高时多出的高度在两者之间；
  - 颜色 5 级：0 = 没有文件，1–4 按范围内非零天数的四分位数划分；浅色 / html.dark 两套色板
    用 CSS 变量定义在 .stat-heatmap 上；
  - 一个 el-tooltip（virtual-triggering）跟随悬停 / 聚焦的格子；每个格子的 aria-label 与提示相同；
  - 键盘：整张图只有一个 Tab 停靠点（roving tabindex），←/→ 前后一周，↑/↓ 前后一天，
    Home / End 到范围两端，Enter / 空格打开当天的时间线；
  - 点击或回车有文件的日子 emit select-day，由 StatisticsView 跳转到按当天筛选的时间线；
    没有文件的日子不可点击；
  - 没有拍摄时间的文件（daily.unknown）不显示、不计入合计，「统计时排除未知项」对本卡片没有影响；
  - 「查看数据」对话框是按月汇总的表格（月份 / 文件数 + 合计）。
-->
<template>
  <figure
    ref="cardRef"
    class="pv-panel stat-card stat-heatmap"
    :style="{ '--pv-heat-svg-width': `${cardSvgWidth}px` }"
  >
    <figcaption class="stat-card__title">每日文件数量</figcaption>
    <el-select
      v-model="range"
      class="stat-heatmap__range"
      size="small"
      aria-label="选择统计年份"
    >
      <el-option
        v-for="option in rangeOptions"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </el-select>
    <p class="stat-heatmap__summary">{{ summary }}</p>

    <div ref="scrollRef" class="stat-heatmap__scroll">
      <svg
        ref="svgRef"
        class="stat-heatmap__svg"
        role="group"
        aria-label="每日文件数量热力图"
        :width="layout.width"
        :height="layout.height"
        :viewBox="`0 0 ${layout.width} ${layout.height}`"
        @pointerover="onPointerOver"
        @pointerleave="hideTip"
        @focusin="onFocusIn"
        @focusout="hideTip"
        @click="onClick"
        @keydown="onKeydown"
      >
        <text
          v-for="m in model.monthLabels"
          :key="`m${m.col}`"
          class="stat-heatmap__label"
          :x="HEATMAP_GUTTER_LEFT + m.col * geo.pitch"
          :y="HEATMAP_MONTH_BASELINE"
        >{{ m.label }}</text>
        <text
          v-for="w in WEEKDAY_LABELS"
          :key="w.label"
          class="stat-heatmap__label"
          text-anchor="end"
          dominant-baseline="central"
          :x="HEATMAP_GUTTER_LEFT - 6"
          :y="HEATMAP_GUTTER_TOP + w.row * geo.pitch + geo.cell / 2"
        >{{ w.label }}</text>
        <template v-for="(week, col) in model.weeks" :key="col">
          <template v-for="(cell, row) in week" :key="row">
            <rect
              v-if="cell"
              :class="['stat-heatmap__cell', `lv-${cell.level}`, { 'has-files': cell.count > 0 }]"
              :x="HEATMAP_GUTTER_LEFT + col * geo.pitch + 0.5"
              :y="HEATMAP_GUTTER_TOP + row * geo.pitch + 0.5"
              :width="geo.cell - 1"
              :height="geo.cell - 1"
              rx="2"
              ry="2"
              role="button"
              :data-date="cell.date"
              :aria-label="cell.count > 0 ? `${cell.label}，按 Enter 在时间线中查看` : cell.label"
              :aria-disabled="cell.count > 0 ? undefined : 'true'"
              :tabindex="cell.date === focusDate ? 0 : -1"
            />
          </template>
        </template>
      </svg>
    </div>
    <el-tooltip
      ref="tipRef"
      :visible="tipVisible"
      :virtual-ref="tipTarget"
      virtual-triggering
      :content="tipText"
      placement="top"
      :offset="6"
      popper-class="stat-heatmap-tip"
    />

    <!-- 说明文字只放在「查看数据」弹窗里；底部一行左边「查看数据」、右边图例 -->
    <div class="stat-heatmap__footer">
      <div class="stat-card__data">
        <StatDataDialog title="每日文件数量数据" :columns="TABLE_COLUMNS" :rows="tableRows">
          <template #note>
            <StatChartNote :lines="NOTE_LINES">
              <slot name="note" />
            </StatChartNote>
          </template>
        </StatDataDialog>
      </div>
      <div class="stat-heatmap__legend">
        <span>少</span>
        <span
          v-for="level in LEVELS"
          :key="level"
          class="stat-heatmap__swatch"
          :class="`lv-${level}`"
          aria-hidden="true"
        />
        <span>多</span>
      </div>
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { TooltipInstance } from 'element-plus'
import type { DailyCounts } from '@/api/stats'
import {
  HEATMAP_GUTTER_LEFT,
  HEATMAP_GUTTER_TOP,
  HEATMAP_MONTH_BASELINE,
  addDays,
  availableYears,
  buildHeatmap,
  fitHeatmapPitch,
  formatDay,
  heatmapGeometry,
  heatmapSvgHeight,
  heatmapSvgWidth,
  monthlyRows,
  parseDay,
  rangeSummary,
  type HeatmapCell,
  type HeatmapRange,
} from './heatmapModel'
import StatChartNote from './StatChartNote.vue'
import StatDataDialog from './StatDataDialog.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

const props = defineProps<{ daily: DailyCounts }>()
const emit = defineEmits<{ 'select-day': [date: string] }>()

const WEEKDAY_LABELS = [
  { row: 0, label: '周一' },
  { row: 2, label: '周三' },
  { row: 4, label: '周五' },
]
const LEVELS = [0, 1, 2, 3, 4]
const NOTE_LINES = ['按拍摄日期（当地时间）统计照片和视频；点击日期可在时间线中查看']
const TABLE_COLUMNS: StatTableColumn[] = [
  { key: 'label', label: '月份' },
  { key: 'count', label: '文件数', numeric: true },
]

const range = ref<HeatmapRange>('recent')

// 新数据（切换目录 / 刷新）到达时回到最近一年
watch(
  () => props.daily.days,
  () => {
    range.value = 'recent'
  }
)

const rangeOptions = computed(() => [
  { label: '最近一年', value: 'recent' as HeatmapRange },
  ...availableYears(props.daily.days).map((y) => ({ label: `${y}年`, value: y as HeatmapRange })),
])

const model = computed(() => buildHeatmap(props.daily.days, props.daily.today, range.value))
const summary = computed(() => rangeSummary(range.value, model.value.total))

/**
 * 所有可选范围的网格（只依赖 props，切换范围时不重建）：pitch 按其中最宽的一个取，
 * 53 / 54 列的年份之间切换时格子大小不变。
 */
const fitModels = computed(() =>
  rangeOptions.value.map((o) => buildHeatmap(props.daily.days, props.daily.today, o.value))
)

/** 卡片左右内边距之和（padding: 12px 16px） */
const CARD_PADDING_X = 32

/**
 * 网格的可用宽度 = 父元素内容宽度 − 卡片内边距（ResizeObserver 更新；挂载前按无限宽取最大 pitch）。
 * 量父元素而不是卡片自己：卡片宽度随 pitch 变化，量自己的话窗口变宽后会停在较小的 pitch。
 */
const available = ref(Infinity)
const pitch = computed(() => fitHeatmapPitch(fitModels.value, available.value))
const geo = computed(() => heatmapGeometry(pitch.value))

/** 卡片宽度用的网格宽度：当前 pitch 下所有可选范围里最宽的一个，切换年份时卡片宽度不变 */
const cardSvgWidth = computed(() =>
  Math.max(...fitModels.value.map((m) => heatmapSvgWidth(m, geo.value)))
)

const layout = computed(() => ({
  width: heatmapSvgWidth(model.value, geo.value),
  height: heatmapSvgHeight(geo.value),
}))

/** date → 格子，悬停 / 键盘查找用 */
const cellByDate = computed(() => {
  const map = new Map<string, HeatmapCell>()
  for (const week of model.value.weeks) for (const cell of week) if (cell) map.set(cell.date, cell)
  return map
})

const tableRows = computed<StatTableRow[]>(() => [
  ...monthlyRows(model.value).map((r) => ({ key: r.key, cells: { label: r.label, count: r.count } })),
  { key: '__total', total: true, cells: { label: '合计', count: model.value.total } },
])

// ---- 滚动位置与键盘焦点 ----

const cardRef = ref<HTMLElement>()
const scrollRef = ref<HTMLElement>()
const svgRef = ref<SVGSVGElement>()
/** roving tabindex：唯一 tabindex=0 的格子 */
const focusDate = ref('')

// 最近一年从今天开始，某一年从 1 月 1 日开始
watch(
  model,
  (m) => {
    focusDate.value = range.value === 'recent' ? m.drawEnd : m.start
  },
  { immediate: true }
)

// 最近一年滚到最右、某一年滚到最左；宽度变化换了 pitch 时重新滚动，但不重置键盘焦点
watch(
  [model, pitch],
  async () => {
    await nextTick()
    const el = scrollRef.value
    if (el) el.scrollLeft = range.value === 'recent' ? el.scrollWidth : 0
  },
  { immediate: true, flush: 'post' }
)

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  const parent = cardRef.value?.parentElement
  if (!parent) return
  const cs = getComputedStyle(parent)
  available.value =
    Math.floor(parent.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) -
    CARD_PADDING_X
  resizeObserver = new ResizeObserver(([entry]) => {
    available.value = Math.floor(entry.contentRect.width) - CARD_PADDING_X
  })
  resizeObserver.observe(parent)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

function cellElement(target: EventTarget | null): SVGRectElement | null {
  const el = target as Element | null
  return el && typeof el.closest === 'function' ? el.closest<SVGRectElement>('rect[data-date]') : null
}

const KEY_STEPS: Record<string, number> = { ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1 }

async function moveFocus(date: string) {
  if (!cellByDate.value.has(date)) return
  focusDate.value = date
  await nextTick()
  svgRef.value?.querySelector<SVGRectElement>(`rect[data-date="${date}"]`)?.focus()
}

function openDay(date: string | null | undefined) {
  if (!date) return
  const cell = cellByDate.value.get(date)
  if (cell && cell.count > 0) emit('select-day', date)
}

function onKeydown(event: KeyboardEvent) {
  const el = cellElement(event.target)
  const date = el?.dataset.date
  if (!date) return
  if (event.key in KEY_STEPS) {
    event.preventDefault()
    moveFocus(formatDay(addDays(parseDay(date), KEY_STEPS[event.key])))
  } else if (event.key === 'Home') {
    event.preventDefault()
    moveFocus(model.value.start)
  } else if (event.key === 'End') {
    event.preventDefault()
    moveFocus(model.value.drawEnd)
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    openDay(date)
  }
}

function onClick(event: MouseEvent) {
  openDay(cellElement(event.target)?.dataset.date)
}

// ---- 提示 ----

const tipRef = ref<TooltipInstance>()
const tipTarget = ref<HTMLElement>()
const tipText = ref('')
const tipVisible = ref(false)

async function showTip(el: SVGRectElement) {
  const cell = cellByDate.value.get(el.dataset.date ?? '')
  if (!cell) return
  tipTarget.value = el as unknown as HTMLElement
  tipText.value = cell.label
  tipVisible.value = true
  await nextTick()
  tipRef.value?.updatePopper()
}

function hideTip() {
  tipVisible.value = false
}

function onPointerOver(event: PointerEvent) {
  const el = cellElement(event.target)
  if (el) showTip(el)
  else hideTip()
}

function onFocusIn(event: FocusEvent) {
  const el = cellElement(event.target)
  if (!el) return
  focusDate.value = el.dataset.date ?? focusDate.value
  showTip(el)
}
</script>

<style scoped>
/* 色板（GitHub 配色）：浅色 */
.stat-heatmap {
  --pv-heat-0: #EBEDF0;
  --pv-heat-1: #9BE9A8;
  --pv-heat-2: #40C463;
  --pv-heat-3: #30A14E;
  --pv-heat-4: #216E39;
  --pv-heat-stroke: rgba(27, 31, 36, 0.06);
  --pv-heat-label: var(--el-text-color-regular);

  margin: 0;
  padding: 12px 16px;
  box-sizing: border-box;
  /* 宽度贴着网格（--pv-heat-svg-width 由脚本按当前 pitch 设置）+ 左右内边距，不被拉宽 */
  width: calc(var(--pv-heat-svg-width) + 32px);
  max-width: 100%;
  min-width: 0;
  /* 卡片很窄时（@container 见下）年份选择器换到第 2 行，标题不被挤成一列一个字 */
  container-type: inline-size;
  /*
   * 行：1 标题 + 年份选择器、2 窄卡片时的年份选择器（宽卡片时为空、高度 0）、3 合计、
   * 4 热力图（1fr，网格靠上；被拉高时多出的高度在网格下面）、5 底部一行（贴在卡片底边）
   */
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto auto auto 1fr auto;
  column-gap: 8px;
  row-gap: 0;
  align-items: center;
}

/* 暗色：html.dark（Element Plus 暗色变量）下换色板和卡片底色 */
html.dark .stat-heatmap {
  --pv-heat-0: #2A2E35;
  --pv-heat-1: #0E4429;
  --pv-heat-2: #006D32;
  --pv-heat-3: #26A641;
  --pv-heat-4: #39D353;
  --pv-heat-stroke: rgba(255, 255, 255, 0.05);
  background: var(--el-bg-color-overlay);
}

.stat-heatmap > * {
  min-width: 0;
}

/* 标题和年份选择器共用第一行，其余各占一整行 */
.stat-card__title {
  grid-row: 1;
  grid-column: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.stat-heatmap > .stat-heatmap__range {
  grid-row: 1;
  grid-column: 2;
  width: 112px;
  max-width: 100%;
  justify-self: end;
}

.stat-heatmap__summary {
  grid-row: 3;
  grid-column: 1 / -1;
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--pv-heat-label);
  font-variant-numeric: tabular-nums;
}

/* 网格自己横向滚动，不撑宽卡片和页面；靠上，被拉高时多出的高度在网格和底部一行之间 */
.stat-heatmap__scroll {
  grid-row: 4;
  grid-column: 1 / -1;
  align-self: start;
  margin: 8px 0 6px;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
}

/* 标题（约 84px）+ 间距 + 选择器（112px）放不下时，选择器换到标题下一行 */
@container (max-width: 210px) {
  .stat-heatmap > .stat-card__title {
    grid-column: 1 / -1;
  }

  .stat-heatmap > .stat-heatmap__range {
    grid-row: 2;
    grid-column: 1 / -1;
    margin-top: 4px;
  }
}

.stat-heatmap__svg {
  display: block;
}

.stat-heatmap__label {
  font-size: 12px;
  fill: var(--pv-heat-label);
  user-select: none;
}

.stat-heatmap__cell {
  stroke: var(--pv-heat-stroke);
  stroke-width: 1px;
  outline: none;
}

.stat-heatmap__cell.has-files {
  cursor: pointer;
}

/* 键盘焦点：主题色描边（SVG 元素的 outline 不可靠） */
.stat-heatmap__cell:focus-visible {
  stroke: var(--el-color-primary);
  stroke-width: 2px;
}

.lv-0 { fill: var(--pv-heat-0); background: var(--pv-heat-0); }
.lv-1 { fill: var(--pv-heat-1); background: var(--pv-heat-1); }
.lv-2 { fill: var(--pv-heat-2); background: var(--pv-heat-2); }
.lv-3 { fill: var(--pv-heat-3); background: var(--pv-heat-3); }
.lv-4 { fill: var(--pv-heat-4); background: var(--pv-heat-4); }

.stat-heatmap__footer {
  grid-row: 5;
  grid-column: 1 / -1;
  align-self: end;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 16px;
}

/* StatChartNote 默认 #606266（= 浅色的 --el-text-color-regular），暗色下跟随主题变量 */
.stat-heatmap :deep(.stat-chart-note) {
  color: var(--pv-heat-label);
}

.stat-heatmap__legend {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--pv-heat-label);
  white-space: nowrap;
}

.stat-heatmap__legend > span:first-child {
  margin-right: 2px;
}

.stat-heatmap__legend > span:last-child {
  margin-left: 2px;
}

.stat-heatmap__swatch {
  width: 11px;
  height: 11px;
  border-radius: 2px;
  box-sizing: border-box;
  box-shadow: inset 0 0 0 1px var(--pv-heat-stroke);
}
</style>

<style>
/* 提示框挂在 body 上（非 scoped）：不拦截指针，鼠标移到被提示框盖住的格子上也能继续悬停 */
.stat-heatmap-tip {
  pointer-events: none;
}
</style>
