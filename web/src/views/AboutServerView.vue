<!--
  关于服务端 —— 设置分组下的第三个页面。

  只读页面：展示服务端版本、运行状态与宿主机信息。存储路径与生效配置属于
  运维细节，后端只对管理员返回（storage / config 为 null 时整块不渲染），
  所以普通用户看到的是「版本 + 运行状态」两块。
-->
<template>
  <div class="about-server-view pv-page">
    <PageHeader
      title="关于服务端"
      subtitle="服务端版本与运行状态"
      :icon="InfoFilled"
    >
      <template #extra>
        <el-button :loading="loading" @click="loadAbout">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="about-body">
      <el-alert
        v-if="error"
        class="about-alert"
        type="error"
        :title="error"
        :closable="false"
        show-icon
      />

      <template v-if="about">
        <!-- 版本 -->
        <section class="about-section pv-panel">
          <h4 class="pv-section-title">版本</h4>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="服务名称">{{ about.name }}</el-descriptions-item>
            <el-descriptions-item label="服务端版本">
              <el-tag size="small" type="primary">v{{ about.version }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="API 版本">{{ about.api_version }}</el-descriptions-item>
            <el-descriptions-item label="Web 端版本">v{{ webVersion }}</el-descriptions-item>
          </el-descriptions>
        </section>

        <!-- 运行状态 -->
        <section class="about-section pv-panel">
          <h4 class="pv-section-title">运行状态</h4>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="启动时间">
              {{ formatDate(about.started_at) }}
            </el-descriptions-item>
            <el-descriptions-item label="已运行">{{ uptimeText }}</el-descriptions-item>
            <el-descriptions-item label="主机名">{{ about.hostname }}</el-descriptions-item>
            <el-descriptions-item label="监听端口">{{ about.port }}</el-descriptions-item>
            <el-descriptions-item label="操作系统">{{ about.platform || '-' }}</el-descriptions-item>
            <el-descriptions-item label="CPU 架构">{{ about.machine || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Python">{{ about.python_version }}</el-descriptions-item>
            <el-descriptions-item label="FastAPI">{{ about.fastapi_version }}</el-descriptions-item>
            <el-descriptions-item label="当前访问地址" :span="2">
              {{ currentOrigin }}
            </el-descriptions-item>
            <el-descriptions-item label="局域网地址" :span="2">
              <span v-if="about.lan_ips.length === 0" class="pv-muted">未检测到</span>
              <template v-else>
                <el-tag
                  v-for="ip in about.lan_ips"
                  :key="ip"
                  class="about-ip"
                  size="small"
                  type="info"
                >
                  {{ ip }}:{{ about.port }}
                </el-tag>
              </template>
              <span class="about-lan-hint pv-muted">
                由服务端枚举网卡得出，仅列出可被同一局域网访问的地址
              </span>
            </el-descriptions-item>
          </el-descriptions>
        </section>

        <!-- 存储（仅管理员） -->
        <section v-if="about.storage" class="about-section pv-panel">
          <h4 class="pv-section-title">存储</h4>
          <div class="about-disk">
            <div class="about-disk-head">
              <span class="pv-muted">
                已用 {{ about.storage.used_gb }} GB / 共 {{ about.storage.total_gb }} GB，
                可用 {{ about.storage.available_gb }} GB
              </span>
            </div>
            <el-progress
              :percentage="diskPercentage"
              :status="diskStatus"
              :stroke-width="12"
            />
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="存储根目录">
              <span class="about-path">{{ about.storage.storage_root }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="照片存储目录">
              <span class="about-path">{{ about.storage.media_root }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="数据库">
              <span class="about-path">{{ about.storage.database_path }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="日志目录">
              <span class="about-path">{{ about.storage.log_dir }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="模型目录">
              <span class="about-path">{{ about.storage.models_root }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </section>

        <!-- 配置（仅管理员） -->
        <section v-if="about.config" class="about-section pv-panel">
          <h4 class="pv-section-title">生效配置</h4>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="用户数上限">{{ about.config.max_users }}</el-descriptions-item>
            <el-descriptions-item label="开放注册">
              <el-tag size="small" :type="about.config.allow_registration ? 'success' : 'info'">
                {{ about.config.allow_registration ? '已开启' : '已关闭' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="分片大小">{{ about.config.chunk_size_mb }} MB</el-descriptions-item>
            <el-descriptions-item label="上传会话有效期">
              {{ about.config.session_expire_days }} 天
            </el-descriptions-item>
            <el-descriptions-item label="回收站保留">
              {{ about.config.trash_retention_days }} 天
            </el-descriptions-item>
            <el-descriptions-item label="日志级别">{{ about.config.log_level }}</el-descriptions-item>
            <el-descriptions-item label="访问令牌有效期">
              {{ about.config.access_token_expire_hours }} 小时
            </el-descriptions-item>
            <el-descriptions-item label="刷新令牌有效期">
              {{ about.config.refresh_token_expire_days }} 天
            </el-descriptions-item>
            <el-descriptions-item label="智能分析" :span="2">
              <el-tag
                v-for="item in analysisFlags"
                :key="item.label"
                class="about-flag"
                size="small"
                :type="item.enabled ? 'success' : 'info'"
              >
                {{ item.label }}{{ item.enabled ? '：开' : '：关' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </section>

        <p v-if="!about.storage" class="about-hint pv-muted">
          存储与配置信息仅管理员可见。
        </p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { InfoFilled, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { getServerAbout } from '@/api/server'
import type { ServerAbout } from '@/api/server'
import { formatDate } from '@/api/files'

const loading = ref(false)
const error = ref('')
const about = ref<ServerAbout | null>(null)

// Injected by Vite at build time from package.json (see vite.config.ts).
const webVersion = __APP_VERSION__

// 浏览器当前使用的地址。和「局域网地址」并列展示，是因为两者本就可能不同：
// 反向代理、自定义域名、多网卡时，服务端枚举出的地址不一定是你正在用的这个。
const currentOrigin = window.location.origin

const uptimeText = computed(() => formatUptime(about.value?.uptime_seconds ?? 0))

const diskPercentage = computed(() => {
  const storage = about.value?.storage
  if (!storage || storage.total_gb <= 0) return 0
  return Math.min(100, Math.round((storage.used_gb / storage.total_gb) * 100))
})

// 与后端磁盘告警阈值（<1GB 警告 / <0.5GB 错误）保持一致的配色。
const diskStatus = computed<'' | 'success' | 'warning' | 'exception'>(() => {
  const storage = about.value?.storage
  if (!storage) return ''
  if (storage.available_gb < 0.5) return 'exception'
  if (storage.available_gb < 1) return 'warning'
  return 'success'
})

const analysisFlags = computed(() => {
  const config = about.value?.config
  if (!config) return []
  return [
    { label: '地点', enabled: config.enable_place },
    { label: '场景', enabled: config.enable_scene },
    { label: '人物', enabled: config.enable_face },
  ]
})

function formatUptime(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  if (total < 60) return `${total} 秒`

  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)

  const parts: string[] = []
  if (days > 0) parts.push(`${days} 天`)
  if (hours > 0) parts.push(`${hours} 小时`)
  // 只有在不足一天时才显示分钟，避免「12 天 3 小时 7 分钟」这种过长的读数。
  if (minutes > 0 && days === 0) parts.push(`${minutes} 分钟`)
  return parts.join(' ') || '不足 1 分钟'
}

async function loadAbout() {
  loading.value = true
  error.value = ''
  try {
    about.value = await getServerAbout()
  } catch (err: any) {
    error.value = err.response?.data?.detail || '获取服务端信息失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAbout()
})
</script>

<style scoped>
.about-body {
  margin-top: var(--pv-page-gutter);
  min-height: 200px;
}

.about-alert {
  margin-bottom: var(--pv-gap);
}

.about-section {
  padding: 16px;
}

.about-section + .about-section {
  margin-top: var(--pv-gap);
}

.about-disk {
  margin-bottom: var(--pv-gap);
}

.about-disk-head {
  margin-bottom: 8px;
}

/* 绝对路径可能很长，用等宽字体并允许换行，避免撑破表格 */
.about-path {
  font-family: var(--el-font-family-monospace, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
  word-break: break-all;
}

.about-ip + .about-ip,
.about-flag + .about-flag {
  margin-left: 8px;
}

.about-lan-hint {
  display: block;
  margin-top: 6px;
}

.about-hint {
  margin: var(--pv-gap) 0 0;
}
</style>
