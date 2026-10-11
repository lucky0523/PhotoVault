<!--
  StatTrendCard —— 拍摄趋势卡片（设计 §7.2、§7.6）。

  读 photo.monthly / photo.yearly / video.monthly / video.yearly（kind = "series"）。
  服务端保证照片和视频共用同一条月份 / 年份轴，这里按 index 直接对齐。
  - 照片：实线 + 圆点 #2F6BFF；视频：虚线 [6, 4] + 三角点 #B86E00。线型和点形状
    不同，不只靠颜色区分；
  - 某类文件数为 0 时不画该序列（图例也不出现）；两类都没有有效拍摄时间时显示空状态；
  - 月份 bucket 数 > 120（跨度超过 10 年）时默认按年显示并注明。
-->
<template>
  <figure class="pv-panel stat-card">
    <figcaption class="stat-card__title">拍摄趋势</figcaption>
    <el-segmented
      v-if="!isEmpty"
      v-model="granularity"
      class="stat-card__switch"
      size="small"
      :options="GRANULARITY_OPTIONS"
      aria-label="趋势粒度"
    />

    <div class="stat-card__body">
      <el-empty v-if="isEmpty" :image-size="60" description="没有可用的拍摄时间" />
      <template v-else>
        <div
          class="stat-card__chart"
          role="group"
          tabindex="0"
          :aria-label="`拍摄趋势：${summary}。按左右方向键逐项查看`"
          @keydown="keyboard.onKeydown"
          @blur="keyboard.onBlur"
        >
          <canvas ref="canvasRef" role="img" :aria-label="summary" />
        </div>
        <span class="stat-sr-only" aria-live="polite">{{ keyboard.liveText.value }}</span>
      </template>
    </div>

    <div class="stat-card__note">
      <p v-if="defaultedToYear && granularity === 'year'">时间跨度超过 10 年，默认按年显示</p>
      <p v-if="unknownText">{{ unknownText }}</p>
      <slot name="note" />
    </div>

    <div v-if="!isEmpty" class="stat-card__data">
      <StatDataDialog title="拍摄趋势数据" :columns="table.columns" :rows="table.rows" />
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ChartConfiguration, TooltipItem } from 'chart.js'
import type { Distribution } from '@/api/stats'
import { Chart, STAT_COLORS, prefersReducedMotion } from './chartSetup'
import { formatShare } from './format'
import { useChartKeyboard } from './useChartKeyboard'
import StatDataDialog from './StatDataDialog.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

type Granularity = 'month' | 'year'

const props = defineProps<{
  photoMonthly: Distribution
  photoYearly: Distribution
  videoMonthly: Distribution
  videoYearly: Distribution
}>()

const GRANULARITY_OPTIONS = [
  { label: '按月', value: 'month' },
  { label: '按年', value: 'year' },
]

/** 超过这么多个月默认按年显示 */
const MAX_DEFAULT_MONTHS = 120

const granularity = ref<Granularity>('month')
const defaultedToYear = ref(false)
const canvasRef = ref<HTMLCanvasElement | null>(null)
let chart: Chart<any> | null = null

// 新数据到达时重新决定默认粒度
watch(
  () => props.photoMonthly.buckets.length,
  (months) => {
    defaultedToYear.value = months > MAX_DEFAULT_MONTHS
    granularity.value = defaultedToYear.value ? 'year' : 'month'
  },
  { immediate: true }
)

interface Series {
  name: '照片' | '视频'
  unit: string
  dist: Distribution
  color: string
  dashed: boolean
  pointStyle: 'circle' | 'triangle'
}

const current = computed(() =>
  granularity.value === 'month'
    ? { photo: props.photoMonthly, video: props.videoMonthly }
    : { photo: props.photoYearly, video: props.videoYearly }
)

/** 只画文件数 > 0 的类别 */
const series = computed<Series[]>(() => {
  const list: Series[] = []
  if (current.value.photo.total > 0) {
    list.push({
      name: '照片',
      unit: '张',
      dist: current.value.photo,
      color: STAT_COLORS.photo,
      dashed: false,
      pointStyle: 'circle',
    })
  }
  if (current.value.video.total > 0) {
    list.push({
      name: '视频',
      unit: '个',
      dist: current.value.video,
      color: STAT_COLORS.video,
      dashed: true,
      pointStyle: 'triangle',
    })
  }
  return list
})

/** 共同轴：照片和视频的 bucket 完全一致，取任一方 */
const axis = computed(() => current.value.photo.buckets)

const isEmpty = computed(() => axis.value.length === 0 || series.value.length === 0)

function pointText(s: Series, index: number): string {
  const b = s.dist.buckets[index]
  return b ? `${s.name}：${b.count} ${s.unit}（${formatShare(b.share)}）` : ''
}

const summary = computed(() => {
  const parts = series.value.map((s) => `${s.name}共 ${s.dist.total} ${s.unit}`)
  const first = axis.value[0]
  const last = axis.value[axis.value.length - 1]
  if (first && last) parts.push(`时间范围 ${first.label} 至 ${last.label}`)
  return parts.join('；')
})

const unknownText = computed(() => {
  const parts: string[] = []
  if (props.photoMonthly.total > 0) parts.push(`照片 ${props.photoMonthly.unknown} 张`)
  if (props.videoMonthly.total > 0) parts.push(`视频 ${props.videoMonthly.unknown} 个`)
  return parts.length > 0 ? `未知：${parts.join('、')}（没有拍摄时间）` : ''
})

function buildConfig(): ChartConfiguration<'line'> {
  const list = series.value
  return {
    type: 'line',
    data: {
      labels: axis.value.map((b) => b.label),
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
          title: { display: true, text: granularity.value === 'month' ? '月份' : '年份' },
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

const keyboard = useChartKeyboard({
  getChart: () => chart,
  count: () => axis.value.length,
  describe: (index) => {
    const label = axis.value[index]?.label ?? ''
    const parts = series.value.map((s) => `${s.name} ${s.dist.buckets[index]?.count ?? 0} ${s.unit}`)
    return `${label}：${parts.join('，')}`
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

watch(
  [() => props.photoMonthly, () => props.videoMonthly, () => props.photoYearly, () => props.videoYearly, granularity, isEmpty],
  render,
  { flush: 'post' }
)

onBeforeUnmount(() => {
  chart?.destroy()
  chart = null
})

const table = computed<{ columns: StatTableColumn[]; rows: StatTableRow[] }>(() => {
  const list = series.value
  const columns: StatTableColumn[] = [
    { key: 'label', label: granularity.value === 'month' ? '月份' : '年份' },
    ...list.map((s) => ({ key: s.name, label: s.name, numeric: true })),
  ]
  const rows: StatTableRow[] = axis.value.map((b, index) => {
    const cells: Record<string, string | number> = { label: b.label }
    for (const s of list) cells[s.name] = s.dist.buckets[index]?.count ?? 0
    return { key: b.key, cells }
  })
  const unknownCells: Record<string, string | number> = { label: '未知' }
  const totalCells: Record<string, string | number> = { label: '合计' }
  for (const s of list) {
    unknownCells[s.name] = s.dist.unknown
    totalCells[s.name] = s.dist.total
  }
  rows.push({ key: '__unknown', cells: unknownCells })
  rows.push({ key: '__total', total: true, cells: totalCells })
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
  height: 240px;
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

.stat-card__note p {
  margin: 0;
}

.stat-card__data {
  margin-top: 4px;
}

/* 空状态说明默认 #909399 只有 3.08:1，改用 #606266（FR-9.2） */
.stat-card :deep(.el-empty__description p) {
  color: #606266;
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
