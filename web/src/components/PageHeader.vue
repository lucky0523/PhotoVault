<!--
  PageHeader —— 所有页面统一的标题栏。

  重构前页面头部有两套写法：
  - el-page-header（设备管理 / 用户管理 / 分析资源管理）
  - 各页自制的白色 .toolbar（图片浏览 / 时间线 / 探索 / 分类照片），
    且内边距三种取值（8px 12px、10px 16px、12px 16px）
  回收站甚至只有一个裸的 <h3>。

  另外 el-page-header 会无条件渲染一个返回箭头，而设备管理 / 用户管理是侧边栏
  一级入口、没有上级页面，箭头点了没反应。所以这里把「返回」做成显式的 back
   prop：只有真正存在上级页面的页面才传，避免再出现无效箭头。
-->
<template>
  <div class="pv-page-header pv-panel">
    <div class="pv-page-header__left">
      <template v-if="back">
        <el-button class="pv-page-header__back" link @click="emit('back')">
          <el-icon><ArrowLeft /></el-icon>
          {{ backText }}
        </el-button>
        <span class="pv-page-header__sep" />
      </template>

      <!-- 默认是「图标 + 标题 + 副标题」；图片浏览用这个插槽放面包屑 -->
      <slot name="title">
        <el-icon v-if="icon" :size="18" class="pv-page-header__icon">
          <component :is="icon" />
        </el-icon>
        <span class="pv-page-header__title">{{ title }}</span>
        <span v-if="subtitle" class="pv-page-header__subtitle">{{ subtitle }}</span>
        <slot name="subtitle" />
      </slot>
    </div>

    <div v-if="$slots.extra" class="pv-page-header__extra">
      <slot name="extra" />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    /** 页面标题 */
    title?: string
    /** 标题右侧的次要说明（页面用途、条目数等） */
    subtitle?: string
    /** 标题左侧图标，与侧边栏导航图标保持一致 */
    icon?: Component
    /** 是否显示返回按钮：仅当页面确实有上级页面时才传 true */
    back?: boolean
    /** 返回按钮文案 */
    backText?: string
  }>(),
  {
    title: '',
    subtitle: '',
    icon: undefined,
    back: false,
    backText: '返回',
  }
)

const emit = defineEmits<{ back: [] }>()
</script>

<style scoped>
.pv-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pv-gap);
  /* 48px = 32px 按钮 + 上下各 8px 内边距。写成 min-height 是为了让
     没有右侧按钮的页面（设备管理）与有按钮的页面标题栏等高。 */
  min-height: 48px;
  padding: 8px 16px;
  box-sizing: border-box;
}

.pv-page-header__left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  /* 右侧操作区较宽时（时间线有 4 个筛选器），标题不被压缩换行 */
  flex-shrink: 0;
}

.pv-page-header__back {
  flex-shrink: 0;
  padding: 0;
  font-size: 14px;
}

/* 返回按钮与标题之间的竖线，避免两段文字糊在一起 */
.pv-page-header__sep {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: var(--pv-divider-color);
  flex-shrink: 0;
}

.pv-page-header__icon {
  color: var(--el-color-primary);
  flex-shrink: 0;
}

.pv-page-header__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.pv-page-header__subtitle {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pv-page-header__extra {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--pv-gap);
  flex-wrap: wrap;
}
</style>
