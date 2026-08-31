<template>
  <el-container class="main-layout">
    <el-aside width="200px">
      <div class="logo">
        <img src="/icon.png" alt="PhotoVault" class="logo-icon" />
        <h2>PhotoVault</h2>
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
        <el-menu-item index="/trash">
          <el-icon><DeleteFilled /></el-icon>
          <span class="trash-menu-label">
            <span>回收站</span>
            <span v-if="trashStore.count > 0" class="trash-count">
              {{ trashStore.count > 999 ? '999+' : trashStore.count }}
            </span>
          </span>
        </el-menu-item>

        <!-- 设置：设备管理 / 用户管理 / 关于服务端。
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
          <el-menu-item v-if="authStore.isAdmin" index="/settings/users">
            <el-icon><UserFilled /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/settings/about">
            <el-icon><InfoFilled /></el-icon>
            <span>关于服务端</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header>
        <div class="header-content">
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
      v-model="showQrcode"
      title="服务器二维码"
      width="360px"
      align-center
      @open="renderQrcode"
    >
      <div class="qrcode-dialog-body">
        <div class="qrcode-wrapper">
          <img v-if="qrcodeDataUrl" :src="qrcodeDataUrl" alt="server qrcode" class="qrcode-img" />
          <div v-else class="qrcode-loading">生成中...</div>
        </div>
        <p class="server-url">{{ serverUrl }}</p>
        <p class="qrcode-hint">扫描二维码以连接到 PhotoVault 服务器</p>
      </div>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTrashStore } from '@/stores/trash'
import { getServerInfo } from '@/api/server'
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
const authStore = useAuthStore()
const trashStore = useTrashStore()

const activeMenu = computed(() => route.path)

const showQrcode = ref(false)
const qrcodeDataUrl = ref('')
const serverUrl = ref(window.location.origin)

function isLocalHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1'
}

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

async function renderQrcode() {
  qrcodeDataUrl.value = ''
  serverUrl.value = await resolveServerUrl()
  try {
    qrcodeDataUrl.value = await QRCode.toDataURL(serverUrl.value, {
      width: 240,
      margin: 2,
      color: { dark: '#000000', light: '#ffffff' },
    })
  } catch {
    qrcodeDataUrl.value = ''
  }
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
</style>
