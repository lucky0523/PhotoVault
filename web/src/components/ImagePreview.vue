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

      <!-- Main image / video -->
      <div class="lightbox-content">
        <video
          v-if="isVideo"
          :key="currentFile?.id"
          :src="currentVideoUrl"
          class="lightbox-video"
          controls
          autoplay
          playsinline
          @loadeddata="onImageLoad"
        />
        <template v-else>
          <img
            :src="currentImageUrl"
            :alt="currentFile?.file_name"
            class="lightbox-image"
            :style="imageStyle"
            @wheel.prevent="handleWheel"
            @load="onImageLoad"
          />
          <!-- Motion photo (动态照片): embedded video overlaid on the still -->
          <video
            v-if="isMotionPhoto && motionPlaying"
            ref="motionVideoRef"
            :key="'motion-' + currentFile?.id"
            :src="currentMotionUrl"
            class="lightbox-motion-video"
            muted
            playsinline
            @ended="motionPlaying = false"
          />
          <!-- Motion photo play toggle, anchored to the image's top-right -->
          <button
            v-if="isMotionPhoto"
            class="motion-toggle"
            :class="{ 'is-playing': motionPlaying }"
            :title="motionPlaying ? '停止' : '播放动态照片'"
            @click.stop="toggleMotion"
          >
            <LivePhotoIcon class="motion-toggle-icon" />
            <span>LIVE</span>
          </button>
          <div v-if="isUltraHdr && !motionPlaying" class="hdr-badge" title="Ultra HDR">HDR</div>
        </template>
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
            v-if="!isVideo"
            type="primary"
            :icon="ZoomIn"
            circle
            size="small"
            @click="zoomIn"
            title="放大"
          />
          <el-button
            v-if="!isVideo"
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
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { Close, ArrowLeft, ArrowRight, ZoomIn, ZoomOut, Download, Loading } from '@element-plus/icons-vue'
import { getThumbnailUrl, getDownloadUrl, getMotionVideoUrl, downloadFile } from '@/api/files'
import LivePhotoIcon from '@/components/LivePhotoIcon.vue'
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
const isVideo = computed(() => {
  const f = currentFile.value
  if (!f) return false
  if ((f.media_type || '').toLowerCase() === 'video') return true
  return !!f.mime_type && f.mime_type.toLowerCase().startsWith('video/')
})
const currentImageUrl = computed(() => {
  if (!currentFile.value) return ''
  return getThumbnailUrl(currentFile.value.id, 'medium')
})
const currentVideoUrl = computed(() => {
  if (!currentFile.value) return ''
  return getDownloadUrl(currentFile.value.id)
})
const isMotionPhoto = computed(() => !isVideo.value && !!currentFile.value?.is_motion_photo)
const isUltraHdr = computed(() => !isVideo.value && !!currentFile.value?.is_ultra_hdr)
const currentMotionUrl = computed(() => {
  if (!currentFile.value) return ''
  return getMotionVideoUrl(currentFile.value.id)
})
const motionPlaying = ref(false)
const motionVideoRef = ref<HTMLVideoElement | null>(null)

async function toggleMotion() {
  if (motionPlaying.value) {
    motionPlaying.value = false
    return
  }
  motionPlaying.value = true
  await nextTick()
  const v = motionVideoRef.value
  if (v) {
    v.currentTime = 0
    try {
      await v.play()
    } catch {
      /* autoplay/user-gesture issues are non-fatal */
    }
  }
}

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
      motionPlaying.value = false
      document.body.style.overflow = 'hidden'
    } else {
      motionPlaying.value = false
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
    motionPlaying.value = false
  }
}

function next() {
  if (hasNext.value) {
    currentIndex.value++
    scale.value = 1
    loading.value = true
    motionPlaying.value = false
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

.lightbox-video {
  max-width: 85vw;
  max-height: 80vh;
  object-fit: contain;
  background: #000;
}

.lightbox-motion-video {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.motion-toggle {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: none;
  border-radius: 16px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  cursor: pointer;
  z-index: 11;
  transition: background 0.2s;
}

.motion-toggle:hover {
  background: rgba(0, 0, 0, 0.75);
}

.motion-toggle.is-playing {
  background: rgba(255, 255, 255, 0.9);
  color: #111;
}

.motion-toggle-icon {
  font-size: 18px;
}

.hdr-badge {
  position: absolute;
  right: 12px;
  bottom: 12px;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.3px;
  pointer-events: none;
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
