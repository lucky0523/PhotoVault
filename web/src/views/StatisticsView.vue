<!--
  StatisticsView —— 数据统计页（设计 §7.2、§7.4、§7.7）。

  - 统计范围（目录 + 是否包含子目录）和「统计时排除未知项」只存在 URL 里：
    ?path=&recursive=0&unknown=1。scopeFromRoute 是 URL → 页面状态的唯一归一化入口，
    控件变化用 router.replace 同步（切换筛选不算导航，不堆历史），watch(fetchKey)
    （path + recursive）触发加载；
  - 「统计时排除未知项」只在前端过滤（applyUnknownPolicy），不发给服务端，切换不重新请求；
    只影响照片 / 视频分布、排行和拍摄趋势，总览的类型占比和格式分布不受影响；
  - 每次请求取消上一个并递增 generation，迟到的旧结果直接丢弃；
  - 卡片由 PHOTO_CARDS / VIDEO_CARDS 配置驱动，只渲染响应里存在且 kind 一致的 key
    （服务端以后新增 key 或 kind 时，旧前端不会误渲染）。
-->
<template>
  <div class="pv-page--fill stats-page">
    <div class="pv-page-head">
      <PageHeader title="数据统计" :icon="DataAnalysis">
        <template #extra>
          <DirectoryTreeSelect :path="scope.path" @update:path="onPathChange" />
          <el-tooltip
            content="根目录始终统计全部文件"
            placement="bottom"
            :disabled="!isRoot"
          >
            <span class="recursive-switch">
              <el-switch
                :model-value="isRoot ? true : scope.recursive"
                :disabled="isRoot"
                active-text="包含子目录"
                aria-label="包含子目录"
                @update:model-value="onRecursiveChange"
              />
            </span>
          </el-tooltip>
          <span class="unknown-switch">
            <el-switch
              :model-value="scope.excludeUnknown"
              active-text="统计时排除未知项"
              aria-label="统计时排除未知项"
              @update:model-value="onExcludeUnknownChange"
            />
          </span>
          <el-button :icon="Refresh" aria-label="刷新统计" :loading="loading" @click="load">
            刷新
          </el-button>
        </template>
      </PageHeader>
      <p class="scope-line">
        统计范围：{{ scopeTrail }}<template v-if="isRoot">。全部包含自定义存储路径下的文件；选择具体目录时不包含。</template>
      </p>
    </div>

    <div class="pv-page-body">
      <!-- 错误：按类别给「重置筛选」或「重试」 -->
      <el-result
        v-if="error"
        icon="error"
        title="统计加载失败"
        :sub-title="error.message"
      >
        <template #extra>
          <el-button v-if="error.kind === 'params'" type="primary" @click="resetFilters">
            重置筛选
          </el-button>
          <el-button v-else type="primary" @click="load">重试</el-button>
        </template>
      </el-result>

      <!-- 首次加载：骨架 -->
      <div v-else-if="!data" class="stat-grid" aria-busy="true">
        <div v-for="n in 6" :key="n" class="pv-panel skeleton-card">
          <el-skeleton animated :rows="5" />
        </div>
      </div>

      <div v-else v-loading="loading" class="stats-content">
        <div class="stats-banners">
          <el-alert
            v-if="data.pending_metadata_count > 0"
            type="info"
            show-icon
            :closable="false"
            :title="`正在补全 ${data.pending_metadata_count} 个文件的元数据，统计结果可能不完整`"
          />
          <el-alert
            v-for="notice in data.notices"
            :key="notice.code"
            type="warning"
            show-icon
            :closable="false"
            :title="notice.message"
          />
          <el-alert
            v-if="rejectedTz"
            type="info"
            show-icon
            :closable="false"
            :title="`浏览器时区 ${rejectedTz} 无法识别，已按服务器时区统计`"
          />
        </div>

        <el-empty
          v-if="data.overview.total.count === 0"
          description="该目录下暂无照片或视频"
        />

        <template v-else>
          <!-- 总览 -->
          <section class="stats-section" aria-labelledby="stats-overview-title">
            <h2 id="stats-overview-title" class="pv-section-title">总览</h2>
            <div class="stat-grid">
              <dl class="stat-numbers is-wide">
                <div
                  v-for="item in numberCards"
                  :key="item.label"
                  class="pv-panel stat-number"
                  :class="{ 'has-sub': item.sub }"
                >
                  <dt>{{ item.label }}</dt>
                  <dd>{{ item.value }}</dd>
                  <template v-if="item.sub">
                    <dt class="stat-number__sub-term">{{ item.sub.label }}</dt>
                    <dd class="stat-number__sub-desc">{{ item.sub.value }}</dd>
                  </template>
                </div>
              </dl>
              <StatChartCard
                v-if="data.overview.categories.kind === 'distribution'"
                title="类型占比"
                chart="doughnut"
                unit="个"
                table-variant="overview"
                allow-bytes
                :dist="data.overview.categories"
              />
              <StatChartCard
                v-if="data.overview.formats.kind === 'distribution'"
                class="is-wide"
                title="格式分布"
                chart="hbar"
                unit="个"
                x-label="文件数"
                y-label="格式"
                table-variant="overview"
                allow-bytes
                :dist="data.overview.formats"
              />
              <StatTrendCard
                v-if="trend"
                class="is-wide"
                :photo-monthly="trend.photoMonthly"
                :photo-yearly="trend.photoYearly"
                :video-monthly="trend.videoMonthly"
                :video-yearly="trend.videoYearly"
                :exclude-unknown="scope.excludeUnknown"
              >
                <template v-if="videoUtcNote" #note>
                  <p>{{ videoUtcNote }}</p>
                </template>
              </StatTrendCard>
            </div>
          </section>

          <!-- 照片 -->
          <section class="stats-section" aria-labelledby="stats-photo-title">
            <h2 id="stats-photo-title" class="pv-section-title">照片</h2>
            <el-empty
              v-if="photoCount === 0"
              :image-size="80"
              description="该目录下没有照片"
            />
            <div v-else class="stat-grid">
              <StatChartCard
                v-for="card in photoCards"
                :key="card.config.key"
                :title="card.config.title"
                :chart="card.config.chart"
                :unit="card.config.unit"
                :x-label="card.config.xLabel"
                :y-label="card.config.yLabel"
                :table-variant="card.config.kind === 'ranking' ? 'ranking' : 'distribution'"
                :exclude-unknown="scope.excludeUnknown"
                :color="STAT_COLORS.photo"
                :empty-text="rankingEmptyText(card.config, card.dist)"
                :dist="card.dist"
              >
                <template v-if="photoNote(card.config.key, card.dist)" #note>
                  {{ photoNote(card.config.key, card.dist) }}
                </template>
              </StatChartCard>
            </div>
          </section>

          <!-- 视频 -->
          <section class="stats-section" aria-labelledby="stats-video-title">
            <h2 id="stats-video-title" class="pv-section-title">视频</h2>
            <el-empty
              v-if="videoCount === 0"
              :image-size="80"
              description="该目录下没有视频"
            />
            <div v-else class="stat-grid">
              <StatChartCard
                v-for="card in videoCards"
                :key="card.config.key"
                :title="card.config.title"
                :chart="card.config.chart"
                :unit="card.config.unit"
                :x-label="card.config.xLabel"
                :y-label="card.config.yLabel"
                :table-variant="card.config.kind === 'ranking' ? 'ranking' : 'distribution'"
                :exclude-unknown="scope.excludeUnknown"
                :color="STAT_COLORS.video"
                :empty-text="rankingEmptyText(card.config, card.dist)"
                :dist="card.dist"
              >
                <template v-if="videoNote(card.config.key)" #note>
                  {{ videoNote(card.config.key) }}
                </template>
              </StatChartCard>
            </div>
          </section>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery } from 'vue-router'
