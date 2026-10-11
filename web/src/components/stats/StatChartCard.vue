<!--
  StatChartCard —— 单个 distribution / ranking 分布的图表卡片（设计 §7.2、§7.6）。

  - chart：bar（竖向柱状）/ hbar（横向条形，排行和格式）/ doughnut（环形，类型占比）；
  - 「其他」（仅排行，other > 0）和「未知」（unknown > 0）用中性灰排在最后；
  - 排行没有任何可识别项时显示空状态说明，不画一张全灰的图；
  - allowBytes 时右上角「数量 / 存储」切换图表口径，表格同时列出两种口径；
  - 图表配置在 chartModels.ts（buildDistributionChart），Chart 实例的生命周期、键盘逐项查看
    和朗读区域在 StatChartCanvas；
  - 「查看数据」打开对话框（StatDataDialog）：上面放大显示同一张图（独立的 Chart 实例，
    同一个 model），带同一个「数量 / 存储」切换，下面是 StatDataTable。
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
      <StatChartCanvas
        v-else
        :title="title"
        :model="model"
        :height="cardChartHeight(chart, model.count)"
      />
    </div>

    <div v-if="$slots.note" class="stat-card__note">
      <slot name="note" />
    </div>

    <div v-if="!isEmptyRanking" class="stat-card__data">
      <StatDataDialog :title="`${title}数据`" :columns="table.columns" :rows="table.rows">
        <template v-if="allowBytes" #toolbar>
          <el-segmented v-model="mode" size="small" :options="MODE_OPTIONS" aria-label="占比口径" />
        </template>
        <template #chart>
          <el-empty
            v-if="isEmptyRanking"
            class="stat-dialog-empty"
            :image-size="60"
            :description="emptyText"
          />
          <StatChartCanvas
            v-else
            :title="title"
            :model="model"
            :height="dialogChartHeight(chart, model.count)"
          />
        </template>
      </StatDataDialog>
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Distribution, StatBucket } from '@/api/stats'
import { STAT_COLORS } from './chartSetup'
import { formatBytes, formatShare } from './format'
import {
  buildDistributionChart,
  cardChartHeight,
  dialogChartHeight,
  type ChartMetric,
  type ChartShape,
} from './chartModels'
import StatChartCanvas from './StatChartCanvas.vue'
import StatDataDialog from './StatDataDialog.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

type TableVariant = 'distribution' | 'overview' | 'ranking'

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

const mode = ref<ChartMetric>('count')

const isEmptyRanking = computed(
  () => props.dist.kind === 'ranking' && props.dist.buckets.length === 0
)

const metric = computed<ChartMetric>(() =>
  props.allowBytes && mode.value === 'bytes' ? 'bytes' : 'count'
)

/** 卡片和对话框共用的图表配置（各自独立的 Chart 实例） */
const model = computed(() =>
  buildDistributionChart({
    dist: props.dist,
    chart: props.chart,
    unit: props.unit,
    xLabel: props.xLabel,
    yLabel: props.yLabel,
    color: props.color,
    metric: metric.value,
  })
)

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

.stat-card__note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
}

.stat-card__data {
  margin-top: 4px;
}

/* 排行空状态说明带「未知 N 个」数据，默认 #909399 只有 3.08:1（FR-9.2） */
.stat-card :deep(.el-empty__description p) {
  color: #606266;
}

/* 对话框内容 append-to-body，不在 .stat-card 里，单独设置同样的颜色 */
.stat-dialog-empty :deep(.el-empty__description p) {
  color: #606266;
}
</style>
