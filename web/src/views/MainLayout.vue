<template>
  <el-container class="main-layout">
    <el-aside width="200px">
      <div class="logo">
        <img src="/icon.png" alt="影集" class="logo-icon" />
        <h2>影集</h2>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
      >
        <el-menu-item index="/photos">
          <el-icon><Picture /></el-icon>
          <span>图片浏览</span>
        </el-menu-item>
        <el-menu-item index="/timeline">
          <el-icon><Timer /></el-icon>
          <span>时间线</span>
        </el-menu-item>
        <el-menu-item index="/explore">
          <el-icon><Compass /></el-icon>
          <span>探索</span>
        </el-menu-item>
        <el-menu-item index="/statistics">
          <el-icon><DataAnalysis /></el-icon>
          <span>数据统计</span>
        </el-menu-item>
        <el-menu-item index="/trash">
          <el-icon><DeleteFilled /></el-icon>
          <span class="trash-menu-label">
            <span>回收站</span>
            <span v-if="trashStore.count > 0" class="trash-count">
              {{ trashStore.count > 999 ? '999+' : trashStore.count }}
            </span>
          </span>
        </el-menu-item>

        <!-- 设置：设备管理 / 用户管理 / 客户端安装包管理（仅管理员）/
             服务器地址（仅管理员）/ 关于服务端。
             el-sub-menu 的 index 只是分组标识，不参与路由；子项的 index 仍是
             真实路径，所以进入任一子页面时父级会自动展开并高亮。 -->
        <el-sub-menu index="settings">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>设置</span>
          </template>
          <el-menu-item index="/settings/devices">
            <el-icon><Monitor /></el-icon>
            <span>设备管理</span>
          </el-menu-item>
          <el-menu-item index="/settings/users">
            <el-icon><UserFilled /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item v-if="authStore.isAdmin" index="/settings/client-versions">
            <el-icon><Cellphone /></el-icon>
            <span>客户端安装包管理</span>
          </el-menu-item>
          <el-menu-item v-if="authStore.isAdmin" index="/settings/server-address">
            <el-icon><Link /></el-icon>
            <span>服务器地址</span>
          </el-menu-item>
          <el-menu-item index="/settings/about">
            <el-icon><InfoFilled /></el-icon>
            <span>关于服务端</span>
          </el-menu-item>

          <!-- 飞牛构建专属入口。列表在 script 里按 __FNOS_BUILD__ 生成，普通构建
               下 fnosMenuItems 为空数组，这一段不渲染。 -->
          <el-menu-item
            v-for="item in visibleFnosMenuItems"
            :key="item.path"
            :index="item.path"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header>
        <div class="header-content">
          <el-tooltip content="下载手机 App" placement="bottom">
            <el-button
              class="qrcode-btn"
              circle
              size="default"
              @click="showAppDownload = true"
            >
              <el-icon :size="18"><Cellphone /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="服务器二维码" placement="bottom">
            <el-button
              class="qrcode-btn"
              circle
              size="default"
              @click="showQrcode = true"
            >
              <el-icon :size="18"><QrCodeIcon /></el-icon>
            </el-button>
          </el-tooltip>
          <span class="username">{{ authStore.username }}</span>
          <el-button type="text" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>

    <el-dialog
      v-model="showAppDownload"
      title="下载手机 App"
      width="400px"
      align-center
      @open="renderAppDownload"
    >
      <div v-loading="appDownloadLoading" class="qrcode-dialog-body app-download-body">
        <template v-if="latestClient && appDownloadUrl">
          <div class="qrcode-wrapper">
            <img
              v-if="appQrcodeDataUrl"
              :src="appQrcodeDataUrl"
              alt="Android App 下载二维码"
              class="qrcode-img"
            />
            <div v-else class="qrcode-loading">生成中...</div>
          </div>
          <div class="app-version">
            Android {{ latestClient.version_name }}
            <span>（versionCode {{ latestClient.version_code }}）</span>
          </div>
          <el-link
            class="download-link"
            type="primary"
            :href="appDownloadUrl"
            target="_blank"
          >
            下载最新Android客户端
          </el-link>
          <p class="qrcode-hint">手机扫描二维码或点击链接下载最新版 APK</p>
        </template>
        <el-empty
          v-else-if="!appDownloadLoading"
          :description="appDownloadError || '管理员尚未上传 Android 客户端'"
          :image-size="80"
        />
      </div>
    </el-dialog>

    <el-dialog
      v-model="showQrcode"
      title="服务器二维码"
      width="360px"
      align-center
      @open="renderQrcode"
    >
      <!-- 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。
           二维码内容只来自 connect-qr 接口返回的 payload，Web 端不拼载荷。 -->
      <div class="qrcode-dialog-body">
        <template v-if="qrState === 'ready' && connectQr">
          <div class="qrcode-wrapper">
            <img v-if="qrcodeDataUrl" :src="qrcodeDataUrl" alt="服务器二维码" class="qrcode-img" />
          </div>
          <div class="server-addresses">
            <p v-if="connectQr.public_address" class="server-url">
              公网：{{ connectQr.public_address }}
              <span v-if="sourceLabel(connectQr.public_source)" class="address-source">
                {{ sourceLabel(connectQr.public_source) }}
              </span>
            </p>
            <p v-if="connectQr.lan_address" class="server-url">
              局域网：{{ connectQr.lan_address }}
              <span v-if="sourceLabel(connectQr.lan_source)" class="address-source">
                {{ sourceLabel(connectQr.lan_source) }}
              </span>
            </p>
            <p v-if="!connectQr.public_address" class="qrcode-warning">
              未配置公网地址，手机在外时无法连接
              <el-link v-if="authStore.isAdmin" type="primary" @click="goToAddressSettings">
                去设置
              </el-link>
            </p>
          </div>
          <p class="qrcode-hint">扫描二维码以连接到 PhotoVault 服务器</p>
          <p class="qrcode-legacy-hint">旧版 App 无法识别此二维码，请先通过“下载手机 App”升级</p>
        </template>
        <el-empty
          v-else-if="qrState === 'empty'"
          :description="
            authStore.isAdmin
              ? '尚未配置服务器地址，也未检测到局域网地址'
              : '尚未配置服务器地址，请联系管理员配置'
          "
          :image-size="80"
        >
          <el-button v-if="authStore.isAdmin" type="primary" link @click="goToAddressSettings">
            前往设置服务器地址
          </el-button>
        </el-empty>
        <el-empty
          v-else-if="qrState === 'error'"
          description="无法获取服务器地址，请稍后重试"
          :image-size="80"
        />
        <div v-else class="qrcode-wrapper">
          <div class="qrcode-loading">生成中...</div>
        </div>
      </div>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTrashStore } from '@/stores/trash'
