<!--
  StatChartCanvas —— 一个 Chart.js 实例的生命周期 + 可访问外壳（设计 §7.6）。

  卡片和「查看数据」对话框各放一个，各自持有独立的 Chart 实例，都从同一个
  StatChartModel（chartModels.ts）取配置：
  - 容器 role="group" tabindex="0"，aria-label 带标题和摘要；canvas role="img"；
  - 键盘逐项查看见 useChartKeyboard，朗读文本写进本组件自己的 aria-live 区域
    （对话框是 aria-modal，朗读区域必须在对话框里面）；
  - onMounted 创建、model 变化 update()、卸载前 destroy()。对话框里的实例随对话框
    内容（destroy-on-close）一起创建和销毁，不会留下 Chart 实例。

  不用 vue-chartjs。图表实例放在普通变量里，不能放进 ref —— Vue 的响应式代理会
  破坏 Chart.js 的内部状态。
-->
<template>
  <div class="stat-chart">
    <div
      class="stat-chart__plot"
      role="group"
      tabindex="0"
      :style="{ height }"
      :aria-label="`${title}：${model.summary}。按左右方向键逐项查看`"
      @keydown="keyboard.onKeydown"
      @blur="keyboard.onBlur"
    >
      <canvas ref="canvasRef" role="img" :aria-label="model.summary" />
    </div>
    <span class="stat-sr-only" aria-live="polite">{{ keyboard.liveText.value }}</span>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Chart } from './chartSetup'
import type { StatChartModel } from './chartModels'
import { useChartKeyboard } from './useChartKeyboard'

const props = defineProps<{
  /** 图表标题（容器 aria-label 的前缀） */
  title: string
  model: StatChartModel
  /** 绘图区高度（CSS 长度） */
  height: string
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
let chart: Chart<any> | null = null

const keyboard = useChartKeyboard({
  getChart: () => chart,
  count: () => props.model.count,
  describe: (index) => props.model.describe(index),
  activeAt: (index) => props.model.activeAt?.(index),
})

function render() {
  const canvas = canvasRef.value
  if (!canvas) {
    chart?.destroy()
    chart = null
    return
  }
  keyboard.reset()
  const config = props.model.buildConfig()
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

watch(() => props.model, render, { flush: 'post' })

onBeforeUnmount(() => {
  chart?.destroy()
  chart = null
})
</script>

<style scoped>
/* 朗读区域 .stat-sr-only 是 position: absolute。外壳不定位的话它的包含块会一路找到
   初始包含块，不受 .pv-page-body 的 overflow 裁切，1×1 的盒子留在内容底部，
   把整个文档撑高 —— 内容区滚到底后再滚，整个页面（顶栏、侧栏）都跟着滚走。 */
.stat-chart {
  position: relative;
  min-width: 0;
}

.stat-chart__plot {
  position: relative;
  width: 100%;
  border-radius: 4px;
}

.stat-chart__plot:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
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
