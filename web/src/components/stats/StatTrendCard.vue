<!--
  StatTrendCard —— 拍摄趋势卡片（设计 §7.2、§7.6）。

  读 photo.monthly / photo.yearly / video.monthly / video.yearly（kind = "series"）。
  服务端保证照片和视频共用同一条月份 / 年份轴，这里按 index 直接对齐。
  - 照片：实线 + 圆点 #2F6BFF；视频：虚线 [6, 4] + 三角点 #B86E00。线型和点形状
    不同，不只靠颜色区分；
  - 某类文件数为 0 时不画该序列（图例也不出现）；两类都没有有效拍摄时间时显示空状态；
  - 月份 bucket 数 > 120（跨度超过 10 年）时默认按年显示并注明；
  - 图表配置在 chartModels.ts（buildTrendChart），Chart 实例的生命周期、键盘逐项查看
    和朗读区域在 StatChartCanvas；
  - 「查看数据」对话框上面放大显示同一张图（独立的 Chart 实例，同一个 model），带同一个
    「按月 / 按年」切换，图下面是和卡片相同的说明（StatChartNote，#note 插槽原样转发），
    再下面是数据表。
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
      <StatChartCanvas
        v-else
        title="拍摄趋势"
        :model="model"
        :height="cardChartHeight('line', model.count)"
      />
    </div>

    <StatChartNote class="stat-card__note" :lines="noteLines">
      <slot name="note" />
    </StatChartNote>

    <div v-if="!isEmpty" class="stat-card__data">
      <StatDataDialog title="拍摄趋势数据" :columns="table.columns" :rows="table.rows">
        <template v-if="noteLines.length > 0 || $slots.note" #note>
          <StatChartNote :lines="noteLines">
            <slot name="note" />
          </StatChartNote>
        </template>
        <template #toolbar>
          <el-segmented
            v-model="granularity"
            size="small"
            :options="GRANULARITY_OPTIONS"
            aria-label="趋势粒度"
          />
        </template>
        <template #chart>
          <el-empty
            v-if="isEmpty"
            class="stat-dialog-empty"
            :image-size="60"
            description="没有可用的拍摄时间"
          />
          <StatChartCanvas
            v-else
            title="拍摄趋势"
            :model="model"
            :height="dialogChartHeight('line', model.count)"
          />
        </template>
      </StatDataDialog>
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Distribution } from '@/api/stats'
import {
  buildTrendChart,
  buildTrendSeries,
  cardChartHeight,
  dialogChartHeight,
  type TrendGranularity,
} from './chartModels'
import StatChartCanvas from './StatChartCanvas.vue'
import StatChartNote from './StatChartNote.vue'
import StatDataDialog from './StatDataDialog.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

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

const granularity = ref<TrendGranularity>('month')
const defaultedToYear = ref(false)

// 新数据到达时重新决定默认粒度
watch(
  () => props.photoMonthly.buckets.length,
  (months) => {
    defaultedToYear.value = months > MAX_DEFAULT_MONTHS
    granularity.value = defaultedToYear.value ? 'year' : 'month'
  },
  { immediate: true }
)

const current = computed(() =>
  granularity.value === 'month'
    ? { photo: props.photoMonthly, video: props.videoMonthly }
    : { photo: props.photoYearly, video: props.videoYearly }
)

/** 只画文件数 > 0 的类别 */
const series = computed(() => buildTrendSeries(current.value.photo, current.value.video))

/** 共同轴：照片和视频的 bucket 完全一致，取任一方 */
const axis = computed(() => current.value.photo.buckets)

const isEmpty = computed(() => axis.value.length === 0 || series.value.length === 0)

/** 卡片和对话框共用的图表配置（各自独立的 Chart 实例） */
const model = computed(() =>
  buildTrendChart({ axis: axis.value, series: series.value, granularity: granularity.value })
)

const unknownText = computed(() => {
  const parts: string[] = []
  if (props.photoMonthly.total > 0) parts.push(`照片 ${props.photoMonthly.unknown} 张`)
  if (props.videoMonthly.total > 0) parts.push(`视频 ${props.videoMonthly.unknown} 个`)
  return parts.length > 0 ? `未知：${parts.join('、')}（没有拍摄时间）` : ''
})

/**
 * 卡片和对话框共用的说明。粒度是同一个 ref，在对话框里切换粒度时
 * 「默认按年显示」的说明在两边一起出现 / 消失。
 */
const noteLines = computed(() => {
  const lines: string[] = []
  if (defaultedToYear.value && granularity.value === 'year') lines.push('时间跨度超过 10 年，默认按年显示')
  if (unknownText.value) lines.push(unknownText.value)
  return lines
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

.stat-card__note {
  margin-top: 6px;
}

.stat-card__data {
  margin-top: 4px;
}

/* 空状态说明默认 #909399 只有 3.08:1，改用 #606266（FR-9.2） */
.stat-card :deep(.el-empty__description p) {
  color: #606266;
}

/* 对话框内容 append-to-body，不在 .stat-card 里，单独设置同样的颜色 */
.stat-dialog-empty :deep(.el-empty__description p) {
  color: #606266;
}
</style>