import { getConnectQr, getServerInfo } from '@/api/server'
import type { ConnectQrInfo, LanAddressSource, PublicAddressSource } from '@/api/server'
import { getLatestAndroidClient } from '@/api/clientVersions'
import type { ClientVersionInfo } from '@/api/clientVersions'
import QRCode from 'qrcode'

// Inline QR-code-style SVG icon (Element Plus has no dedicated QrCode icon)
const QrCodeIcon = () =>
  h(
    'svg',
    { viewBox: '0 0 1024 1024', xmlns: 'http://www.w3.org/2000/svg' },
    [
      h('path', {
        d: 'M464 480H160V176h304v304zM224 416h176V240H224v176zm592-240H512v304h304V176zm-64 240H576V240h176v176zM464 544H160v304h304V544zm-64 240H224V608h176v176zm384-240H512v304h304V544zm-64 240H576V608h176v176zM640 544h64v64h-64zM768 640h64v64h-64zM640 736h64v64h-64z',
      }),
    ]
  )

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const trashStore = useTrashStore()

const activeMenu = computed(() => route.path)

// --- 飞牛（fnOS）专属菜单项 -------------------------------------------------
// 整个列表（路径、文案、图标名）都写在 __FNOS_BUILD__ 块里，普通构建下这段是
// `if (false) { ... }`，会被整块删除，dist 里连菜单文案都不会出现。
//
// icon 用图标组件名字符串而不是 import 进来的组件：main.ts 已把 Element Plus
// 图标全量注册为全局组件，模板里 <component :is="名字"> 能直接解析。写成 import
// 就得放在模块顶层，那条静态依赖会一直留在普通构建里。
interface FnosMenuItem {
  path: string
  label: string
  icon: string
  adminOnly?: boolean
}

