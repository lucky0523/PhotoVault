<!--
  StatChartCard —— 单个 distribution / ranking 分布的图表卡片（设计 §7.2、§7.6）。

  - chart：bar（竖向柱状）/ hbar（横向条形，排行）/ stackedBar（100% 堆积条形，类型占比、格式占比，
    卡片外框更紧凑）/
    line（折线，拍摄星期 / 小时分布；「未知」不是时间轴上的一点，只出现在说明和表格里）；
  - 「其他」（仅排行，other > 0）和「未知」（unknown > 0）用中性灰排在最后；
  - excludeUnknown（「统计时排除未知项」，总览卡片不传）：先经 applyUnknownPolicy 去掉「未知」、
    按已知项重新计算占比，图表、对话框和表格都用这份结果；说明里注明排除了多少；
  - 排行没有任何可识别项、或排除未知后没有任何已知项时显示空状态说明，不画一张全灰 / 全 0 的图；
  - emptyWhenNoKnown（场景亮度）：没有任何已知项时不论开关状态都显示 emptyText 空状态，
    卡片自带的说明行清空，#note 插槽照常显示；
  - allowBytes 时右上角「数量 / 存储」切换图表口径，表格同时列出两种口径；
  - legendInFooter（类型占比、格式占比）：堆积条形图的图例不画在图下，放到卡片底部一行
    「查看数据」的右侧，右对齐、项多时在按钮右侧换行；底部一行贴在卡片底边（同一行两张卡片
    等高时图例在右下角）。对话框里的图例仍在图下；
  - 图表配置在 chartModels.ts（buildDistributionChart），Chart 实例的生命周期、键盘逐项查看
    和朗读区域在 StatChartCanvas；
  - 「查看数据」打开对话框（StatDataDialog）：上面放大显示同一张图（独立的 Chart 实例，
    同一个 model），带同一个「数量 / 存储」切换，图下面是和卡片相同的 #note 说明
    （StatChartNote，插槽原样转发），再下面是 StatDataTable。
