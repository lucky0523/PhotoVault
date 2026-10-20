<!--
  StatTrendCard —— 拍摄趋势卡片（设计 §7.2、§7.6）。

  读 photo.monthly / photo.yearly / video.monthly / video.yearly（kind = "series"）。
  服务端保证照片和视频共用同一条月份 / 年份轴，这里按 index 直接对齐。
  - 照片：实线 + 圆点（STAT_COLORS.photo）；视频：虚线 [6, 4] + 三角点（STAT_COLORS.video）。
    线型和点形状不同，不只靠颜色区分；
  - 某类文件数为 0 时不画该序列（图例也不出现）；两类都没有有效拍摄时间时显示空状态；
  - excludeUnknown（「统计时排除未知项」）时四个序列先经 applyUnknownPolicy：占比按已知项
    重新分配，表格去掉「未知」行、合计为已知数，说明改为「已排除未知：…」（只列排除数 > 0 的类别）；
  - 月份 bucket 数 > 120（跨度超过 10 年）时默认按年显示并注明；
  - 图表配置在 chartModels.ts（buildTrendChart），Chart 实例的生命周期、键盘逐项查看
    和朗读区域在 StatChartCanvas；
  - 图例是 HTML（StatChartLegend，线型 + 点形状），放在卡片底部一行「查看数据」右侧、右对齐，
    按钮对齐图例最后一行；底部一行贴在卡片底边。对话框里的图例在图下。点图例项切换该序列
    显示 / 隐藏（原 Chart.js 图例的行为），卡片和对话框共用同一个隐藏状态；
  - 「查看数据」对话框上面放大显示同一张图（独立的 Chart 实例，同一个 model），带同一个
    「按月 / 按年」切换，图下面是和卡片相同的说明（StatChartNote，#note 插槽原样转发），
    再下面是数据表；
  - fillHeight（只在总览里用）：卡片被同一行拉高时图表跟着填满卡片的高度（图表区是 1fr 的一行，
    canvas 绝对定位不撑高卡片），最小高度 --pv-trend-chart-min（默认 240px）。
-->
<template>
  <figure class="pv-panel stat-card stat-card--footer-legend" :class="{ 'stat-card--fill': fillHeight }">
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
        :height="fillHeight ? '100%' : cardChartHeight('line', model.count)"
        hide-legend
      />
    </div>

    <StatChartNote class="stat-card__note" :lines="noteLines">
      <slot name="note" />
    </StatChartNote>

    <div v-if="!isEmpty" class="stat-card__data is-footer">
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
            legend-toggleable
            @legend-toggle="toggleSeries"
          />
        </template>
      </StatDataDialog>
      <StatChartLegend
        v-if="model.legend?.length"
        class="stat-card__legend"
        :items="model.legend"
        toggleable
        @toggle="toggleSeries"
      />
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Distribution } from '@/api/stats'
import {
  applyUnknownPolicy,
  buildTrendChart,
  buildTrendSeries,
  cardChartHeight,
  dialogChartHeight,
  type TrendGranularity,
} from './chartModels'
import StatChartCanvas from './StatChartCanvas.vue'
import StatChartLegend from './StatChartLegend.vue'
import StatChartNote from './StatChartNote.vue'
import StatDataDialog from './StatDataDialog.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

const props = withDefaults(
  defineProps<{
    photoMonthly: Distribution
    photoYearly: Distribution
    videoMonthly: Distribution
    videoYearly: Distribution
    /** 统计时排除「未知」（没有拍摄时间的文件） */
    excludeUnknown?: boolean
    /** 图表填满卡片被拉高后的高度（总览），最小 --pv-trend-chart-min */
    fillHeight?: boolean
  }>(),
  { excludeUnknown: false, fillHeight: false }
)

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

/** 当前粒度的两个序列，已按「统计时排除未知项」处理（图表、对话框和表格共用） */
const current = computed(() => {
  const [photo, video] =
    granularity.value === 'month'
      ? [props.photoMonthly, props.videoMonthly]
      : [props.photoYearly, props.videoYearly]
  return {
    photo: applyUnknownPolicy(photo, props.excludeUnknown),
    video: applyUnknownPolicy(video, props.excludeUnknown),
  }
})

/** 只画文件数 > 0 的类别 */
const series = computed(() => buildTrendSeries(current.value.photo, current.value.video))

/** 共同轴：照片和视频的 bucket 完全一致，取任一方 */
const axis = computed(() => current.value.photo.buckets)

const isEmpty = computed(() => axis.value.length === 0 || series.value.length === 0)

/** 点图例隐藏的序列名（卡片和对话框共用，切换粒度时保留） */
const hiddenSeries = ref<string[]>([])

function toggleSeries(name: string) {
  hiddenSeries.value = hiddenSeries.value.includes(name)
    ? hiddenSeries.value.filter((n) => n !== name)
    : [...hiddenSeries.value, name]
}

/** 卡片和对话框共用的图表配置（各自独立的 Chart 实例） */
const model = computed(() =>
  buildTrendChart({
    axis: axis.value,
    series: series.value,
    granularity: granularity.value,
    hidden: hiddenSeries.value,
  })
)

/**
 * 未知（没有拍摄时间）的说明。包含未知时列出有文件的每一类（含 0）；
 * 排除未知时只列真正排除了的类别，都没有排除时不显示（和分布卡片一致）。
 */
const unknownText = computed(() => {
  const shown = (d: Distribution) => (props.excludeUnknown ? d.unknown > 0 : d.total > 0)
  const parts: string[] = []
  if (shown(props.photoMonthly)) parts.push(`照片 ${props.photoMonthly.unknown} 张`)
  if (shown(props.videoMonthly)) parts.push(`视频 ${props.videoMonthly.unknown} 个`)
  if (parts.length === 0) return ''
  const prefix = props.excludeUnknown ? '已排除未知' : '未知'
  return `${prefix}：${parts.join('、')}（没有拍摄时间）`
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
  if (!props.excludeUnknown) rows.push({ key: '__unknown', cells: unknownCells })
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

/*
 * 图例在底部一行（与 StatChartCard 的 legendInFooter 相同）：标题 + 切换、图、说明各占一行，
 * 最后一行吸收卡片被拉高的高度，底部一行贴在卡片底边，图例在右下角。
 */
.stat-card--footer-legend {
  grid-template-rows: auto auto auto 1fr;
}

.stat-card--footer-legend > .stat-card__data {
  grid-row: 4;
  align-self: end;
}

/*
 * fillHeight：改由图表区（第 2 行）吸收卡片被拉高的高度；canvas 绝对定位填满图表区，
 * 卡片高度只由最小高度和同一行的另一张卡片决定，不被 canvas 反过来撑高
 */
.stat-card--fill {
  grid-template-rows: auto 1fr auto auto;
}

.stat-card--fill > .stat-card__body {
  position: relative;
  align-self: stretch;
  min-height: var(--pv-trend-chart-min, 240px);
}

.stat-card--fill > .stat-card__body > .stat-chart {
  position: absolute;
  inset: 0;
}

/*
 * 左边「查看数据」，右边图例；按钮与图例的最后一行文字对齐（last baseline，不支持时退回底对齐）。
 * 按钮右侧放不下 180px 时图例整体换到按钮下面一行，仍靠右。
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

/* 空状态说明默认 #909399 只有 3.08:1，改用 #606266（FR-9.2） */
.stat-card :deep(.el-empty__description p) {
  color: #606266;
}

/* 对话框内容 append-to-body，不在 .stat-card 里，单独设置同样的颜色 */
.stat-dialog-empty :deep(.el-empty__description p) {
  color: #606266;
}
</style>