const fnosMenuItems: FnosMenuItem[] = []

if (__FNOS_BUILD__) {
  fnosMenuItems.push({
    path: '/settings/fnos-shared-access',
    label: '飞牛目录授权',
    icon: 'FolderOpened',
    adminOnly: true,
  })
}

// 在这里过滤而不是在模板上写 v-if：同一元素上 v-if 的优先级高于 v-for，
// v-if 里拿不到 item。
const visibleFnosMenuItems = computed(() =>
  fnosMenuItems.filter((item) => !item.adminOnly || authStore.isAdmin)
)

const showQrcode = ref(false)
const qrcodeDataUrl = ref('')
const qrState = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')
const connectQr = ref<ConnectQrInfo | null>(null)
// 非响应式的请求序号：对话框反复开关时，过期请求的结果不能覆盖新状态。
let qrRequestSeq = 0
const showAppDownload = ref(false)
const appDownloadLoading = ref(false)
const appQrcodeDataUrl = ref('')
const appDownloadUrl = ref('')
const appDownloadError = ref('')
const latestClient = ref<ClientVersionInfo | null>(null)

// isLocalHostname / resolveServerUrl 仅供「下载手机 App」对话框（renderAppDownload）
// 使用；「服务器二维码」对话框的地址由服务端 connect-qr 接口决定，不走这里。
function isLocalHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1'
}

// 仅供下载手机 App 对话框使用（见上）。
async function resolveServerUrl(): Promise<string> {
  const hostname = window.location.hostname
  // If already accessing via a real IP/host, trust the current origin.
  if (!isLocalHostname(hostname)) {
    return window.location.origin
  }
  // localhost: ask the server for its LAN IP so mobile devices can reach it.
  try {
    const info = await getServerInfo()
    if (info.lan_ips.length > 0) {
      return `http://${info.lan_ips[0]}:${info.port}`
    }
  } catch {
    // fall through to window.location.origin
  }
  return window.location.origin
}

async function renderAppDownload() {
  appDownloadLoading.value = true
  appQrcodeDataUrl.value = ''
  appDownloadUrl.value = ''
  appDownloadError.value = ''
  latestClient.value = null
  try {
    const [baseUrl, client] = await Promise.all([
      resolveServerUrl(),
      getLatestAndroidClient(),
    ])
    if (!client.download_url) {
      appDownloadError.value = '最新版客户端缺少下载地址'
      return
    }
    latestClient.value = client
    appDownloadUrl.value = new URL(client.download_url, `${baseUrl}/`).toString()
    appQrcodeDataUrl.value = await QRCode.toDataURL(appDownloadUrl.value, {
      width: 240,
      margin: 2,
      color: { dark: '#000000', light: '#ffffff' },
    })
  } catch (error: any) {
    appDownloadError.value =
      error.response?.status === 404
        ? '管理员尚未上传 Android 客户端'
        : '无法获取最新版 Android 客户端'
  } finally {
    appDownloadLoading.value = false
  }
}

// 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。
// 载荷由服务端唯一的编码器生成，这里只把 payload 原样画成二维码；当前 origin 原样
// 交给服务端，由它决定是否采用。失败时显示错误，不回退到旧的单地址格式。
async function renderQrcode() {
  const seq = ++qrRequestSeq
  qrState.value = 'loading'
  qrcodeDataUrl.value = ''
  connectQr.value = null
  try {
    const info = await getConnectQr(window.location.origin)
    if (seq !== qrRequestSeq) return
    if (!info.payload) {
      connectQr.value = info
      qrState.value = 'empty'
      return
    }
    const dataUrl = await QRCode.toDataURL(info.payload, {
      width: 240,
      margin: 2,
      errorCorrectionLevel: 'M',
      color: { dark: '#000000', light: '#ffffff' },
    })
    if (seq !== qrRequestSeq) return
    connectQr.value = info
    qrcodeDataUrl.value = dataUrl
    qrState.value = 'ready'
  } catch {
    if (seq !== qrRequestSeq) return
    qrState.value = 'error'
  }
}