import axios from 'axios'
import { DataAnalysis, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect.vue'
import StatChartCard from '@/components/stats/StatChartCard.vue'
import StatTrendCard from '@/components/stats/StatTrendCard.vue'
import { STAT_COLORS } from '@/components/stats/chartSetup'
import { formatBytes } from '@/components/stats/format'
import { getMediaStats } from '@/api/stats'
import type { Distribution, MediaStatsParams, MediaStatsResponse } from '@/api/stats'

// ---- 卡片配置 --------------------------------------------------------------

interface CardConfig {
  key: string
  kind: 'distribution' | 'ranking'
  chart: 'bar' | 'hbar' | 'line'
  title: string
  xLabel: string
  yLabel: string
  unit: string
  /** 排行为空时说明里的名词 */
  noun?: string
}

const PHOTO_CARDS: CardConfig[] = [
  { key: 'weekday', kind: 'distribution', chart: 'line', title: '拍摄星期分布', xLabel: '星期', yLabel: '照片数', unit: '张' },
  { key: 'hour', kind: 'distribution', chart: 'line', title: '拍摄小时分布', xLabel: '小时', yLabel: '照片数', unit: '张' },
  { key: 'iso', kind: 'distribution', chart: 'bar', title: 'ISO 分布', xLabel: 'ISO', yLabel: '照片数', unit: '张' },
  { key: 'focal_length', kind: 'distribution', chart: 'bar', title: '焦段分布（35mm 等效）', xLabel: '等效焦距', yLabel: '照片数', unit: '张' },
  { key: 'aperture', kind: 'distribution', chart: 'bar', title: '光圈分布', xLabel: '光圈', yLabel: '照片数', unit: '张' },
  { key: 'shutter', kind: 'distribution', chart: 'bar', title: '快门分布', xLabel: '快门速度', yLabel: '照片数', unit: '张' },
  { key: 'camera', kind: 'ranking', chart: 'hbar', title: '机身排行', xLabel: '照片数', yLabel: '机身', unit: '张', noun: '机身' },
  { key: 'lens', kind: 'ranking', chart: 'hbar', title: '镜头排行', xLabel: '照片数', yLabel: '镜头', unit: '张', noun: '镜头' },
]

const VIDEO_CARDS: CardConfig[] = [
  { key: 'weekday', kind: 'distribution', chart: 'line', title: '拍摄星期分布', xLabel: '星期', yLabel: '视频数', unit: '个' },
  { key: 'hour', kind: 'distribution', chart: 'line', title: '拍摄小时分布', xLabel: '小时', yLabel: '视频数', unit: '个' },
  { key: 'duration', kind: 'distribution', chart: 'bar', title: '拍摄时长分布', xLabel: '时长', yLabel: '视频数', unit: '个' },
  { key: 'camera', kind: 'ranking', chart: 'hbar', title: '机身排行', xLabel: '视频数', yLabel: '机身', unit: '个', noun: '机身' },
]

// ---- URL ↔ 统计范围 --------------------------------------------------------

const first = (v: unknown) => (Array.isArray(v) ? v[0] : v)

interface StatsScope {
  path: string
  recursive: boolean
  /** 统计时排除「未知」，只在前端过滤，不发给服务端 */
  excludeUnknown: boolean
}

/**
 * URL → 页面状态的唯一归一化入口。
 * - recursive 除 '0' 以外一律为 true；
 * - unknown 只有 '1' 表示包含未知（开关关），其他值（缺省、abc、数组）一律为默认的排除未知。
 *   这个参数不发给服务端，不会产生 400 / 422，非法值和 recursive 一样宽松处理。
 */
function scopeFromRoute(q: LocationQuery): StatsScope {
  const raw = first(q.path)
  const path = typeof raw === 'string' ? raw.replace(/^\/+|\/+$/g, '') : ''
  const recursive = first(q.recursive) !== '0'
  const excludeUnknown = first(q.unknown) !== '1'
  return { path, recursive, excludeUnknown }
}

const route = useRoute()
const router = useRouter()

const scope = computed(() => scopeFromRoute(route.query))
const isRoot = computed(() => scope.value.path === '')
const scopeTrail = computed(() => ['全部', ...scope.value.path.split('/').filter(Boolean)].join(' › '))

/** 默认值不写进 URL：recursive=0 / unknown=1 只在偏离默认时出现 */
function replaceQuery(next: StatsScope) {
  router.replace({
    name: 'Statistics',
    query: {
      path: next.path || undefined,
      recursive: next.recursive ? undefined : '0',
      unknown: next.excludeUnknown ? undefined : '1',
    },
  })
}

function onPathChange(path: string) {
  replaceQuery({ ...scope.value, path })
}

function onRecursiveChange(value: string | number | boolean) {
  replaceQuery({ ...scope.value, recursive: value === true })
}

function onExcludeUnknownChange(value: string | number | boolean) {
  replaceQuery({ ...scope.value, excludeUnknown: value === true })
}

/** 只有 path / recursive 决定请求；「排除未知项」变化不重新请求 */
const fetchKey = computed(() => `${scope.value.path}\u0000${scope.value.recursive}`)

async function resetFilters() {
  const before = fetchKey.value
  await router.replace({ name: 'Statistics', query: {} })
  // path / recursive 本来就是默认值时 watch(fetchKey) 不会触发，这里自己重新加载，重置不会无效
  if (fetchKey.value === before) load()
}

// ---- 请求 ------------------------------------------------------------------

interface StatsError {
  kind: 'params' | 'transient'
  message: string
}

/**
 * 按错误类别决定页面给「重置筛选」还是「重试」（设计 §7.4）。
 * 参数类错误只可能来自手改或过期的链接（控件本身只产生合法值），重试没有意义。
 */
function classifyStatsError(err: unknown): StatsError {
  if (axios.isAxiosError(err)) {
    const status = err.response?.status
    if (status === 400) {
      const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail
      return { kind: 'params', message: typeof detail === 'string' && detail ? detail : '请求参数无效' }
    }
    if (status === 422) return { kind: 'params', message: '请求参数无效' }
    if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
      return { kind: 'transient', message: '统计耗时过长，请稍后重试' }
    }
    if (!err.response) return { kind: 'transient', message: '网络连接失败' }
  }
  return { kind: 'transient', message: '服务器错误，请稍后重试' }
}

