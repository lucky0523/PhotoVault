<template>
  <div class="admin-users-view">
    <el-page-header title="用户管理">
      <template #content>
        <span>管理系统用户（仅管理员可见）</span>
      </template>
      <template #extra>
        <el-button type="primary" @click="showCreateDialog = true">
          创建用户
        </el-button>
      </template>
    </el-page-header>

    <div class="users-table-wrapper" v-loading="loading">
      <el-table :data="users" stripe style="width: 100%">
        <el-table-column prop="username" label="用户名" min-width="120">
          <template #default="{ row }">
            <span>{{ row.username }}</span>
            <el-tag v-if="row.is_admin" type="warning" size="small" class="admin-badge">
              管理员
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="创建时间" min-width="160">
          <template #default="{ row }">
            {{ formatCreatedAt(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openResetPasswordDialog(row)">
              重置密码
            </el-button>
            <el-popconfirm
              title="确定要删除该用户吗？此操作不可恢复。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDeleteUser(row.id)"
            >
              <template #reference>
                <el-button size="small" type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Create User Dialog -->
    <el-dialog
      v-model="showCreateDialog"
      title="创建用户"
      width="420px"
      @close="resetCreateForm"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="80px"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="createForm.password"
            type="password"
            placeholder="请输入密码（至少8位）"
            show-password
          />
        </el-form-item>
        <el-form-item label="管理员">
          <el-checkbox v-model="createForm.is_admin">设为管理员</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="handleCreateUser">
          创建
        </el-button>
      </template>
    </el-dialog>

    <!-- Reset Password Dialog -->
    <el-dialog
      v-model="showResetPasswordDialog"
      title="重置密码"
      width="420px"
      @close="resetPasswordForm"
    >
      <p class="reset-hint">
        为用户 <strong>{{ resetTarget?.username }}</strong> 设置新密码
      </p>
      <el-form
        ref="resetFormRef"
        :model="resetForm"
        :rules="resetRules"
        label-width="80px"
      >
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="resetForm.newPassword"
            type="password"
            placeholder="请输入新密码（至少8位）"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showResetPasswordDialog = false">取消</el-button>
        <el-button type="primary" :loading="resetLoading" @click="handleResetPassword">
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { listUsers, createUser, deleteUser, changePassword } from '@/api/admin'
import type { UserInfo } from '@/api/admin'

const loading = ref(false)
const users = ref<UserInfo[]>([])

// Create user
const showCreateDialog = ref(false)
const createLoading = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  username: '',
  password: '',
  is_admin: false,
})
const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码长度不少于8位', trigger: 'blur' },
  ],
}

// Reset password
const showResetPasswordDialog = ref(false)
const resetLoading = ref(false)
const resetTarget = ref<UserInfo | null>(null)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({
  newPassword: '',
})
const resetRules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '密码长度不少于8位', trigger: 'blur' },
  ],
}

function formatCreatedAt(dateStr: string): string {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

async function loadUsers() {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch (error) {
    ElMessage.error('加载用户列表失败')
    console.error('Failed to load users:', error)
  } finally {
    loading.value = false
  }
}

async function handleCreateUser() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return

  createLoading.value = true
  try {
    await createUser({
      username: createForm.username,
      password: createForm.password,
      is_admin: createForm.is_admin,
    })
    ElMessage.success('用户创建成功')
    showCreateDialog.value = false
    resetCreateForm()
    await loadUsers()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '创建用户失败'
    ElMessage.error(msg)
  } finally {
    createLoading.value = false
  }
}

async function handleDeleteUser(userId: number) {
  try {
    await deleteUser(userId)
    ElMessage.success('用户已删除')
    await loadUsers()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '删除用户失败'
    ElMessage.error(msg)
  }
}

function openResetPasswordDialog(user: UserInfo) {
  resetTarget.value = user
  showResetPasswordDialog.value = true
}

async function handleResetPassword() {
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) return

  if (!resetTarget.value) return

  resetLoading.value = true
  try {
    await changePassword(resetTarget.value.id, resetForm.newPassword)
    ElMessage.success('密码重置成功')
    showResetPasswordDialog.value = false
    resetPasswordForm()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '重置密码失败'
    ElMessage.error(msg)
  } finally {
    resetLoading.value = false
  }
}

function resetCreateForm() {
  createForm.username = ''
  createForm.password = ''
  createForm.is_admin = false
  createFormRef.value?.resetFields()
}

function resetPasswordForm() {
  resetForm.newPassword = ''
  resetTarget.value = null
  resetFormRef.value?.resetFields()
}

onMounted(() => {
  loadUsers()
})
</script>

<style scoped>
.admin-users-view {
  padding: 20px;
}

.users-table-wrapper {
  margin-top: 24px;
  min-height: 200px;
}

.admin-badge {
  margin-left: 8px;
}

.reset-hint {
  margin-bottom: 16px;
  color: #606266;
}
</style>
