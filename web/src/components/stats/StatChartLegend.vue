<!--
  StatChartLegend —— 堆积条形图（类型占比、格式占比）和拍摄趋势的 HTML 图例（设计 §7.6）。

  - 堆积条形图的色标是方块；拍摄趋势的色标是线型 + 点形状（item.marker，照片实线 + 圆点、
    视频虚线 + 三角），不只靠颜色区分；
  - 可换行，每段都显示；文字用正文色，段色只用于色标（照片 / 视频色对白底对比度不足，不能当文字色）；
  - 默认图例内容已在图表的 aria-label 和「查看数据」表格里，aria-hidden，读屏不重复朗读，也不可聚焦；
  - toggleable（拍摄趋势）：每项是一个 aria-pressed 按钮，点击切换该序列显示 / 隐藏
    （与原 Chart.js 图例的点击行为相同），隐藏的项文字划掉、色标变淡；
  - 默认由 StatChartCanvas 画在图下（对话框、普通卡片）；StatChartCard 的 legendInFooter 变体
    和 StatTrendCard 把它放到卡片底部一行「查看数据」的右侧。外边距由使用方决定。
-->
<template>
  <ul class="stat-chart__legend" :aria-hidden="toggleable ? undefined : 'true'">
    <li v-for="item in items" :key="item.key">
      <component
        :is="toggleable ? 'button' : 'span'"
        class="stat-chart__legend-item"
        :class="{ 'is-hidden': item.hidden }"
        v-bind="toggleable ? { type: 'button', 'aria-pressed': item.hidden ? 'false' : 'true' } : {}"
        @click="toggleable && emit('toggle', item.key)"
      >
        <!-- 折线：线型 + 点形状，与图上一致（26 = 6 + 4 + 6 + 4 + 6，虚线关于中点对称） -->
        <svg
          v-if="item.marker"
          class="stat-chart__marker"
          width="26"
          height="12"
          viewBox="0 0 26 12"
          focusable="false"
          aria-hidden="true"
        >
          <line
            x1="0"
            y1="6"
            x2="26"
            y2="6"
            :stroke="item.color"
            stroke-width="2"
            :stroke-dasharray="item.marker.dashed ? '6 4' : undefined"
          />
          <circle v-if="item.marker.pointStyle === 'circle'" cx="13" cy="6" r="3.5" :fill="item.color" />
          <polygon v-else points="13,2 17,9.5 9,9.5" :fill="item.color" />
        </svg>
        <span v-else class="stat-chart__swatch" :style="{ background: item.color }" />
        <span class="stat-chart__legend-text">{{ item.text }}</span>
      </component>
    </li>
  </ul>
</template>

<script setup lang="ts">
import type { StatLegendItem } from './chartModels'

withDefaults(
  defineProps<{
    items: StatLegendItem[]
    /** 每项可点击切换序列显示 / 隐藏（拍摄趋势） */
    toggleable?: boolean
  }>(),
  { toggleable: false }
)

const emit = defineEmits<{
  /** 点击了第 key 项（toggleable 时） */
  toggle: [key: string]
}>()
</script>

<style scoped>
.stat-chart__legend {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
  min-width: 0;
  font-size: 12px;
  line-height: 18px;
  color: #606266;
}

.stat-chart__legend li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}

/* 不可点击时包装元素不参与布局，色标和文字直接是 li 的子项 */
span.stat-chart__legend-item {
  display: contents;
}

button.stat-chart__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: none;
  font: inherit;
  line-height: inherit;
  color: inherit;
  text-align: inherit;
  cursor: pointer;
}

button.stat-chart__legend-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.stat-chart__legend-item.is-hidden .stat-chart__legend-text {
  text-decoration: line-through;
}

.stat-chart__legend-item.is-hidden .stat-chart__marker {
  opacity: 0.4;
}

/* 浅色段在白底上也能看清色标 */
.stat-chart__swatch {
  flex: none;
  width: 12px;
  height: 12px;
  border-radius: 2px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.2);
}

.stat-chart__marker {
  flex: none;
  display: block;
  overflow: visible;
}

.stat-chart__legend-text {
  min-width: 0;
  overflow-wrap: anywhere;
  /* Chrome 默认压缩行尾全角括号的宽度来判断能否放下，「GIF 2 个（0.2%）」这类文字会
     不换行、溢出几像素；不压缩就按实际宽度换行 */
  text-spacing-trim: space-all;
}
</style>
