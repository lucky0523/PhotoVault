<!--
  DirectoryTreeSelect —— 目录选择器，数据统计页（设计 §7.3）和时间线的目录筛选共用。

  el-tree-select 懒加载目录树：
  - 根目录用哨兵值 '/' 表示，对外映射成 path ''（全部）；其他节点的值就是虚拟路径；
  - 每层调用 browseFiles(path, 1, 1)：目录列表不分页，文件只取 1 条减小负载；
  - 过滤 .trash 和 backed_up_count === 0 的目录（只剩回收站或已彻底删除文件的目录
    也会出现在 browse 结果里）；
  - 从 URL 恢复深层路径时节点还没加载，用 cache-data 先显示目录名；
  - aria-label 由调用方给出（默认「选择统计目录」，时间线传「选择目录」）。

  加载逻辑参照 PhotosView.vue::loadTreeNode，但不重构 PhotosView，避免回归。
-->
<template>
  <el-tree-select
    v-model="selected"
    class="directory-tree-select"
    lazy
    check-strictly
    node-key="value"
    :load="loadNode"
    :props="TREE_PROPS"
    :cache-data="cacheData"
    :render-after-expand="false"
    :aria-label="ariaLabel"
    placeholder="选择目录"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { browseFiles } from '@/api/files'

interface DirNode {
  value: string
  label: string
  isLeaf: boolean
}

const props = withDefaults(
  defineProps<{
    /** 虚拟路径，'' 表示全部 */
    path: string
    /** 无障碍名称 */
    ariaLabel?: string
  }>(),
  { ariaLabel: '选择统计目录' }
)

const emit = defineEmits<{ 'update:path': [path: string] }>()

const ROOT_VALUE = '/'
const TREE_PROPS = { label: 'label', isLeaf: 'isLeaf' }

const selected = computed<string>({
  get: () => (props.path === '' ? ROOT_VALUE : props.path),
  set: (value) => emit('update:path', !value || value === ROOT_VALUE ? '' : value),
})

const cacheData = computed<DirNode[]>(() => {
  const nodes: DirNode[] = [{ value: ROOT_VALUE, label: '全部', isLeaf: true }]
  if (props.path) {
    nodes.push({ value: props.path, label: props.path.split('/').pop() || props.path, isLeaf: false })
  }
  return nodes
})

async function loadNode(
  node: { level: number; data: DirNode },
  resolve: (data: DirNode[]) => void
) {
  const isRoot = node.level === 0
  try {
    const response = await browseFiles(isRoot ? '' : node.data.value, 1, 1)
    const dirs: DirNode[] = response.directories
      .filter((dir) => dir.name !== '.trash' && dir.backed_up_count !== 0)
      .map((dir) => ({ value: dir.path, label: dir.name, isLeaf: false }))
    resolve(isRoot ? [{ value: ROOT_VALUE, label: '全部', isLeaf: true }, ...dirs] : dirs)
  } catch {
    resolve([])
  }
}
</script>

<style scoped>
.directory-tree-select {
  width: min(100%, 280px);
}
</style>
