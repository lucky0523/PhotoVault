<!--
  StatChartCard —— 单个 distribution / ranking 分布的图表卡片（设计 §7.2、§7.6）。

  - chart：bar（竖向柱状）/ hbar（横向条形，排行和格式）/ doughnut（环形，类型占比）；
  - 「其他」（仅排行，other > 0）和「未知」（unknown > 0）用中性灰排在最后；
  - 排行没有任何可识别项时显示空状态说明，不画一张全灰的图；
  - allowBytes 时右上角「数量 / 存储」切换图表口径，表格同时列出两种口径；
  - 键盘逐项查看见 useChartKeyboard，「查看数据」展开 StatDataTable。

  不用 vue-chartjs：onMounted 创建、数据变化 update()、卸载前 destroy()。
  图表实例放在普通变量里，不能放进 ref —— Vue 的响应式代理会破坏 Chart.js 的内部状态。
-->
<template>
  <figure class="pv-panel stat-card">
    <figcaption class="stat-card__title">{{ title }}</figcaption>
    <el-segmented
      v-if="allowBytes && !isEmptyRanking"
      v-model="mode"
      class="stat-card__switch"
      size="small"
      :options="MODE_OPTIONS"
      aria-label="占比口径"
    />

    <div class="stat-card__body">
      <el-empty
        v-if="isEmptyRanking"
        :image-size="60"
        :description="emptyText"
      />
      <template v-else>
        <div
          class="stat-card__chart"
          role="group"
          tabindex="0"
          :style="{ height: `${chartHeight}px` }"
          :aria-label="`${title}：${summary}。按左右方向键逐项查看`"
          @keydown="keyboard.onKeydown"
          @blur="keyboard.onBlur"
        >
          <canvas ref="canvasRef" role="img" :aria-label="summary" />
        </div>
        <span class="stat-sr-only" aria-live="polite">{{ keyboard.liveText.value }}</span>
      </template>
    </div>

    <div v-if="$slots.note" class="stat-card__note">
      <slot name="note" />
    </div>

    <div v-if="!isEmptyRanking" class="stat-card__data">
      <el-button
        link
        type="primary"
        :aria-expanded="showTable ? 'true' : 'false'"
        :aria-controls="tableId"
        @click="showTable = !showTable"
      >
        {{ showTable ? '收起数据' : '查看数据' }}
      </el-button>
      <StatDataTable
        v-show="showTable"
        :id="tableId"
        :caption="`${title}数据`"
        :columns="table.columns"
        :rows="table.rows"
      />
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import type { ChartConfiguration, TooltipItem } from 'chart.js'
import type { Distribution, StatBucket } from '@/api/stats'
import { Chart, STAT_COLORS, STAT_TEXT_COLOR, prefersReducedMotion } from './chartSetup'
import { formatBytes, formatShare } from './format'
import { useChartKeyboard } from './useChartKeyboard'
import StatDataTable from './StatDataTable.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

type ChartShape = 'bar' | 'hbar' | 'doughnut'
type TableVariant = 'distribution' | 'overview' | 'ranking'
type Mode = 'count' | 'bytes'

const props = withDefaults(
  defineProps<{
    title: string
    dist: Distribution
    chart: ChartShape
    /** 数量单位：张 / 个 */
    unit: string
    xLabel?: string
    yLabel?: string
    /** 表格列配置 */
    tableVariant?: TableVariant
    /** 显示「数量 / 存储」切换（只用于总览类型 / 格式） */
    allowBytes?: boolean
    /** 普通柱的颜色 */
    color?: string
    /** 排行没有可识别项时的说明 */
    emptyText?: string
  }>(),
  {
    xLabel: '',
    yLabel: '',
    tableVariant: 'distribution',
    allowBytes: false,
    color: STAT_COLORS.photo,
    emptyText: '暂无数据',
  }
)

const MODE_OPTIONS = [
  { label: '数量', value: 'count' },
  { label: '存储', value: 'bytes' },
]

/** 环形图（类型占比）按类别取色 */
const KEY_COLORS: Record<string, string> = {
  photo: STAT_COLORS.photo,
  video: STAT_COLORS.video,
  other: STAT_COLORS.other,
}

const LABEL_MAX_CHARS = 18

const mode = ref<Mode>('count')
const showTable = ref(false)
const tableId = `stat-table-${useId()}`
const canvasRef = ref<HTMLCanvasElement | null>(null)
let chart: Chart<any> | null = null

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