function isInvalidTzError(err: unknown): boolean {
  if (!axios.isAxiosError(err) || err.response?.status !== 422) return false
  const detail = (err.response.data as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'object' && detail !== null && (detail as { code?: unknown }).code === 'invalid_tz'
}

function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined
  } catch {
    return undefined
  }
}

const data = shallowRef<MediaStatsResponse | null>(null)
const loading = ref(false)
const error = ref<StatsError | null>(null)
/** 浏览器时区被服务端拒绝（本页会话内有效），之后的请求都不再带 tz */
const tzRejected = ref(false)
const rejectedTz = ref('')

let controller: AbortController | null = null
let generation = 0

async function load() {
  controller?.abort()
  const ctrl = new AbortController()
  controller = ctrl
  const gen = ++generation
  const { path, recursive } = scopeFromRoute(route.query)
  const params: MediaStatsParams = { path, recursive }
  const tz = tzRejected.value ? undefined : browserTimeZone()
  if (tz) params.tz = tz

  loading.value = true
  error.value = null
  try {
    let result: MediaStatsResponse
    try {
      result = await getMediaStats(params, ctrl.signal)
    } catch (err) {
      if (!tz || gen !== generation || !isInvalidTzError(err)) throw err
      // 浏览器时区服务端不认识：去掉 tz 自动重试一次，并提示已按服务器时区统计
      tzRejected.value = true
      rejectedTz.value = tz
      delete params.tz
      result = await getMediaStats(params, ctrl.signal)
    }
    if (gen !== generation) return
    data.value = result
  } catch (err) {
    if (gen !== generation || axios.isCancel(err)) return
    // 401 由 http 拦截器处理（刷新令牌或跳登录），页面不进入错误态
    if (axios.isAxiosError(err) && err.response?.status === 401) return
    error.value = classifyStatsError(err)
  } finally {
    if (gen === generation) loading.value = false
  }
}