-->
<template>
  <figure
    class="pv-panel stat-card"
    :class="{ 'stat-card--compact': chart === 'stackedBar', 'stat-card--footer-legend': legendInFooter }"
  >
    <figcaption class="stat-card__title">{{ title }}</figcaption>
    <el-segmented
      v-if="allowBytes && !isEmpty"
      v-model="mode"
      class="stat-card__switch"
      size="small"
      :options="MODE_OPTIONS"
      aria-label="占比口径"
    />

    <div class="stat-card__body">
      <el-empty
        v-if="isEmpty"
        :image-size="60"
        :description="emptyDescription"
      />
      <StatChartCanvas
        v-else
        :title="title"
        :model="model"
        :height="cardChartHeight(chart, model.count)"
        :hide-legend="legendInFooter"
      />
    </div>

    <StatChartNote v-if="noteLines.length > 0 || $slots.note" class="stat-card__note" :lines="noteLines">
      <slot name="note" />
    </StatChartNote>

    <div v-if="!isEmpty" class="stat-card__data" :class="{ 'is-footer': legendInFooter }">
      <StatDataDialog :title="`${title}数据`" :columns="table.columns" :rows="table.rows">
        <template v-if="noteLines.length > 0 || $slots.note" #note>
          <StatChartNote :lines="noteLines">
            <slot name="note" />
          </StatChartNote>
        </template>
        <template v-if="allowBytes" #toolbar>
          <el-segmented v-model="mode" size="small" :options="MODE_OPTIONS" aria-label="占比口径" />
        </template>
        <template #chart>
          <el-empty
            v-if="isEmpty"
            class="stat-dialog-empty"
            :image-size="60"
            :description="emptyDescription"
          />
          <StatChartCanvas
            v-else
            :title="title"
            :model="model"
            :height="dialogChartHeight(chart, model.count)"
          />
        </template>
      </StatDataDialog>
      <StatChartLegend
        v-if="legendInFooter && model.legend?.length"
        class="stat-card__legend"
        :items="model.legend"
      />
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Distribution, StatBucket } from '@/api/stats'
import { STAT_COLORS } from './chartSetup'
import { formatBytes, formatShare } from './format'
import {
  applyUnknownPolicy,
  buildDistributionChart,
  cardChartHeight,
  dialogChartHeight,
  type ChartMetric,
  type ChartShape,
} from './chartModels'
import StatChartCanvas from './StatChartCanvas.vue'
import StatChartLegend from './StatChartLegend.vue'
import StatChartNote from './StatChartNote.vue'
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
    /** 统计时排除「未知」（照片 / 视频卡片；总览卡片不受影响） */
    excludeUnknown?: boolean
    /** 没有任何已知项时（不论是否排除未知）显示 emptyText 空状态 */
    emptyWhenNoKnown?: boolean
    /** 图例放在底部一行「查看数据」右侧（类型占比 / 格式占比） */
    legendInFooter?: boolean
  }>(),
  {
    xLabel: '',
    yLabel: '',
    tableVariant: 'distribution',
    allowBytes: false,
    color: STAT_COLORS.photo,
    emptyText: '暂无数据',
    excludeUnknown: false,
    emptyWhenNoKnown: false,
    legendInFooter: false,
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

/** 按「统计时排除未知项」处理后的分布：图表、对话框和表格共用 */
const view = computed(() => applyUnknownPolicy(props.dist, props.excludeUnknown))

/** 排除未知后没有任何已知项（例如全是没有 EXIF 的截图）：和空排行一样显示空状态，不画全 0 的图 */
const isAllUnknownExcluded = computed(() => view.value.excludedUnknown > 0 && view.value.total === 0)

/** emptyWhenNoKnown 时没有任何已知项（不看开关状态） */
const isNoKnown = computed(
  () => props.emptyWhenNoKnown && props.dist.total - props.dist.unknown <= 0
)

const isEmpty = computed(() => isEmptyRanking.value || isNoKnown.value || isAllUnknownExcluded.value)

const emptyDescription = computed(() =>
  !isEmptyRanking.value && !isNoKnown.value && isAllUnknownExcluded.value
    ? `全部 ${view.value.excludedUnknown} ${props.unit}都是未知，已排除`
    : props.emptyText
)

/** 卡片和对话框共用的说明（插槽内容另外原样转发） */
const noteLines = computed(() => {
  const v = view.value
  // emptyWhenNoKnown 的空状态文案已经写明张数
  if (isNoKnown.value) return []
  // 全部为未知时空状态说明已经写明排除了多少，不再重复
  if (isAllUnknownExcluded.value && !isEmptyRanking.value) return []
  if (v.excludedUnknown > 0) return [`已排除未知 ${v.excludedUnknown} ${props.unit}，占比按已知项计算`]
  if (props.chart === 'line' && v.unknown > 0) {
    return [`未知 ${v.unknown} ${props.unit}（没有拍摄时间，不在折线中显示）`]
  }
  return []
})

/** 卡片和对话框共用的图表配置（各自独立的 Chart 实例） */
const model = computed(() =>
  buildDistributionChart({
    dist: view.value,
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
  const d = view.value
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
    if (!props.excludeUnknown) {
      rows.push({
        key: '__unknown',
        cells: { label: '未知', count: d.unknown, share: formatShare(d.unknown_share) },
      })
    }
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
  if (!props.excludeUnknown) {
    rows.push({
      key: '__unknown',
      cells: { label: '未知', range: '—', count: d.unknown, share: formatShare(d.unknown_share) },
    })
  }
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
}

.stat-card__data {
  margin-top: 4px;
}

/* 堆积条形图只有一根条：收紧标题、图和「查看数据」之间的间距（按钮和切换本身不缩小） */
.stat-card--compact > .stat-card__body {
  margin-top: 6px;
}

.stat-card--compact > .stat-card__data {
  margin-top: 2px;
}

/*
 * 图例在底部一行（legendInFooter）：标题 + 切换、图、说明各占一行，最后一行吸收卡片被同一行
 * 另一张卡片拉高的高度，底部一行贴在卡片底边，图例在右下角。
 */
.stat-card--footer-legend {
  grid-template-rows: auto auto auto 1fr;
}

.stat-card--footer-legend > .stat-card__data {
  grid-row: 4;
  align-self: end;
}

/*
 * 左边「查看数据」，右边图例；按钮与图例的最后一行文字对齐（last baseline，不支持时退回底对齐），
 * 图例换成多行时按钮不会悬在中间。图例项多时在按钮右侧的剩余宽度里右对齐换行，不与按钮重叠。
 * 按钮右侧放不下 180px（约一个最长的图例项）时图例整体换到按钮下面一行，仍靠右。
 */
.stat-card__data.is-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  align-items: last baseline;
  gap: 4px 16px;
}

.stat-card__data.is-footer :deep(.el-button) {
  flex: none;
}

.stat-card__data.is-footer > .stat-card__legend {
  flex: 1 1 180px;
  min-width: 0;
  justify-content: flex-end;
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
