<!--
  初始化向导。

  两种形态，由后端 /setup/status 的 needs_work_dir 决定：

    needs_work_dir=false（Docker / 本地开发）
      一步：创建管理员账户。存储位置由配置决定，与本页无关。

    needs_work_dir=true（飞牛应用）
      两步：填账号 -> 选择并授权工作目录 -> 一次性提交。
      飞牛的照片只能放在平台授予过 ACL 的目录里，而授权只能在跑起来的应用界面里
      完成，所以服务端在选定之前刻意不建数据库。

  为什么两步的数据合并成一个请求提交：这样服务端要么全部建好、要么什么都不留。
  中途放弃不会留下半初始化状态，下次启动会重新走一遍——这正是需求里那条
  「没完成一整套流程就重走」。也因此不需要在服务端内存里暂存密码。

  飞牛相关的代码全部包在 __FNOS_BUILD__ 里并用动态 import，普通构建下这些块是
  `if (false)`，会被整块删掉，SDK 不会进普通产物（见 vite.config.ts 的说明）。
-->
<template>
  <div class="setup-container">
    <el-card class="setup-card" :class="{ 'setup-card--wide': needsWorkDir }">
      <template #header>
        <div class="setup-header">
          <h1>PhotoVault 初始化</h1>
          <p>{{ headerHint }}</p>
        </div>
      </template>

      <el-steps v-if="needsWorkDir" :active="step" finish-status="success" simple class="setup-steps">
        <el-step title="管理员账户" />
        <el-step title="工作目录" />
      </el-steps>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="setup-alert"
      />

      <el-alert
        v-if="successMessage"
        :title="successMessage"
        type="success"
        show-icon
        :closable="false"
        class="setup-alert"
      />

      <!-- 第一步：管理员账户 -->
      <el-form
        v-show="step === 0"
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="0"
        @submit.prevent="handlePrimaryAction"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="管理员用户名"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码（至少8个字符）"
            prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认密码"
            prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            style="width: 100%"
            native-type="submit"
          >
            {{ needsWorkDir ? '下一步：选择工作目录' : '创建管理员账户' }}
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 第二步：工作目录（仅飞牛） -->
      <div v-if="needsWorkDir" v-show="step === 1" class="setup-workdir">
        <el-alert type="info" :closable="false" show-icon class="setup-alert">
          <template #title>照片和数据库将存放在这里</template>
          飞牛只允许应用写入已授权的目录。请先完成授权，授权过的目录才会出现在下方
          列表中，然后从中选定一个容量充足的作为工作目录。
        </el-alert>

        <!-- 先授权：只有授权过的目录才存在可被应用操作的路径 -->
        <el-button
          :loading="picking"
          size="large"
          class="setup-workdir-auth"
          @click="handlePickDirectory"
        >
          选择目录并授权
        </el-button>

        <!-- 再从已授权的目录中选定 -->
        <div class="setup-workdir-field">
          <span class="setup-workdir-label">工作目录</span>
          <el-select
            v-model="workDir"
            :loading="optionsLoading"
            placeholder="请选择一个已授权的目录"
            size="large"
            class="setup-workdir-select"
          >
            <el-option
              v-for="opt in workDirOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            >
              <span class="setup-option-path">{{ opt.value }}</span>
              <span v-if="opt.tag" class="setup-option-tag">{{ opt.tag }}</span>
            </el-option>
          </el-select>
          <el-button
            :loading="optionsLoading"
            size="large"
            @click="loadWorkDirOptions()"
          >
            刷新
          </el-button>
        </div>

        <!-- 授权结果紧跟在选择框下面：它说明的是「列表里现在有什么」 -->
        <p v-if="pickHint" class="setup-workdir-hint">{{ pickHint }}</p>
        <p v-if="optionsHint" class="setup-workdir-note">{{ optionsHint }}</p>

        <el-alert
          v-if="previousPath"
          type="warning"
          :closable="false"
          show-icon
          class="setup-workdir-previous"
        >
          <template #title>检测到上次使用的工作目录</template>
          <p class="setup-previous-path">{{ previousPath }}</p>
          重新授权这个目录即可找回原有的照片和数据库。选择其他目录会作为全新的库开始，
          原目录中的文件不会被删除。
        </el-alert>

        <div class="setup-workdir-submit">
          <el-button size="large" :disabled="loading" @click="step = 0">上一步</el-button>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            :disabled="!workDir"
            style="flex: 1"
            @click="handleSubmit"
          >
            完成初始化
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getSetupStatus, getWorkDirOptions, initSetup } from '@/api/setup'
import { resetSetupStatusCache } from '@/router'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const picking = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const pickHint = ref('')

