<!--
  StatDataTable —— 图表卡片「查看数据」对话框（StatDataDialog）里的原生表格（设计 §7.6）。

  列和行由调用方决定（分布 / 总览类型格式 / 排行 / 趋势四种配置），这里只负责
  可访问的表格结构和窄宽度下的溢出处理：
  - 外层 .stats-table-wrap 自己滚动（横向 + 限高纵向），列放不下或行很多时
    也不会撑宽 / 撑高对话框和页面（AC34）；
  - caption 视觉隐藏（对话框标题已经显示同样的文字），读屏仍能读到表格名称；
  - 容器宽度 < 240px 时隐藏 .col-range（区间）和 .col-secondary（存储占比），
    这两列的信息在 tooltip 或相邻列里都还有，隐藏不丢信息。
-->
<template>
  <div class="stats-table-wrap">
    <table class="stats-table">
      <caption>{{ caption }}</caption>
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="col.key"
            scope="col"
            :class="[col.className, { 'is-numeric': col.numeric }]"
          >
            {{ col.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.key"
          :class="{ 'is-child': row.child, 'is-total': row.total }"
        >
          <td
            v-for="(col, ci) in columns"
            :key="col.key"
            :class="[col.className, { 'is-numeric': col.numeric, 'is-first': ci === 0 }]"
          >
            {{ row.cells[col.key] ?? '—' }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
export interface StatTableColumn {
  key: string
  label: string
  /** 'col-range' / 'col-secondary'：窄容器下隐藏 */
  className?: string
  /** 数字列右对齐 */
  numeric?: boolean
}

export interface StatTableRow {
  key: string
  cells: Record<string, string | number>
  /** 子项（格式分组的展开项），首列缩进 */
  child?: boolean
  /** 合计行 */
  total?: boolean
}

defineProps<{
  caption: string
  columns: StatTableColumn[]
  rows: StatTableRow[]
}>()
</script>

<style scoped>
.stats-table-wrap {
  position: relative;
  overflow: auto;
  max-width: 100%;
  max-height: min(60vh, 480px);
  container-type: inline-size;
}

.stats-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

/* 视觉隐藏：对话框标题已显示同样的文字 */
.stats-table caption {
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

.stats-table th,
.stats-table td {
  padding: 4px 8px;
  border-bottom: 1px solid var(--pv-divider-color);
  text-align: left;
  white-space: nowrap;
}

.stats-table th {
  font-weight: 600;
  color: #606266;
  background: #fafafa;
}

.stats-table .is-numeric {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.stats-table tr.is-child .is-first {
  padding-left: 24px;
  color: #606266;
}

.stats-table tr.is-total td {
  font-weight: 600;
  border-bottom: none;
}

/* 不支持容器查询的浏览器：按视口宽度回退 */
@supports not (container-type: inline-size) {
  @media (max-width: 480px) {
    .col-range,
    .col-secondary {
      display: none;
    }
  }
}

@container (max-width: 239px) {
  .col-range,
  .col-secondary {
    display: none;
  }
}
</style>
