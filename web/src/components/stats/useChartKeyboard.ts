/**
 * 图表键盘浏览（设计 §7.6）。
 *
 * 图表容器 role="group" tabindex="0"，获得焦点后：
 * - ArrowRight / ArrowDown 下一项，ArrowLeft / ArrowUp 上一项，Home / End 首末项；
 * - Escape 或失焦清除高亮。
 *
 * 每次移动对同一 index 的所有可见数据集一起 setActiveElements（趋势卡片的照片 /
 * 视频两条序列同时高亮），同时把 tooltip 定位到该项，并把同样的文字写进视觉隐藏的
 * aria-live 区域，读屏软件也能逐项听到。
 */
import { ref } from 'vue'
import type { ActiveDataPoint, Chart } from 'chart.js'

export interface ChartKeyboardOptions {
  /** 当前图表实例（未创建时返回 null） */
  getChart: () => Chart<any> | null
  /** 可浏览的项数（= 图表 labels 数） */
  count: () => number
  /** 第 index 项的朗读文本 */
  describe: (index: number) => string
}

export function useChartKeyboard(options: ChartKeyboardOptions) {
  const activeIndex = ref(-1)
  const liveText = ref('')

  function highlight(index: number) {
    const chart = options.getChart()
    if (!chart) return
    const active: ActiveDataPoint[] = []
    chart.data.datasets.forEach((_, datasetIndex) => {
      if (!chart.isDatasetVisible(datasetIndex)) return
      if (chart.getDatasetMeta(datasetIndex).data[index]) {
        active.push({ datasetIndex, index })
      }
    })
    if (active.length === 0) return
    const element = chart.getDatasetMeta(active[0].datasetIndex).data[index]
    const pos = element.tooltipPosition(true)
    chart.setActiveElements(active)
    chart.tooltip?.setActiveElements(active, { x: pos.x ?? 0, y: pos.y ?? 0 })
    chart.update('none')
    activeIndex.value = index
    liveText.value = options.describe(index)
  }

  function clear() {
    activeIndex.value = -1
    liveText.value = ''
    const chart = options.getChart()
    if (!chart) return
    chart.setActiveElements([])
    chart.tooltip?.setActiveElements([], { x: 0, y: 0 })
    chart.update('none')
  }

  function onKeydown(event: KeyboardEvent) {
    const n = options.count()
    if (n === 0) return
    const current = activeIndex.value
    let next: number | null = null
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = current < 0 ? 0 : Math.min(current + 1, n - 1)
        break
      case 'ArrowLeft':
      case 'ArrowUp':
        next = current < 0 ? 0 : Math.max(current - 1, 0)
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = n - 1
        break
      case 'Escape':
        if (current >= 0) {
          event.preventDefault()
          clear()
        }
        return
      default:
        return
    }
    // 方向键默认会滚动页面，这里已经消费了它
    event.preventDefault()
    highlight(next)
  }

  /** 数据变化后旧 index 可能已经越界，调用方在重绘前重置 */
  function reset() {
    activeIndex.value = -1
    liveText.value = ''
  }

  return { activeIndex, liveText, onKeydown, onBlur: clear, reset }
}