/** 0 = 管理员账户，1 = 工作目录 */
const step = ref(0)
const needsWorkDir = ref(false)
const workDir = ref('')

interface WorkDirOption {
  value: string
  label: string
  tag?: string
}

const workDirOptions = ref<WorkDirOption[]>([])
const optionsLoading = ref(false)
const optionsHint = ref('')
const previousPath = ref('')

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

const headerHint = computed(() =>
  needsWorkDir.value
    ? '首次使用请创建管理员账户并指定工作目录'
    : '首次使用请创建管理员账户'
)

const validateConfirmPassword = (_rule: any, value: string, callback: any) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  username: [{ required: true, message: '请输入管理员用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码长度不能少于8个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
}

// ---------------------------------------------------------------------------
// 初始状态
// ---------------------------------------------------------------------------

onMounted(async () => {
  try {
    const status = await getSetupStatus()
    needsWorkDir.value = status.needs_work_dir
  } catch {
    // 拿不到就按一步式走：真正的校验在提交时由服务端做，这里猜错也不会造成
    // 错误结果，只是少显示一个步骤条。
    needsWorkDir.value = false
  }

  if (needsWorkDir.value && __FNOS_BUILD__) {
    const { FNOS_AUTH_MESSAGE_TYPE, readAuthState, clearAuthState } = await import(
      '@/utils/fnos'
    )

    // 独立浏览器页面下走的是「路由授权」：新窗口完成后由回调页 postMessage 回来。
    // 回调结果里**不含**目录路径（要靠 /fnos/shared-folders 查，而那个接口需要管理员
    // 登录，初始化时还没有账号），所以这里只能提示用户把刚授权的目录填进输入框。
    authMessageHandler = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return
      const data = event.data as { type?: string; result?: Record<string, unknown> } | null
      if (data?.type !== FNOS_AUTH_MESSAGE_TYPE) return

      const result = data.result ?? {}
      const expected = readAuthState()
      if (expected && result.state !== expected) return
      clearAuthState()

      picking.value = false
      if (result.status === 'success') {
        // 回调本身不带路径，所以只能重新拉一遍授权列表——刚授权的目录会出现在里面。
        pickHint.value = '授权已完成，请在下方列表中选择刚授权的目录。'
        void loadWorkDirOptions()
      } else if (result.error === 'access_denied') {
        errorMessage.value = '授权被拒绝：只有飞牛管理员可以授权目录。'
      } else {
        errorMessage.value = `授权未完成（status=${String(result.status ?? '未知')}）`
      }
    }
    window.addEventListener('message', authMessageHandler)
  }
})

let authMessageHandler: ((event: MessageEvent) => void) | null = null

onBeforeUnmount(() => {
  if (authMessageHandler) window.removeEventListener('message', authMessageHandler)
})

// ---------------------------------------------------------------------------
// 候选目录
// ---------------------------------------------------------------------------

/**
 * 读取可选的工作目录。
 *
 * 默认共享文件夹总是列在最后作为兜底：它由平台自动授权、恒可写，所以即使一个目录
 * 都没授权、或者连不上飞牛网关，初始化也不会被卡死。
 */
