<template>
  <el-container class="main-layout">
    <el-aside width="200px">
      <div class="logo">
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
          <span>回收站</span>
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
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const authStore = useAuthStore()

const activeMenu = computed(() => route.path)

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
  text-align: center;
  border-bottom: 1px solid #e6e6e6;
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
</style>
