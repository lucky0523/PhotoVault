<!--
  StatDataDialog —— 图表卡片的「查看数据」按钮 + 图表和数据表对话框（设计 §7.6）。

  StatChartCard 和 StatTrendCard 共用：
  - 按钮文字固定「查看数据」，aria-haspopup="dialog"；
  - el-dialog 标题即表格标题（如「类型占比数据」），关闭按钮 / Esc / 点遮罩都能关闭；
  - 对话框里先放大显示和卡片同一张图（#chart 插槽，卡片放一个 StatChartCanvas），
    再显示 StatDataTable。#toolbar 插槽放和卡片绑定同一状态的口径 / 粒度切换；
  - 对话框里的图是独立的 Chart 实例：destroy-on-close 让对话框内容在打开时挂载、
    关闭过渡结束后卸载，StatChartCanvas 随之创建和 destroy()，卡片自己的 canvas 不动；
  - 宽度 min(860px, 90vw)，窄屏下表格在 StatDataTable 自己的滚动容器里滚动；
  - 关闭后把焦点还给按钮：Element Plus 只在键盘关闭时恢复焦点，这里在 closed
    （关闭过渡结束、焦点陷阱已释放）时统一处理，Esc / 遮罩 / 关闭按钮都一样；
  - append-to-body：对话框不留在卡片的 grid / figure 里。
-->
<template>
  <el-button ref="triggerRef" link type="primary" aria-haspopup="dialog" @click="visible = true">
    查看数据
  </el-button>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(860px, 90vw)"
    align-center
    append-to-body
    destroy-on-close
    :close-on-press-escape="true"
    :close-on-click-modal="true"
    @closed="restoreFocus"
  >
    <div v-if="$slots.toolbar" class="stat-data-dialog__toolbar">
      <slot name="toolbar" />
    </div>
    <div v-if="$slots.chart" class="stat-data-dialog__chart">
      <slot name="chart" />
    </div>
    <div class="stat-data-dialog__table" :class="{ 'has-chart': !!$slots.chart }">
      <StatDataTable :caption="title" :columns="columns" :rows="rows" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ButtonInstance } from 'element-plus'
import StatDataTable from './StatDataTable.vue'
import type { StatTableColumn, StatTableRow } from './StatDataTable.vue'

defineProps<{
  /** 对话框标题，同时作为表格 caption */
  title: string
  columns: StatTableColumn[]
  rows: StatTableRow[]
}>()

const visible = ref(false)
const triggerRef = ref<ButtonInstance>()

function restoreFocus() {
  triggerRef.value?.ref?.focus()
}
</script>

<style scoped>
.stat-data-dialog__toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.stat-data-dialog__chart {
  min-width: 0;
  margin-bottom: 12px;
}

/* 上面有图时表格限高更小，标题 + 切换 + 图 + 表格在一屏内放得下 */
.stat-data-dialog__table.has-chart :deep(.stats-table-wrap) {
  max-height: min(36vh, 320px);
}
</style>