const isEmptyRanking = computed(
  () => props.dist.kind === 'ranking' && props.dist.buckets.length === 0
)

const items = computed<ChartItem[]>(() => {
  const d = props.dist
  const list: ChartItem[] = d.buckets.map((b) => ({
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
    list.push({
      key: '__other',
      label: '其他',
      count: d.other ?? 0,
      share: d.other_share ?? 0,
      bytes: 0,
      bytesShare: 0,
      neutral: true,
    })
  }
  if (d.unknown > 0) {
    list.push({
      key: '__unknown',
      label: '未知',
      count: d.unknown,
      share: d.unknown_share,
      bytes: 0,
      bytesShare: 0,
      neutral: true,
    })
  }
  return list
})

const isBytes = computed(() => props.allowBytes && mode.value === 'bytes')

function valueOf(item: ChartItem): number {
  return isBytes.value ? item.bytes : item.count
}

function shareOf(item: ChartItem): number {
  return isBytes.value ? item.bytesShare : item.share
}

function formatValue(value: number): string {
  return isBytes.value ? formatBytes(value) : `${value} ${props.unit}`
}

function describeItem(item: ChartItem): string {
  const text = `${item.label}：${formatValue(valueOf(item))}（${formatShare(shareOf(item))}）`
  return item.range ? `${text}，区间 ${item.range}` : text
}

const summary = computed(() => {
  const d = props.dist
  const parts: string[] = [
    isBytes.value ? `共 ${formatBytes(d.total_bytes ?? 0)}` : `共 ${d.total} ${props.unit}`,
  ]
  let top: ChartItem | null = null
  for (const item of items.value) {
    if (item.neutral || valueOf(item) <= 0) continue
    if (!top || valueOf(item) > valueOf(top)) top = item
  }
  if (top) {
    parts.push(`最多：${top.label} ${formatValue(valueOf(top))}（${formatShare(shareOf(top))}）`)
  }
  if (d.unknown > 0) parts.push(`未知 ${d.unknown} ${props.unit}`)
  return parts.join('；')
})

/** 横向条形图的高度随条数变化，竖向图和环形图固定 240px */
const chartHeight = computed(() =>
  props.chart === 'hbar' ? Math.max(160, 28 * items.value.length + 48) : 240
)

function truncate(label: string): string {
  const chars = Array.from(label)
  return chars.length > LABEL_MAX_CHARS ? `${chars.slice(0, LABEL_MAX_CHARS).join('')}…` : label
}

function colorOf(item: ChartItem, index: number): string {
  if (item.neutral) return STAT_COLORS.unknown
  if (props.chart === 'doughnut') {
    const palette = [STAT_COLORS.photo, STAT_COLORS.video, STAT_COLORS.other]
    return KEY_COLORS[item.key] ?? palette[index % palette.length]
  }
  return props.color
}

function buildConfig(): ChartConfiguration<any> {
  const list = items.value
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

  if (props.chart === 'doughnut') {
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

  const horizontal = props.chart === 'hbar'
  const valueAxis = horizontal ? 'x' : 'y'
  const xTitle = isBytes.value && valueAxis === 'x' ? '存储' : props.xLabel
  const yTitle = isBytes.value && valueAxis === 'y' ? '存储' : props.yLabel
  const valueTicks = isBytes.value
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

const keyboard = useChartKeyboard({
  getChart: () => chart,
  count: () => items.value.length,
  describe: (index) => {
    const item = items.value[index]
    return item ? describeItem(item) : ''
  },
})

function render() {
  const canvas = canvasRef.value
  if (!canvas) {
    chart?.destroy()
    chart = null
    return
  }
  keyboard.reset()
  const config = buildConfig()
  if (chart && chart.canvas === canvas) {
    chart.setActiveElements([])
    chart.tooltip?.setActiveElements([], { x: 0, y: 0 })
    chart.data = config.data
    chart.options = config.options ?? {}
    chart.update()
    return
  }
  chart?.destroy()
  chart = new Chart(canvas, config)
}

onMounted(render)

watch([() => props.dist, mode, isEmptyRanking], render, { flush: 'post' })

onBeforeUnmount(() => {
  chart?.destroy()
  chart = null
})

// ---- 「查看数据」表格 ------------------------------------------------------

/** 合计行的占比：有数据时 100%，否则 0% */
function totalShare(total: number): string {
  return formatShare(total > 0 ? 100 : 0)
}

const table = computed<{ columns: StatTableColumn[]; rows: StatTableRow[] }>(() => {
  const d = props.dist
  const rows: StatTableRow[] = []

  if (props.tableVariant === 'overview') {
    const columns: StatTableColumn[] = [
      { key: 'label', label: '分组' },
      { key: 'count', label: '数量', numeric: true },
      { key: 'share', label: '占比', numeric: true },
      { key: 'bytes', label: '存储', numeric: true },
      { key: 'bytesShare', label: '存储占比', numeric: true, className: 'col-secondary' },
    ]
    const toRow = (b: StatBucket, child: boolean, parentKey = ''): StatTableRow => ({
      key: `${parentKey}${b.key}`,
      child,
      cells: {
        label: b.label,
        count: b.count,
        share: formatShare(b.share),
        bytes: formatBytes(b.bytes ?? 0),
        bytesShare: formatShare(b.bytes_share ?? 0),
      },
    })
    for (const b of d.buckets) {
      rows.push(toRow(b, false))
      for (const c of b.children ?? []) rows.push(toRow(c, true, `${b.key}/`))
    }
    if (d.unknown > 0) {
      rows.push({
        key: '__unknown',
        cells: { label: '未知', count: d.unknown, share: formatShare(d.unknown_share), bytes: '—', bytesShare: '—' },
      })
    }
    rows.push({
      key: '__total',
      total: true,
      cells: {
        label: '合计',
        count: d.total,
        share: totalShare(d.total),
        bytes: formatBytes(d.total_bytes ?? 0),
        bytesShare: formatShare((d.total_bytes ?? 0) > 0 ? 100 : 0),
      },
    })
    return { columns, rows }
  }

  if (props.tableVariant === 'ranking') {
    const columns: StatTableColumn[] = [
      { key: 'label', label: '名称' },
      { key: 'count', label: '数量', numeric: true },
      { key: 'share', label: '占比', numeric: true },
    ]
    for (const b of d.buckets) {
      rows.push({ key: b.key, cells: { label: b.label, count: b.count, share: formatShare(b.share) } })
    }
    rows.push({
      key: '__other',
      cells: { label: '其他', count: d.other ?? 0, share: formatShare(d.other_share ?? 0) },
    })
    rows.push({
      key: '__unknown',
      cells: { label: '未知', count: d.unknown, share: formatShare(d.unknown_share) },
    })
    rows.push({
      key: '__total',
      total: true,
      cells: { label: '合计', count: d.total, share: totalShare(d.total) },
    })
    return { columns, rows }
  }

  // distribution
  const hasRange = d.buckets.some((b) => b.range)
  const columns: StatTableColumn[] = [
    { key: 'label', label: '分组' },
    ...(hasRange ? [{ key: 'range', label: '区间', className: 'col-range' }] : []),
    { key: 'count', label: '数量', numeric: true },
    { key: 'share', label: '占比', numeric: true },
  ]
  for (const b of d.buckets) {
    rows.push({
      key: b.key,
      cells: { label: b.label, range: b.range ?? '—', count: b.count, share: formatShare(b.share) },
    })
    for (const c of b.children ?? []) {
      rows.push({
        key: `${b.key}/${c.key}`,
        child: true,
        cells: { label: c.label, range: c.range ?? '—', count: c.count, share: formatShare(c.share) },
      })
    }
  }
  rows.push({
    key: '__unknown',
    cells: { label: '未知', range: '—', count: d.unknown, share: formatShare(d.unknown_share) },
  })
  rows.push({
    key: '__total',
    total: true,
    cells: { label: '合计', range: '—', count: d.total, share: totalShare(d.total) },
  })
  return { columns, rows }
})
</script>

<style scoped>
.stat-card {
  margin: 0;
  padding: 12px 16px;
  box-sizing: border-box;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 8px;
}

.stat-card > * {
  grid-column: 1 / -1;
  min-width: 0;
}

.stat-card__title {
  grid-column: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.stat-card > .stat-card__switch {
  grid-column: 2;
}

.stat-card__body {
  margin-top: 8px;
}

.stat-card__chart {
  position: relative;
  width: 100%;
  border-radius: 4px;
}

.stat-card__chart:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.stat-card__note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
}

.stat-card__data {
  margin-top: 4px;
}

.stat-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