watch(
  fetchKey,
  () => {
    // 离开页面时 query 也会变化，不能因此多发一次请求
    if (route.name !== 'Statistics') return
    load()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  controller?.abort()
  generation++
})

// ---- 展示数据 --------------------------------------------------------------

function categoryCount(key: string): number {
  return data.value?.overview.categories.buckets.find((b) => b.key === key)?.count ?? 0
}

const photoCount = computed(() => categoryCount('photo'))
const videoCount = computed(() => categoryCount('video'))

interface NumberCard {
  label: string
  value: string
  /** 从属数字（动态照片是照片的子集），显示在同一张卡片里 */
  sub?: { label: string; value: string }
}

const numberCards = computed<NumberCard[]>(() => {
  const overview = data.value?.overview
  if (!overview) return []
  return [
    { label: '总文件数', value: overview.total.count.toLocaleString() },
    { label: '总存储', value: formatBytes(overview.total.bytes) },
    {
      label: '照片数',
      value: photoCount.value.toLocaleString(),
      sub: { label: '其中动态照片', value: overview.motion_photo_count.toLocaleString() },
    },
    { label: '视频数', value: videoCount.value.toLocaleString() },
  ]
})

function pickCards(block: Record<string, Distribution> | undefined, configs: CardConfig[]) {
  if (!block) return []
  return configs
    .filter((config) => block[config.key]?.kind === config.kind)
    .map((config) => ({ config, dist: block[config.key] }))
}

const photoCards = computed(() => pickCards(data.value?.photo, PHOTO_CARDS))
const videoCards = computed(() => pickCards(data.value?.video, VIDEO_CARDS))

/** 趋势卡片要求 4 个 series key 都在 */
const trend = computed(() => {
  const photo = data.value?.photo
  const video = data.value?.video
  const series = [photo?.monthly, photo?.yearly, video?.monthly, video?.yearly]
  if (!series.every((d) => d?.kind === 'series')) return null
  const [photoMonthly, photoYearly, videoMonthly, videoYearly] = series as Distribution[]
  return { photoMonthly, photoYearly, videoMonthly, videoYearly }
})

