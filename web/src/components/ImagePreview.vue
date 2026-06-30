<template>
  <Teleport to="body">
    <div v-if="visible" class="lightbox-overlay" @click.self="close">
      <!-- Close button -->
      <button class="lightbox-close" @click="close" aria-label="关闭">
        <el-icon :size="24"><Close /></el-icon>
      </button>

      <!-- Navigation: Previous -->
      <button
        v-if="hasPrev"
        class="lightbox-nav lightbox-prev"
        @click="prev"
        aria-label="上一张"
      >
        <el-icon :size="32"><ArrowLeft /></el-icon>
      </button>

      <!-- Main image -->
      <div class="lightbox-content">
        <img
          :src="currentImageUrl"
          :alt="currentFile?.file_name"
          class="lightbox-image"
          :style="imageStyle"
          @wheel.prevent="handleWheel"
          @load="onImageLoad"
        />
        <div v-if="loading" class="lightbox-loading">
          <el-icon class="is-loading" :size="40"><Loading /></el-icon>
        </div>
      </div>

      <!-- Navigation: Next -->
      <button
        v-if="hasNext"
        class="lightbox-nav lightbox-next"
        @click="next"
        aria-label="下一张"
      >
        <el-icon :size="32"><ArrowRight /></el-icon>
      </button>

      <!-- Bottom toolbar -->
      <div class="lightbox-toolbar">
        <span class="lightbox-filename">{{ currentFile?.file_name }}</span>
        <span class="lightbox-counter">{{ currentIndex + 1 }} / {{ files.length }}</span>
        <div class="lightbox-actions">
          <el-button
            type="primary"
            :icon="ZoomIn"
            circle
            size="small"
            @click="zoomIn"
            title="放大"
          />
          <el-button
            type="primary"
            :icon="ZoomOut"
            circle
            size="small"
            @click="zoomOut"
            title="缩小"
          />
          <el-button
            type="primary"
            :icon="Download"
            circle
            size="small"
            @click="handleDownload"
            title="下载原图"
          />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { Close, ArrowLeft, ArrowRight, ZoomIn, ZoomOut, Download, Loading } from '@element-plus/icons-vue'
import { getThumbnailUrl, downloadFile } from '@/api/files'
import type { FileInfo } from '@/api/files'

const props = defineProps<{
  visible: boolean
  files: FileInfo[]
  initialIndex: number
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const currentIndex = ref(0)
const scale = ref(1)
const loading = ref(true)

const currentFile = computed(() => props.files[currentIndex.value])
const currentImageUrl = computed(() => {
  if (!currentFile.value) return ''
  return getThumbnailUrl(currentFile.value.id, 'medium')
})

const hasPrev = computed(() => currentIndex.value > 0)
const hasNext = computed(() => currentIndex.value < props.files.length - 1)

const imageStyle = computed(() => ({
  transform: `scale(${scale.value})`,
}))

watch(
  () => props.visible,
  (val) => {
    if (val) {
      currentIndex.value = props.initialIndex
      scale.value = 1
      loading.value = true
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
  }
)

function close() {
  emit('update:visible', false)
}

function prev() {
  if (hasPrev.value) {
    currentIndex.value--
    scale.value = 1
    loading.value = true
  }
}

function next() {
  if (hasNext.value) {
    currentIndex.value++
    scale.value = 1
    loading.value = true
  }
}

function zoomIn() {
  scale.value = Math.min(scale.value + 0.25, 4)
}

function zoomOut() {
  scale.value = Math.max(scale.value - 0.25, 0.25)
}

function handleWheel(e: WheelEvent) {
  if (e.deltaY < 0) {
    zoomIn()
  } else {
    zoomOut()
  }
}

function onImageLoad() {
  loading.value = false
}

function handleDownload() {
  if (currentFile.value) {
    downloadFile(currentFile.value.id, currentFile.value.file_name)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (!props.visible) return
  switch (e.key) {
    case 'Escape':
      close()
      break
    case 'ArrowLeft':
      prev()
      break
    case 'ArrowRight':
      next()
      break
    case '+':
    case '=':
      zoomIn()
      break
    case '-':
      zoomOut()
      break
  }
}

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = ''
})
</script>

<style scoped>
.lightbox-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.9);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #fff;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  z-index: 10;
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.2);
}

.lightbox-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #fff;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  z-index: 10;
}

.lightbox-nav:hover {
  background: rgba(255, 255, 255, 0.25);
}

.lightbox-prev {
  left: 20px;
}

.lightbox-next {
  right: 20px;
}

.lightbox-content {
  max-width: 85vw;
  max-height: 80vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

.lightbox-image {
  max-width: 85vw;
  max-height: 80vh;
  object-fit: contain;
  transition: transform 0.2s ease;
  user-select: none;
  -webkit-user-drag: none;
}

.lightbox-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
}

.lightbox-toolbar {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.7);
  border-radius: 8px;
  padding: 10px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  color: #fff;
  font-size: 14px;
}

.lightbox-filename {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lightbox-counter {
  color: rgba(255, 255, 255, 0.7);
}

.lightbox-actions {
  display: flex;
  gap: 8px;
}
</style>
