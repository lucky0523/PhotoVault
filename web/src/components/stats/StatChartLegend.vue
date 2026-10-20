<!--
  StatChartLegend —— 堆积条形图（类型占比、格式占比）的 HTML 图例（设计 §7.6）。

  - 可换行，每段都显示；文字用正文色，段色只用于色标（照片 / 视频色对白底对比度不足，不能当文字色）；
  - 图例内容已在图表的 aria-label 和「查看数据」表格里，aria-hidden，读屏不重复朗读，也不可聚焦；
  - 默认由 StatChartCanvas 画在图下（对话框、普通卡片）；StatChartCard 的 legendInFooter 变体
    把它放到卡片底部一行「查看数据」的右侧。外边距由使用方决定。
-->
<template>
  <ul class="stat-chart__legend" aria-hidden="true">
    <li v-for="item in items" :key="item.key">
      <span class="stat-chart__swatch" :style="{ background: item.color }" />
      <span class="stat-chart__legend-text">{{ item.text }}</span>
    </li>
  </ul>
</template>

<script setup lang="ts">
import type { StatLegendItem } from './chartModels'

defineProps<{
  items: StatLegendItem[]
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

/* 浅色段在白底上也能看清色标 */
.stat-chart__swatch {
  flex: none;
  width: 12px;
  height: 12px;
  border-radius: 2px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.2);
}

.stat-chart__legend-text {
  min-width: 0;
  overflow-wrap: anywhere;
  /* Chrome 默认压缩行尾全角括号的宽度来判断能否放下，「GIF 2 个（0.2%）」这类文字会
     不换行、溢出几像素；不压缩就按实际宽度换行 */
  text-spacing-trim: space-all;
}
</style>
