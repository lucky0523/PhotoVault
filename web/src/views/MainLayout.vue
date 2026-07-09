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
        <el-menu-item index="/devices">
          <el-icon><Monitor /></el-icon>
          <span>设备管理</span>
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
        <el-menu-item v-if="authStore.isAdmin" index="/admin/users">
          <el-icon><UserFilled /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header>
        <div class="header-content">
          <span class="username">{{ authStore.username }}</span>
          <el-button type="text" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTrashStore } from '@/stores/trash'

const route = useRoute()
const authStore = useAuthStore()
const trashStore = useTrashStore()

const activeMenu = computed(() => route.path)

// Refresh the recycle-bin count on mount and whenever the route changes,
// so the badge stays in sync after move-to-trash / restore / purge actions.
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
.main-layout {
  height: 100vh;
}

.logo {
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-bottom: 1px solid #e6e6e6;
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
  background: #fff;
  border-right: 1px solid #e6e6e6;
}

.el-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.username {
  color: #606266;
  font-size: 14px;
}

.el-main {
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
</style>
