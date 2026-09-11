<!--
  初始化向导。

  两种形态，由后端 /setup/status 的 needs_work_dir 决定：

    needs_work_dir=false（Docker / 本地开发）
      一步：创建管理员账户。存储位置由配置决定，与本页无关。

    needs_work_dir=true（飞牛应用）
      两步：选择并授权工作目录 -> 填账号 -> 一次性提交。
      飞牛的照片只能放在平台授予过 ACL 的目录里，而授权只能在跑起来的应用界面里
      完成，所以服务端在选定之前刻意不建数据库。

  为什么目录在前：目录决定了后面还要不要问账号。选中的目录里如果已经有
  photovault.db（重装、或者重新授权回原来的文件夹），那就是一个现成的库，直接沿用
  即可，原有账号继续有效——此时再让用户建一个用不上的管理员纯属误导。

  这个判断在**点按钮之前**就有：/setup/work-dir-options 随候选列表一并返回
  library_paths，选中即知结论，按钮文字直接变成「完成设置」，点下去一步跳转。
  全新目录才会在点击时走一趟 /setup/work-dir/check（写探测），把「不可写／没授权」
  提前报出来。

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
        <el-step title="工作目录" />
        <el-step title="管理员账户" />
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

      <!-- 第一步：工作目录（仅飞牛） -->
      <div v-if="needsWorkDir" v-show="step === 0" class="setup-workdir">
        <el-alert type="info" :closable="false" show-icon class="setup-alert">
          <template #title>照片和数据库将存放在这里</template>
          飞牛只允许应用写入已授权的目录。请先完成授权，授权过的目录才会出现在下方
          列表中，然后从中选定一个容量充足的作为工作目录。
          如果所选目录里已经有 PhotoVault 数据，将直接沿用，无需再创建管理员账户。
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

        <!-- 选中的是现成的库：说清楚点下去会发生什么，按钮也随之变成「完成设置」 -->
        <el-alert
          v-if="adoptExisting"
          type="success"
          :closable="false"
          show-icon
          class="setup-workdir-adopt"
        >
          <template #title>该目录中已存在 PhotoVault 数据</template>
          将直接沿用其中的照片和数据库，无需创建管理员账户。点「完成设置」即可结束
          初始化，之后请使用原有的管理员账号登录。
        </el-alert>

        <!-- 选中的目录本身就是个现成的库时不再提「去找回」：上面那条已经说明白了 -->
        <el-alert
          v-if="previousPath && !adoptExisting"
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
          <el-button
            type="primary"
            size="large"
            :loading="checking || loading"
            :disabled="!workDir || finished"
            style="flex: 1"
            @click="handleWorkDirNext"
          >
            {{ adoptExisting ? '完成设置' : '下一步：创建管理员账户' }}
          </el-button>
        </div>
      </div>

      <!-- 第二步：管理员账户 -->
      <el-form
        v-show="step === accountStep"
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="0"
        @submit.prevent="handleAccountSubmit"
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
          <div class="setup-account-submit">
            <el-button
              v-if="needsWorkDir"
              size="large"
              :disabled="loading || finished"
              @click="step = 0"
            >
              上一步
            </el-button>
            <el-button
              type="primary"
              size="large"
              :loading="loading"
              :disabled="finished"
              style="flex: 1"
              native-type="submit"
            >
              {{ needsWorkDir ? '完成初始化' : '创建管理员账户' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <!-- 已确定的工作目录：账号这一步看不到选择框了，把结果留在视野里 -->
      <p v-if="needsWorkDir && step === accountStep" class="setup-workdir-note">
        工作目录：<span class="setup-chosen-path">{{ workDir }}</span>
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { checkWorkDir, getSetupStatus, getWorkDirOptions, initSetup } from '@/api/setup'
import { resetSetupStatusCache } from '@/router'
import type { FormInstance, FormRules } from 'element-plus'

const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const checking = ref(false)
const picking = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const pickHint = ref('')

/** 两步式：0 = 工作目录，1 = 管理员账户；一步式只有 0 = 管理员账户。 */
const step = ref(0)
const needsWorkDir = ref(false)
const workDir = ref('')

/** 账号那一步的序号，一步式下就是第 0 步。 */
const accountStep = computed(() => (needsWorkDir.value ? 1 : 0))

interface WorkDirOption {
  value: string
  label: string
  tag?: string
  /** 该目录里已经有可用的 PhotoVault 数据。 */
  hasLibrary?: boolean
}

const workDirOptions = ref<WorkDirOption[]>([])
const optionsLoading = ref(false)
const optionsHint = ref('')
const previousPath = ref('')

/**
 * 选定目录里已经有可用的库，这次初始化只是沿用它。
 *
 * 由候选列表随手带回的 library_paths 直接算出来，所以在用户点按钮**之前**就已经
 * 知道结论：按钮文字随之变成「完成设置」，点下去一步到位，不会先跑去要账号。
 * 为 true 时提交不带账号密码，服务端也不会创建账号——原有账号继续有效。
 */
const adoptExisting = computed(() => {
  const chosen = workDir.value.trim()
  if (!chosen) return false
  return workDirOptions.value.some((o) => o.value === chosen && o.hasLibrary)
})

/**
 * 初始化已经成功，正在跳转。
 *
 * 用来把按钮锁死：/setup/init 只能成功一次，成功后再点一下会拿到 403
 * 「System already initialized」，把一次好结果盖成一条错误提示。
 */
const finished = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

const headerHint = computed(() =>
  needsWorkDir.value
    ? '首次使用请先指定工作目录，再创建管理员账户'
    : '首次使用请创建管理员账户'
)

/**
 * 取出后端的错误说明。
 *
 * 422 的 detail 是 pydantic 的错误数组而不是字符串，直接塞进界面会显示成
 * "[object Object]"，所以这里统一拍平成一句话。
 */
function toDetail(error: any, fallback: string): string {
  const detail = error?.response?.data?.detail
  if (typeof detail === 'string' && detail) return detail
  if (Array.isArray(detail)) {
    const msgs = detail.map((d: any) => d?.msg).filter(Boolean)
    if (msgs.length > 0) return msgs.join('；')
  }
  return fallback
}

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

  // 目录现在是第一步，进页面就把候选列表拉出来：管理员可能在安装前就已经把目录
  // 共享给本应用了，那种情况下不用再点授权。
  if (needsWorkDir.value) void loadWorkDirOptions()

  if (needsWorkDir.value && __FNOS_BUILD__) {
    const { FNOS_AUTH_MESSAGE_TYPE, readAuthState, clearAuthState } = await import(
      '@/utils/fnos'
    )

    // 独立浏览器页面下走的是「路由授权」：新窗口完成后由回调页 postMessage 回来。
    // 回调结果里**不含**目录路径（要靠 /fnos/shared-folders 查，而那个接口需要管理员
    // 登录，初始化时还没有账号），所以这里只能重新拉一遍授权列表让用户挑。
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

    const hasLibrary = (p: string) => opts.library_paths.includes(p)
    const tagFor = (base: string, p: string) =>
      hasLibrary(p) ? `${base} · 已有数据` : base

    workDirOptions.value = [
      ...opts.authorized_paths.map((p) => ({
        value: p,
        label: p,
        tag: tagFor('已授权', p),
        hasLibrary: hasLibrary(p),
      })),
      {
        value: opts.default_path,
        label: opts.default_path,
        tag: tagFor('默认共享文件夹', opts.default_path),
        hasLibrary: hasLibrary(opts.default_path),
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
    optionsHint.value = toDetail(e, '读取候选目录失败，请点「刷新」重试。')
  } finally {
    optionsLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

/** 把某个候选目录标记为「已有数据」，让按钮文字和标签跟上服务端的判断。 */
function markHasLibrary(path: string) {
  const option = workDirOptions.value.find((o) => o.value === path)
  if (!option || option.hasLibrary) return
  option.hasLibrary = true
  option.tag = option.tag ? `${option.tag} · 已有数据` : '已有数据'
}

/**
 * 第一步（仅飞牛）的按钮。
 *
 * 目录里已经有库时这一步就是最后一步：直接提交完成初始化，不再问管理员账号，因为
 * 那个库里的账号才是有效的。此时不必再探一次目录——列表已经给出了结论，而
 * /setup/init 自己会做同样的校验。
 *
 * 是全新目录才去 /work-dir/check 走一遍写探测：把「目录不可写／没授权」提前报出来，
 * 用户此时还在目录选择器前面，换一个目录的成本最低。
 */
async function handleWorkDirNext() {
  const candidate = workDir.value.trim()
  if (!candidate || finished.value) return

  errorMessage.value = ''

  if (adoptExisting.value) {
    await handleSubmit(true)
    return
  }

  checking.value = true
  try {
    const result = await checkWorkDir(candidate)
    workDir.value = result.work_dir

    if (result.has_existing_library) {
      // 列表拉取之后目录里才出现的数据（比如刚从备份恢复回去）：以服务端此刻的判断
      // 为准，同样一步完成，而不是把用户送去建一个用不上的账号。
      markHasLibrary(result.work_dir)
      await handleSubmit(true)
      return
    }

    step.value = 1
  } catch (e: any) {
    errorMessage.value = toDetail(e, '无法使用该目录，请换一个目录后重试。')
  } finally {
    checking.value = false
  }
}

/** 最后一步：校验账号表单后提交。 */
async function handleAccountSubmit() {
  if (!formRef.value || finished.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  errorMessage.value = ''
  await handleSubmit(false)
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

/**
 * 提交工作目录（以及需要新建时的账号）。服务端原子完成，失败则什么都不留。
 *
 * @param adopt 沿用目录里已有的库。为 true 时不发账号密码，服务端也不会创建账号。
 */
async function handleSubmit(adopt: boolean) {
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    const result = await initSetup({
      // 沿用已有库时不发账号：那个库里已经有管理员，多建一个只会让人困惑。
      ...(adopt ? {} : { username: form.username, password: form.password }),
      ...(needsWorkDir.value ? { work_dir: workDir.value.trim() } : {}),
    })

    resetSetupStatusCache()

    // 锁住按钮：init 只能成功一次，跳转前再点一下只会换来一条 403 报错。
    finished.value = true

    successMessage.value = result.adopted
      ? result.message
      : '初始化完成，即将跳转到登录页面...'

    setTimeout(() => {
      router.push({ name: 'Login' })
    }, 1500)
  } catch (error: any) {
    errorMessage.value = toDetail(error, '初始化失败，请重试')
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

.setup-workdir-previous,
.setup-workdir-adopt {
  margin-top: 12px;
}

.setup-previous-path {
  margin: 0 0 4px;
  font-family: var(--el-font-family-monospace, monospace);
  word-break: break-all;
}

.setup-chosen-path {
  font-family: var(--el-font-family-monospace, monospace);
  word-break: break-all;
  color: #606266;
}

.setup-workdir-submit {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.setup-account-submit {
  display: flex;
  gap: 12px;
  width: 100%;
}
</style>
