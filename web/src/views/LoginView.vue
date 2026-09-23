<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <div class="login-header">
          <h1>PhotoVault</h1>
          <p>手机图片备份系统</p>
        </div>
      </template>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="login-error"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="0"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.rememberLogin">记住登录状态</el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            style="width: 100%"
            native-type="submit"
          >
            登录
          </el-button>
        </el-form-item>

        <el-form-item v-if="allowRegistration">
          <div class="register-link">
            没有账户？<router-link to="/register">注册新账户</router-link>
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getRegistrationStatus } from '@/api/auth'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorMessage = ref('')
const allowRegistration = ref(false)

const form = reactive({
  username: '',
  password: '',
  rememberLogin: true,
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

onMounted(async () => {
  try {
    const status = await getRegistrationStatus()
    allowRegistration.value = status.allow_registration
  } catch {
    // If the endpoint fails, hide registration link
    allowRegistration.value = false
  }
})

async function handleLogin() {
  if (!formRef.value) return

  await formRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    errorMessage.value = ''

    try {
      // 不勾"记住登录状态"时凭证只写进 sessionStorage：关掉浏览器就失效，但本次
      // 会话里照样能用。原来的做法是登录成功后立刻把令牌从 localStorage 删掉，
      // 等于刚登录就退出，随后每个请求都是 401。
      await authStore.login(form.username, form.password, form.rememberLogin)

      const redirect = (route.query.redirect as string) || '/photos'
      router.push(redirect)
    } catch (error: any) {
      errorMessage.value =
        error.response?.data?.detail || '登录失败，请检查用户名和密码'
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f0f2f5;
}

.login-card {
  width: 400px;
}

.login-header {
  text-align: center;
}

.login-header h1 {
  margin: 0;
  font-size: 28px;
  color: #303133;
}

.login-header p {
  margin: 8px 0 0;
  color: #909399;
  font-size: 14px;
}

.login-error {
  margin-bottom: 16px;
}

.register-link {
  width: 100%;
  text-align: center;
  color: #909399;
  font-size: 14px;
}

.register-link a {
  color: #409eff;
  text-decoration: none;
}

.register-link a:hover {
  text-decoration: underline;
}
</style>