function rankingEmptyText(config: CardConfig, dist: Distribution): string {
  return `没有记录${config.noun ?? ''}信息（未知 ${dist.unknown} ${config.unit}）`
}

function photoNote(key: string, dist: Distribution): string {
  if (key === 'focal_length') {
    const byExif = dist.meta?.by_exif ?? 0
    const byDerived = dist.meta?.by_derived ?? 0
    return `EXIF 等效焦距 ${byExif} 张 / 由裁切系数换算 ${byDerived} 张 / 未知 ${dist.unknown} 张；未记录等效焦距且无法可靠换算的照片计为未知`
  }
  if (key === 'shutter') return '「1/15–1s」档表示 1/30 秒以上、1 秒及以下'
  return ''
}

/** 视频只有 UTC 时间、已按默认时区换算的说明 */
function utcConvertedNote(dist: Distribution | undefined): string {
  const d = data.value
  const converted = dist?.meta?.utc_converted ?? 0
  if (!d || converted <= 0) return ''
  const source = d.scope.tz_source === 'config' ? '（服务器配置）' : ''
  return `其中 ${converted} 个视频只记录了 UTC 时间，已按 ${d.scope.tz}${source} 换算`
}

/** 趋势卡片和视频星期分布是同一批行，计数取自 video.weekday */
const videoUtcNote = computed(() => utcConvertedNote(data.value?.video.weekday))

function videoNote(key: string): string {
  if (key !== 'weekday' && key !== 'hour') return ''
  return utcConvertedNote(data.value?.video[key])
}
</script>

<style scoped>
/* 窄屏下标题栏允许换行，右侧控件不把页面撑出横向滚动（AC34） */
.stats-page :deep(.pv-page-header) {
  flex-wrap: wrap;
}

/*
 * 右侧控件区占满标题右边的剩余宽度（仍靠右对齐、可换行）。目录选择器的宽度是
 * min(100%, 280px)，几乎不计入控件区的固有宽度；不放开的话桌面宽度下控件区按两个
 * 开关 + 按钮的宽度收窄，「统计时排除未知项」会被挤到第二行。
 */
.stats-page :deep(.pv-page-header__extra) {
  flex: 1 1 auto;
  min-width: 0;
  max-width: 100%;
}

/*
 * 文字对比度 ≥ 4.5:1（FR-9.2，设计 §7.6），只在本页覆盖，不改全局主题：
 * - 警告横幅默认 #ffa91f 配 #fff6e8 只有 1.79:1，改用深琥珀 #8a5300（5.91:1，图标随文字色）；
 * - el-empty 说明默认 #909399（3.08:1），改用 #606266。
 */
.stats-page :deep(.el-alert--warning.is-light),
.stats-page :deep(.el-alert--warning.is-light .el-alert__description) {
  color: #8a5300;
}

.stats-page :deep(.el-empty__description p) {
  color: #606266;
}

.recursive-switch,
.unknown-switch {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
}

.unknown-switch :deep(.el-switch) {
  max-width: 100%;
}

.scope-line {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  overflow-wrap: anywhere;
}

.stats-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

.stats-banners {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stats-banners:empty {
  display: none;
}

.stats-section {
  min-width: 0;
}

.stat-grid {
  display: grid;
  gap: var(--pv-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 340px), 1fr));
}

/* 防止 canvas 或表格的固有宽度把列撑宽 */
.stat-grid > * {
  min-width: 0;
}

.stat-grid > .is-wide {
  grid-column: 1 / -1;
}

.stat-numbers {
  margin: 0;
  display: grid;
  gap: var(--pv-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 150px), 1fr));
}

.stat-number {
  padding: 12px 16px;
  min-width: 0;
}

.stat-number dt {
  font-size: 13px;
  color: #606266;
}

.stat-number dd {
  margin: 4px 0 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

/* 照片数卡片：主数字占满一行，「其中动态照片 N」作为第二行 */
.stat-number.has-sub {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  column-gap: 6px;
  align-items: baseline;
}

.stat-number.has-sub > :not(.stat-number__sub-term):not(.stat-number__sub-desc) {
  grid-column: 1 / -1;
}

.stat-number .stat-number__sub-term {
  margin-top: 6px;
  font-size: 12px;
  color: #606266;
}

.stat-number .stat-number__sub-desc {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.skeleton-card {
  padding: 16px;
}
</style>