function sourceLabel(source: PublicAddressSource | LanAddressSource | null): string {
  if (source === 'origin') return '（当前访问地址）'
  if (source === 'detected') return '（自动检测）'
  return ''
}

function goToAddressSettings() {
  showQrcode.value = false
  router.push({ name: 'ServerAddress' })
}

onMounted(() => {
  trashStore.refresh()
})

watch(
  () => route.path,
  () => {
    trashStore.refresh()
  }
)

function handleLogout() {
  authStore.logout()
}
</script>

<style scoped>
/* --pv-topbar-height 与 --pv-divider-color 是全局 token（styles/layout.css）：
   logo 底边线与顶栏底边线共用同一高度，保证两条线严格对齐；
   所有分隔线共用同一颜色与 1px 宽度，避免粗细/深浅不一致。 */
.main-layout {
  height: 100vh;
}

.logo {
  height: var(--pv-topbar-height);
  box-sizing: border-box;
  flex-shrink: 0;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-bottom: 1px solid var(--pv-divider-color);
}

.logo-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  object-fit: cover;
}

.logo h2 {
  margin: 0;
  font-size: 18px;
  color: #303133;
}

.el-aside {
  display: flex;
  flex-direction: column;
  /* 菜单区域内部滚动，避免整个侧栏滚动时分隔线被滚动条打断 */
  overflow: hidden;
  background: #fff;
  border-right: 1px solid var(--pv-divider-color);
}

/* Element Plus 的 el-menu 自带 border-right，与 el-aside 的右边框叠加会形成
   2px 且颜色更深的“双线”，只在菜单区域出现，导致上下粗细不一。这里去掉，
   侧栏右侧只保留 el-aside 的一条 1px 分隔线，从上到下完全一致。 */
.el-menu {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-right: none;
}

.el-header {
  height: var(--pv-topbar-height);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  background: #fff;
  border-bottom: 1px solid var(--pv-divider-color);
}

.header-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.qrcode-btn {
  color: #606266;
}

.username {
  color: #606266;
  font-size: 14px;
}

/* 内边距归零，改由各页面自己用统一的 --pv-page-gutter 控制。
   此前 el-main 的 20px 会和「页面自己又写了一份 padding」叠加，
   导致设备管理/用户管理/回收站是 40px，而图片浏览/时间线是 20px。 */
.el-main {
  padding: 0;
  background: #f5f7fa;
}

.trash-menu-label {
  display: inline-flex;
  align-items: center;
  width: 100%;
}

.trash-count {
  margin-left: auto;
  padding: 1px 8px;
  background: #f0f2f5;
  border-radius: 10px;
  color: #909399;
  font-size: 12px;
  line-height: 16px;
  min-width: 18px;
  text-align: center;
}

.qrcode-dialog-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.app-download-body {
  min-height: 340px;
  justify-content: center;
}

.app-version {
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.app-version span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.download-link {
  max-width: 100%;
  font-size: 13px;
}

.download-link :deep(.el-link__inner) {
  display: block;
  max-width: 100%;
  overflow-wrap: anywhere;
  text-align: center;
}

.qrcode-wrapper {
  width: 240px;
  height: 240px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.qrcode-img {
  width: 240px;
  height: 240px;
}

.qrcode-loading {
  color: #909399;
  font-size: 14px;
}

.server-url {
  margin: 0;
  word-break: break-all;
  text-align: center;
  font-size: 13px;
  color: #606266;
}

.qrcode-hint {
  margin: 0;
  font-size: 12px;
  color: #909399;
}

.server-addresses {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}

.address-source {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.qrcode-warning {
  margin: 0;
  text-align: center;
  font-size: 13px;
  color: var(--el-color-warning);
}

.qrcode-legacy-hint {
  margin: 0;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