async function loadWorkDirOptions(preferred?: string) {
  optionsLoading.value = true
  optionsHint.value = ''

  try {
    const opts = await getWorkDirOptions()
    previousPath.value = opts.previous_path

    workDirOptions.value = [
      ...opts.authorized_paths.map((p) => ({ value: p, label: p, tag: '已授权' })),
      {
        value: opts.default_path,
        label: opts.default_path,
        tag: '默认共享文件夹',
      },
    ]

    if (preferred && workDirOptions.value.some((o) => o.value === preferred)) {
      workDir.value = preferred
    } else if (!workDir.value) {
      // 不自动选中默认项：让「选定工作目录」保持为一次明确的选择，避免用户以为
      // 自己刚授权的目录已经生效。
      workDir.value = ''
    }

    if (!opts.gateway_available) {
      optionsHint.value = `暂时无法读取已授权目录（${opts.gateway_reason || '原因未知'}），当前只能使用默认共享文件夹。`
    } else if (opts.authorized_paths.length === 0) {
      optionsHint.value = '请授权存储目录，或直接使用默认共享文件夹。'
    }
  } catch (e: any) {
    workDirOptions.value = []
    optionsHint.value = e.response?.data?.detail || '读取候选目录失败，请点「刷新」重试。'
  } finally {
    optionsLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

/** 第一步的按钮：一步式直接提交，两步式先校验再进入第二步。 */
async function handlePrimaryAction() {
  if (!formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  errorMessage.value = ''
  if (needsWorkDir.value) {
    step.value = 1
    // 进入第二步时先拉一次：管理员可能在安装前就已经把目录共享给本应用了。
    if (workDirOptions.value.length === 0) void loadWorkDirOptions()
    return
  }
  await handleSubmit()
}

/** 打开飞牛的目录选择器完成授权。 */
async function handlePickDirectory() {
  // 只有在错配时才会走到这里：后端说需要选工作目录（PHOTOVAULT_REQUIRE_WORKDIR_SETUP
  // 打开了），但当前前端不是飞牛构建，SDK 根本没被打包。静默 return 会表现为「点了
  // 没反应」，所以明确说出来。此时仍可从选择框里选默认共享文件夹完成初始化。
  if (!__FNOS_BUILD__) {
    errorMessage.value =
      '当前构建不含飞牛目录授权能力，无法调起目录选择器。请使用下方的默认共享文件夹，或改用飞牛版本的安装包。'
    return
  }

  errorMessage.value = ''
  pickHint.value = ''
  picking.value = true

  try {
    const {
      FNOS_APP_NAME_FALLBACK,
      FNOS_AUTH_CALLBACK_PATH,
      guessHostOrigin,
      pickSharedFolder,
    } = await import('@/utils/fnos')

    const outcome = await pickSharedFolder({
      // 初始化阶段拿不到后端的 app_name（/fnos/env 需要管理员登录），只能用常量。
      // 它必须与 packaging/fnos/manifest 的 appname 一致，否则授权会挂到别的应用名下。
      appName: FNOS_APP_NAME_FALLBACK,
      hostOrigin: guessHostOrigin(),
      redirectUri: `${window.location.origin}${FNOS_AUTH_CALLBACK_PATH}`,
    })

    if (outcome.mode === 'bridge') {
      picking.value = false
      if (outcome.paths.length > 0) {
        // SDK 直接给了路径，重新拉一遍列表并把它选中，让「列表里的都是已授权目录」
        // 这个前提始终成立。
        pickHint.value = '授权已完成，已为你选中该目录。'
        await loadWorkDirOptions(outcome.paths[0])
      } else {
        errorMessage.value = outcome.msg
          ? `未选择目录：${outcome.msg}`
          : '未选择目录，请重试。'
      }
      return
    }

    // 路由授权：窗口已打开，结果由 message 事件带回，picking 在那里复位。
    pickHint.value = '已打开飞牛授权页，请在新窗口中选择目录并确认授权。'
  } catch (e) {
    picking.value = false
    const { toErrorMessage } = await import('@/utils/fnos')
    errorMessage.value = toErrorMessage(e)
  }
}

/** 提交账号（以及工作目录）。服务端原子完成，失败则什么都不留。 */
async function handleSubmit() {
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    const result = await initSetup({
      username: form.username,
      password: form.password,
      ...(needsWorkDir.value ? { work_dir: workDir.value.trim() } : {}),
    })

    resetSetupStatusCache()

    successMessage.value = result.adopted
      ? result.message
      : '初始化完成，即将跳转到登录页面...'

    setTimeout(
      () => {
        router.push({ name: 'Login' })
      },
      result.adopted ? 4000 : 1500
    )
  } catch (error: any) {
    errorMessage.value = error.response?.data?.detail || '初始化失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.setup-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f0f2f5;
}

.setup-card {
  width: 400px;
}

.setup-card--wide {
  width: 520px;
}

.setup-header {
  text-align: center;
}

.setup-header h1 {
  margin: 0;
  font-size: 28px;
  color: #303133;
}

.setup-header p {
  margin: 8px 0 0;
  color: #909399;
  font-size: 14px;
}

.setup-steps {
  margin-bottom: 20px;
}

.setup-alert {
  margin-bottom: 16px;
}

.setup-workdir-auth {
  width: 100%;
  margin-bottom: 12px;
}

.setup-workdir-hint {
  margin: 10px 0 0;
  color: #67c23a;
  font-size: 13px;
  line-height: 1.5;
}

.setup-workdir-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.setup-workdir-label {
  flex: none;
  color: #606266;
  font-size: 14px;
}

.setup-workdir-select {
  flex: 1;
  min-width: 0;
}

/* 下拉项里路径可能很长，标签靠右并保证不被挤掉 */
.setup-option-path {
  margin-right: 12px;
}

.setup-option-tag {
  float: right;
  color: #909399;
  font-size: 12px;
}

.setup-workdir-note {
  margin: 10px 0 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
}

.setup-workdir-previous {
  margin-top: 12px;
}

.setup-previous-path {
  margin: 0 0 4px;
  font-family: var(--el-font-family-monospace, monospace);
  word-break: break-all;
}

.setup-workdir-submit {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
</style>
