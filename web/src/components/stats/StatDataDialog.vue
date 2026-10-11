<!--
  StatDataDialog —— 图表卡片的「查看数据」按钮 + 数据表对话框（设计 §7.6）。

  StatChartCard 和 StatTrendCard 共用：
  - 按钮文字固定「查看数据」，aria-haspopup="dialog"；
  - el-dialog 标题即表格标题（如「类型占比数据」），关闭按钮 / Esc / 点遮罩都能关闭；
  - 宽度 min(720px, 90vw)，窄屏下表格在 StatDataTable 自己的滚动容器里滚动；
  - 关闭后把焦点还给按钮（Element Plus 只在键盘关闭时恢复焦点，这里统一处理）；
  - append-to-body：对话框不留在卡片的 grid / figure 里。
-->
<template>
  <el-button ref="triggerRef" link type="primary" aria-haspopup="dialog" @click="visible = true">
    查看数据
  </el-button>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(720px, 90vw)"
    align-center
    append-to-body
    :close-on-press-escape="true"
    :close-on-click-modal="true"
    @closed="restoreFocus"
  >
    <StatDataTable :caption="title" :columns="columns" :rows="rows" />
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
