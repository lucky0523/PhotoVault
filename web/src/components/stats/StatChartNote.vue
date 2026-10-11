<!--
  StatChartNote —— 图表下方的说明文字（设计 §7.6）。

  卡片和「查看数据」对话框共用，两边显示同样的说明：
  - lines：卡片自己生成的说明（如趋势卡片的「时间跨度超过 10 年，默认按年显示」、未知数量）；
  - 默认插槽：StatisticsView 通过卡片 #note 插槽传进来的提示，卡片原样转发。
  文字 12px #606266，白底对比度 6.29:1（FR-9.2）。
-->
<template>
  <div class="stat-chart-note">
    <p v-for="line in lines" :key="line">{{ line }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ lines?: string[] }>(), { lines: () => [] })
</script>

<style scoped>
.stat-chart-note {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
}

.stat-chart-note p {
  margin: 0;
}
</style>
