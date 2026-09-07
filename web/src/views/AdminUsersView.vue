<template>
  <div class="admin-users-view pv-page">
    <PageHeader
      title="用户管理"
      subtitle="查看系统用户"
      :icon="UserFilled"
    >
      <template #extra>
        <el-button type="primary" @click="showCreateDialog = true">
          创建用户
        </el-button>
      </template>
    </PageHeader>

    <div class="users-table-wrapper pv-panel" v-loading="loading">
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
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openResetPasswordDialog(row)">
              {{ authStore.isAdmin ? '重置密码' : '修改密码' }}
            </el-button>
            <el-popconfirm
              :width="360"
              :title="`确定要清除用户“${row.username}”的删除记录吗？仅移除已彻底删除文件的同步记录，此操作不可恢复。`"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleClearPurgedRecords(row)"
            >
              <template #reference>
                <el-button
                  size="small"
                  type="warning"
                  :loading="clearingUserId === row.id"
                >
                  清除“删除记录”
                </el-button>
              </template>
            </el-popconfirm>
            <el-popconfirm
              v-if="authStore.isAdmin"
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
      :title="authStore.isAdmin ? '重置密码' : '修改密码'"
      width="420px"
      @close="resetPasswordForm"
    >
      <p class="reset-hint">
        <template v-if="authStore.isAdmin">
          为用户 <strong>{{ resetTarget?.username }}</strong> 设置新密码
        </template>
        <template v-else>验证当前密码后设置新密码</template>
      </p>
      <el-form
        ref="resetFormRef"
        :model="resetForm"
        :rules="resetRules"
        label-width="90px"
      >
        <el-form-item v-if="!authStore.isAdmin" label="当前密码" prop="currentPassword">
          <el-input
            v-model="resetForm.currentPassword"
            type="password"
            placeholder="请输入当前密码"
            show-password
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="resetForm.newPassword"
            type="password"
            placeholder="请输入新密码（至少8位）"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="resetForm.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showResetPasswordDialog = false">取消</el-button>
        <el-button type="primary" :loading="resetLoading" @click="handleResetPassword">
          {{ authStore.isAdmin ? '确认重置' : '确认修改' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { UserFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  listUsers,
  createUser,
  deleteUser,
  clearPurgedRecords,
  changePassword,
} from '@/api/admin'
import { changeOwnPassword } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { validatePasswordChars } from '@/utils/validators'
import type { UserInfo } from '@/api/admin'

const authStore = useAuthStore()
const loading = ref(false)
const users = ref<UserInfo[]>([])
const clearingUserId = ref<number | null>(null)

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
    { validator: validatePasswordChars, trigger: 'blur' },
  ],
}

// Reset password
const showResetPasswordDialog = ref(false)
const resetLoading = ref(false)
const resetTarget = ref<UserInfo | null>(null)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const validateResetConfirmPassword = (_rule: any, value: string, callback: any) => {
  if (value !== resetForm.newPassword) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}
const resetRules: FormRules = {
  currentPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: validatePasswordChars, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    { validator: validateResetConfirmPassword, trigger: 'blur' },
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

async function handleClearPurgedRecords(user: UserInfo) {
  clearingUserId.value = user.id
  try {
    const response = await clearPurgedRecords(user.id)
    ElMessage.success(`已清除 ${response.count} 条删除记录`)
    await loadUsers()
  } catch (error: any) {
    const msg = error.response?.data?.detail || '清除删除记录失败'
    ElMessage.error(msg)
  } finally {
    clearingUserId.value = null
  }
}

function openResetPasswordDialog(user: UserInfo) {
  resetTarget.value = user
  showResetPasswordDialog.value = true
}

async function handleResetPassword() {
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid || !resetTarget.value) return

  resetLoading.value = true
  try {
    if (authStore.isAdmin) {
      await changePassword(resetTarget.value.id, resetForm.newPassword)
    } else {
      await changeOwnPassword(resetForm.currentPassword, resetForm.newPassword)
    }
    ElMessage.success(authStore.isAdmin ? '密码重置成功' : '密码修改成功')
    showResetPasswordDialog.value = false
    resetPasswordForm()
  } catch (error: any) {
    const fallback = authStore.isAdmin ? '重置密码失败' : '修改密码失败'
    const msg = error.response?.data?.detail || fallback
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
  resetForm.currentPassword = ''
  resetForm.newPassword = ''
  resetForm.confirmPassword = ''
  resetTarget.value = null
  resetFormRef.value?.resetFields()
}

onMounted(() => {
  loadUsers()
})
</script>

<style scoped>
/* 表格放进白色面板里，和其他页面的卡片/面板保持同一层级语言 */
.users-table-wrapper {
  margin-top: var(--pv-page-gutter);
  min-height: 200px;
  padding: 4px 12px 12px;
  overflow: hidden;
}

.admin-badge {
  margin-left: 8px;
}

.reset-hint {
  margin-bottom: 16px;
  color: #606266;
}

/* Allow long password validation messages to wrap and display fully */
:deep(.el-form-item__error) {
  position: static;
  white-space: normal;
  line-height: 1.4;
  margin-top: 2px;
}
</style>
