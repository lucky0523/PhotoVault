<!--
  飞牛目录授权测试 —— 只存在于飞牛（fnOS）应用包里的诊断页面。

  为什么需要它：应用共享授权链路横跨四个互不相同的运行面，任何一环没配好，
  表现都是「点了没反应」，光看日志分不清是哪一环：
    1. 宿主环境    —— manifest 是否声明 micro_app=true，页面是否真的在飞牛宿主
                      的 iframe 里（SDK 用 window.parent === window 判断）
    2. 前端 JS SDK —— pickSharedFile / authorizeSharedFile，且只有管理员能调用
    3. 应用包声明  —— config/resource 的 api-scope 是否包含 trim.file.sharedAccess
    4. 后端开放 API—— Unix Socket 与 TRIM_API_TOKEN 是否就绪

  所以这个页面把四层的状态和返回值全部摊开显示，包括原始 JSON，而不是只给
  成功/失败。定位问题靠的是「哪一层的哪个字段不对」。

  路由与侧边栏入口都在 __FNOS_BUILD__ 为真时才注册，普通构建里本文件不会被
  打包（见 router/index.ts 与 vite.config.ts 的说明）。

  参考：https://developer.fnnas.com/api/authorization/shared-access/
-->
<template>
  <div class="fnos-view pv-page">
    <PageHeader
      title="飞牛目录授权测试"
      subtitle="应用共享授权路径（trim.file.sharedAccess）"
      :icon="FolderOpened"
    >
      <template #extra>
        <el-button :loading="envLoading" @click="loadEnv">
          <el-icon><Refresh /></el-icon>
          重新探测环境
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      class="fnos-alert"
      type="warning"
      :closable="false"
      show-icon
      title="应用共享授权由飞牛管理员操作"
    >
      <template #default>
        普通飞牛用户调用会失败：宿主内直调返回
        <code>code: 1 / msg: "仅管理员可进行此操作"</code>，路由授权返回
        <code>status: "error" / error: "access_denied"</code>。
        该能力只支持目录授权，不支持文件授权。
      </template>
    </el-alert>

    <!-- ==================== 1. 运行环境 ==================== -->
    <section class="fnos-section pv-panel">
      <h4 class="pv-section-title">前端宿主环境</h4>

      <el-alert
        v-if="hostEnv && !hostEnv.ready"
        class="fnos-alert"
        type="error"
        :closable="false"
        show-icon
        :title="`JS SDK 初始化失败：${hostEnv.error}`"
      >
        <template #default>
          常见原因：manifest 未声明 <code>micro_app=true</code>（页面不会按微应用环境
          加载）；或当前页面不在飞牛宿主里。
        </template>
      </el-alert>

      <el-descriptions v-loading="envLoading" :column="2" border>
        <el-descriptions-item label="SDK 初始化">
          <el-tag v-if="!hostEnv" size="small" type="info">未探测</el-tag>
          <el-tag v-else-if="hostEnv.ready" size="small" type="success">已完成</el-tag>
          <el-tag v-else size="small" type="danger">失败</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="是否在 iframe 中">
          <el-tag size="small" :type="hostEnv?.inIframe ? 'success' : 'warning'">
            {{ hostEnv ? String(hostEnv.inIframe) : '-' }}
          </el-tag>
          <span class="fnos-hint pv-muted">飞牛桌面以 iframe 打开本应用</span>
        </el-descriptions-item>
        <el-descriptions-item label="isWeb">
          <el-tag size="small" :type="hostEnv?.isWeb ? 'success' : 'warning'">
            {{ hostEnv ? String(hostEnv.isWeb) : '-' }}
          </el-tag>
          <span class="fnos-hint pv-muted">移动端 App 内嵌页为 false</span>
        </el-descriptions-item>
        <el-descriptions-item label="isStandaloneWeb">
          <el-tag size="small" :type="hostEnv?.isStandaloneWeb ? 'warning' : 'success'">
            {{ hostEnv ? String(hostEnv.isStandaloneWeb) : '-' }}
          </el-tag>
          <span class="fnos-hint pv-muted">
            true 表示没有运行在宿主环境中，授权要走 openAppAuth 路由
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="宿主主题">
          {{ hostEnv?.platformConfig?.theme ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="宿主语言">
          {{ hostEnv?.platformConfig?.language ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="系统版本">
          {{ hostEnv?.platformConfig?.systemVersion ?? '-' }}
          <span class="fnos-hint pv-muted">本能力要求 ≥ 1.2.0401</span>
        </el-descriptions-item>
        <el-descriptions-item label="宿主 App 版本">
          {{ hostEnv?.platformConfig?.appVersion ?? '-' }}
          <span class="fnos-hint pv-muted">本能力要求 ≥ 1.34.0</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前页面地址" :span="2">
          <span class="fnos-path">{{ currentHref }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="宿主 origin（referrer）" :span="2">
          <span v-if="hostEnv?.hostOrigin" class="fnos-path">{{ hostEnv.hostOrigin }}</span>
          <span v-else class="pv-muted">
            取不到（不在 iframe 中，或浏览器没有带 referrer）
          </span>
          <span class="fnos-hint pv-muted">路由授权拼 /app-auth/* 时需要它</span>
        </el-descriptions-item>
      </el-descriptions>
    </section>

    <!-- ==================== 2. 后端开放 API 环境 ==================== -->
    <section class="fnos-section pv-panel">
      <h4 class="pv-section-title">后端开放 API 环境</h4>

      <el-alert
        v-if="backendEnv && !backendEnv.available"
        class="fnos-alert"
        type="error"
        :closable="false"
        show-icon
        :title="`后端无法调用开放 API：${backendEnv.reason}`"
      />

      <el-descriptions v-loading="envLoading" :column="2" border>
        <el-descriptions-item label="可用性">
          <el-tag v-if="!backendEnv" size="small" type="info">未探测</el-tag>
          <el-tag v-else-if="backendEnv.available" size="small" type="success">可用</el-tag>
          <el-tag v-else size="small" type="danger">不可用</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="appName">
          <span class="fnos-path">{{ appName }}</span>
          <span class="fnos-hint pv-muted">须与 manifest 的 appname 一致</span>
        </el-descriptions-item>
        <el-descriptions-item label="Unix Socket" :span="2">
          <span class="fnos-path">{{ backendEnv?.socket_path ?? '-' }}</span>
          <el-tag
            v-if="backendEnv"
            class="fnos-inline-tag"
            size="small"
            :type="backendEnv.socket_exists ? 'success' : 'danger'"
          >
            {{ backendEnv.socket_exists ? '存在' : '不存在' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="TRIM_API_TOKEN" :span="2">
          <el-tag
            v-if="backendEnv"
            size="small"
            :type="backendEnv.token_present ? 'success' : 'danger'"
          >
            {{ backendEnv.token_present ? '已注入' : '缺失' }}
          </el-tag>
          <span v-else>-</span>
          <span class="fnos-hint pv-muted">
            由系统在调用 cmd/main 时写入环境变量，服务端进程继承；只报告有无，不显示内容
          </span>
        </el-descriptions-item>
      </el-descriptions>
    </section>

    <!-- ==================== 3. 申请授权 ==================== -->
    <section class="fnos-section pv-panel">
      <h4 class="pv-section-title">向管理员申请授权</h4>

      <el-form label-width="120px" label-position="left">
        <el-form-item label="侧边栏分组">
          <el-select
            v-model="sidebarGroups"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="默认 myFiles / otherShare / favorites"
            class="fnos-control"
          >
            <el-option
              v-for="group in SIDEBAR_GROUP_OPTIONS"
              :key="group.value"
              :label="`${group.label}（${group.value}）`"
              :value="group.value"
            />
          </el-select>
          <div class="fnos-hint pv-muted">控制选择器左侧可见分组与展示顺序</div>
        </el-form-item>

        <el-form-item label="pickSharedFile">
          <el-button
            type="primary"
            :loading="picking"
            :disabled="!hostEnv?.ready"
            @click="handlePick"
          >
            打开目录选择框
          </el-button>
          <div class="fnos-hint pv-muted">
            让管理员选择要授权给本应用的目录。
            {{
              hostEnv?.isStandaloneWeb
                ? '当前是独立浏览器页面，将通过 openAppAuth 打开授权页。'
                : '当前在宿主环境内，直接调用 SDK。'
            }}
          </div>
        </el-form-item>

        <el-form-item label="authorizeSharedFile">
          <div class="fnos-row">
            <el-input
              v-model="authorizePath"
              placeholder="/vol1/1000/data/shared"
              class="fnos-control"
              clearable
            />
            <el-button
              :loading="authorizing"
              :disabled="!hostEnv?.ready || !authorizePath.trim()"
              @click="handleAuthorize"
            >
              就该目录重新申请
            </el-button>
          </div>
          <div class="fnos-hint pv-muted">
            用于「应用已知道要访问哪个目录，但尚未授权或授权被移除」的场景。
          </div>
        </el-form-item>

        <!-- 路由授权参数：只有独立浏览器页面才会用到，宿主内直调不经过 redirectUri -->
        <template v-if="hostEnv?.isStandaloneWeb">
          <el-divider content-position="left">
            <span class="pv-muted">路由授权参数（isStandaloneWeb = true 时生效）</span>
          </el-divider>

          <el-alert type="info" :closable="false" show-icon class="fnos-alert">
            <template #title>为什么这里要单独填「飞牛系统地址」</template>
            <template #default>
              <code>/app-auth/*</code> 是飞牛系统 UI 的路由，不是本应用的路由，必须落在飞牛的
              origin 上。而 SDK 在独立页面下按
              <code>window.location.origin</code> 推导基址 —— PhotoVault 挂在自己的端口
              （<code>{{ currentOrigin }}</code>）而不是统一网关的
              <code>/app/{{ appName }}/</code> 下，推出来就是本应用自己，授权页会被前端路由
              兜成空白页。所以这里由你指定飞牛地址，URL 由本页自行拼接。
            </template>
          </el-alert>

          <el-form-item label="飞牛系统地址">
            <div class="fnos-row">
              <el-input
                v-model="hostOrigin"
                placeholder="http://10.211.55.5:5666"
                class="fnos-control"
                clearable
              />
              <el-button @click="hostOrigin = guessHostOrigin()">重新推测</el-button>
            </div>
            <div class="fnos-hint pv-muted">
              飞牛 Web 管理界面的地址，默认端口 5666。
              {{
                hostEnv?.hostOrigin
                  ? '（本次已从宿主 referrer 取到准确值）'
                  : '若曾在飞牛桌面里打开过本应用，这里会自动填上记住的地址。'
              }}
            </div>
          </el-form-item>

          <el-form-item label="redirectUri">
            <el-input v-model="redirectUri" class="fnos-control" clearable />
            <div class="fnos-hint pv-muted">
              授权完成后系统跳转到这里。默认填当前源下的回调路由；如果通过统一网关访问，
              应改成同域路径（例如
              <code>/app/{{ appName }}{{ FNOS_AUTH_CALLBACK_PATH }}</code>），
              这样回调页与原页面同源，才能用 postMessage 通知原页面。
            </div>
          </el-form-item>

          <el-form-item label="打开方式">
            <el-radio-group v-model="authTarget">
              <el-radio value="_blank">_blank（新窗口，不打断当前页）</el-radio>
              <el-radio value="_self">_self（跳转当前页，回调链路更稳）</el-radio>
            </el-radio-group>
            <div class="fnos-hint pv-muted">
              移动端或平板浏览器里 _blank 可能拿不到 window.opener，回调页通知不到原页面，
              此时用下面的「刷新已授权目录」手动同步。
            </div>
          </el-form-item>
        </template>
      </el-form>
    </section>

    <!-- ==================== 4. 已授权目录 ==================== -->
    <section class="fnos-section pv-panel">
      <h4 class="pv-section-title">
        已授权目录
        <span class="fnos-hint pv-muted">
          后端 trim.file.getSharedAccessibleFolders，共 {{ sharedFolders.length }} 项
        </span>
      </h4>

      <div class="fnos-toolbar">
        <el-button
          :loading="foldersLoading"
          :disabled="!backendEnv?.available"
          @click="loadSharedFolders"
        >
          <el-icon><Refresh /></el-icon>
          刷新已授权目录
        </el-button>
      </div>

      <el-alert
        v-if="foldersError"
        class="fnos-alert"
        type="error"
        :closable="false"
        show-icon
        :title="foldersError"
      />

      <el-table v-loading="foldersLoading" :data="sharedFolders" border>
        <el-table-column type="index" label="#" width="60" />
        <el-table-column label="授权目录">
          <template #default="{ row }">
            <span class="fnos-path">{{ row }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="authorizePath = row">填入上方</el-button>
            <el-popconfirm
              :title="`删除该目录的授权？${row}`"
              confirm-button-text="删除"
              cancel-button-text="取消"
              width="280"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button link type="danger">删除授权</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <span class="pv-muted">
            {{
              backendEnv?.available
                ? '暂无授权目录。用上面的 pickSharedFile 让管理员授权一个。'
                : '后端开放 API 不可用，无法查询。'
            }}
          </span>
        </template>
      </el-table>
    </section>

    <!-- ==================== 5. 调用记录 ==================== -->
    <section class="fnos-section pv-panel">
      <h4 class="pv-section-title">
        调用记录
        <span class="fnos-hint pv-muted">原始返回值，按时间倒序</span>
      </h4>

      <div class="fnos-toolbar">
        <el-button :disabled="logs.length === 0" @click="logs = []">清空</el-button>
      </div>

      <el-empty v-if="logs.length === 0" description="还没有调用记录" :image-size="72" />
      <div v-else class="fnos-logs">
        <div v-for="entry in logs" :key="entry.id" class="fnos-log">
          <div class="fnos-log-head">
            <el-tag size="small" :type="entry.ok ? 'success' : 'danger'">
              {{ entry.ok ? 'OK' : 'FAIL' }}
            </el-tag>
            <span class="fnos-log-title">{{ entry.title }}</span>
            <span class="fnos-log-time pv-muted">{{ entry.time }}</span>
          </div>
          <pre class="fnos-log-body">{{ entry.detail }}</pre>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { FolderOpened, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  deleteFnosSharedFolder,
  getFnosEnv,
  getFnosSharedFolders,
  type FnosEnvInfo,
} from '@/api/fnos'
import {
  authorizeSharedFolder,
  clearAuthState,
  DEFAULT_SIDEBAR_GROUPS,
  FNOS_APP_NAME_FALLBACK,
  FNOS_AUTH_CALLBACK_PATH,
  FNOS_AUTH_MESSAGE_TYPE,
  guessHostOrigin,
  pickSharedFolder,
  probeHostEnv,
  readAuthState,
  rememberHostOrigin,
  toErrorMessage,
  type FnosHostEnv,
  type SharedAuthOptions,
  type SharedAuthOutcome,
} from '@/utils/fnos'
import type { SidebarGroup } from '@fn/micro-app-postmate'
import type { AppAuthResult } from '@trimjs/web-app'

const SIDEBAR_GROUP_OPTIONS: Array<{ value: SidebarGroup; label: string }> = [
  { value: 'myFiles', label: '我的文件' },
  { value: 'otherShare', label: '他人共享' },
  { value: 'external', label: '外接存储' },
  { value: 'remote', label: '远程挂载' },
  { value: 'favorites', label: '我的收藏' },
  { value: 'team', label: '团队空间' },
]

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

const envLoading = ref(false)
const hostEnv = ref<FnosHostEnv | null>(null)
const backendEnv = ref<FnosEnvInfo | null>(null)

const picking = ref(false)
const authorizing = ref(false)
const authorizePath = ref('')
const sidebarGroups = ref<SidebarGroup[]>([...DEFAULT_SIDEBAR_GROUPS])
const authTarget = ref<'_blank' | '_self'>('_blank')
const redirectUri = ref(`${window.location.origin}${FNOS_AUTH_CALLBACK_PATH}`)
const hostOrigin = ref(guessHostOrigin())

const foldersLoading = ref(false)
const foldersError = ref('')
const sharedFolders = ref<string[]>([])

const currentHref = window.location.href
const currentOrigin = window.location.origin

/** 后端拿到的 appName 是运行时真值，探测失败时才退回硬编码常量。 */
const appName = computed(() => backendEnv.value?.app_name || FNOS_APP_NAME_FALLBACK)

interface LogEntry {
  id: number
  time: string
  title: string
  ok: boolean
  detail: string
}

const logs = ref<LogEntry[]>([])
let logSeq = 0

function log(title: string, ok: boolean, detail: unknown): void {
  logs.value.unshift({
    id: ++logSeq,
    time: new Date().toLocaleTimeString(),
    title,
    ok,
    detail: typeof detail === 'string' ? detail : safeStringify(detail),
  })
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

// ---------------------------------------------------------------------------
// 环境探测
// ---------------------------------------------------------------------------

async function loadEnv(): Promise<void> {
  envLoading.value = true
  try {
    // 两侧互不依赖，并行探测；用 allSettled 保证一侧失败不影响另一侧展示。
    const [host, backend] = await Promise.allSettled([probeHostEnv(), getFnosEnv()])

    if (host.status === 'fulfilled') {
      hostEnv.value = host.value
      log('探测前端宿主环境', host.value.ready, host.value)
    } else {
      hostEnv.value = null
      log('探测前端宿主环境', false, toErrorMessage(host.reason))
    }

    if (backend.status === 'fulfilled') {
      backendEnv.value = backend.value
      log('GET /fnos/env', backend.value.available, backend.value)
    } else {
      backendEnv.value = null
      log('GET /fnos/env', false, toErrorMessage(backend.reason))
    }

    if (backendEnv.value?.available) {
      await loadSharedFolders()
    }
  } finally {
    envLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 授权
// ---------------------------------------------------------------------------

function authOptions(): SharedAuthOptions {
  const origin = hostOrigin.value.trim()
  // 手填/改过的地址记下来，下次打开不用重新填。
  rememberHostOrigin(origin)

  return {
    appName: appName.value,
    hostOrigin: origin,
    redirectUri: redirectUri.value.trim(),
    // 这里传的是 ref 的值（reactive Proxy）；utils/fnos.ts 会在进 SDK 前转成纯数据，
    // 否则 postMessage 的结构化克隆会因为 Proxy 而报 could not be cloned。
    sidebarGroups: sidebarGroups.value.length > 0 ? sidebarGroups.value : undefined,
    target: authTarget.value,
  }
}

/**
 * 授权成功后统一处理：宿主内直调能立刻拿到结果，所以顺带刷新后端列表，
 * 用来验证「授权确实落到了应用名下」而不只是选择器返回了路径。
 */
async function afterAuth(label: string, outcome: SharedAuthOutcome): Promise<void> {
  const ok = outcome.mode === 'route' || outcome.code === 0
  log(label, ok, outcome.raw)

  if (outcome.mode === 'route') {
    ElMessage.info('已打开授权页，完成后回到本页；若没有自动刷新，请点「刷新已授权目录」')
    return
  }

  if (!ok) {
    ElMessage.error(outcome.msg || '授权失败')
    return
  }

  if (outcome.paths.length > 0) {
    ElMessage.success(`本次授权目录：${outcome.paths.join('、')}`)
  } else {
    ElMessage.success('调用成功，但未返回目录（可能是管理员取消了选择）')
  }
  await loadSharedFolders()
}

async function handlePick(): Promise<void> {
  picking.value = true
  try {
    await afterAuth('pickSharedFile', await pickSharedFolder(authOptions()))
  } catch (e) {
    log('pickSharedFile', false, toErrorMessage(e))
    ElMessage.error(toErrorMessage(e))
  } finally {
    picking.value = false
  }
}

async function handleAuthorize(): Promise<void> {
  const path = authorizePath.value.trim()
  if (!path) return

  authorizing.value = true
  try {
    await afterAuth(
      `authorizeSharedFile(${path})`,
      await authorizeSharedFolder(path, authOptions())
    )
  } catch (e) {
    log(`authorizeSharedFile(${path})`, false, toErrorMessage(e))
    ElMessage.error(toErrorMessage(e))
  } finally {
    authorizing.value = false
  }
}

// ---------------------------------------------------------------------------
// 已授权目录
// ---------------------------------------------------------------------------

async function loadSharedFolders(): Promise<void> {
  foldersLoading.value = true
  foldersError.value = ''
  try {
    const result = await getFnosSharedFolders()
    sharedFolders.value = result.paths ?? []
    log('GET /fnos/shared-folders', true, result)
  } catch (e) {
    sharedFolders.value = []
    foldersError.value = extractApiError(e)
    log('GET /fnos/shared-folders', false, foldersError.value)
  } finally {
    foldersLoading.value = false
  }
}

async function handleDelete(path: string): Promise<void> {
  try {
    const result = await deleteFnosSharedFolder(path)
    log(`DELETE /fnos/shared-folders?path=${path}`, result.suc, result)
    if (result.suc) {
      ElMessage.success('已删除授权')
    } else {
      ElMessage.warning('接口返回 suc=false')
    }
  } catch (e) {
    const msg = extractApiError(e)
    log(`DELETE /fnos/shared-folders?path=${path}`, false, msg)
    ElMessage.error(msg)
  } finally {
    await loadSharedFolders()
  }
}

/** axios 错误里真正有用的是后端的 detail，直接展示 message 会只看到状态码。 */
function extractApiError(e: unknown): string {
  const detail = (e as { response?: { data?: { detail?: unknown } } })?.response?.data?.detail
  if (typeof detail === 'string' && detail) return detail
  if (detail) return safeStringify(detail)
  return toErrorMessage(e)
}

// ---------------------------------------------------------------------------
// 路由授权回调
// ---------------------------------------------------------------------------

/**
 * 回调页用 postMessage 把结果送回本页。
 *
 * 两道校验都不能省：
 *   - event.origin 必须同源，否则任何页面都能伪造授权结果；
 *   - state 必须与本次请求时保存的一致，否则可能是上一次遗留的回调。
 */
function handleAuthMessage(event: MessageEvent): void {
  if (event.origin !== window.location.origin) return

  const data = event.data as { type?: string; result?: AppAuthResult } | null
  if (data?.type !== FNOS_AUTH_MESSAGE_TYPE) return

  const result = data.result ?? {}
  const expected = readAuthState()

  if (expected && result.state !== expected) {
    log('授权回调（state 不匹配，已忽略）', false, { expected, received: result })
    return
  }
  clearAuthState()

  const ok = result.status === 'success'
  log(`授权回调 ${result.method ?? ''}`.trim(), ok, result)

  if (ok) {
    ElMessage.success('授权页返回成功')
  } else if (result.error === 'access_denied') {
    ElMessage.error('授权被拒绝：仅管理员可进行此操作（access_denied）')
  } else {
    ElMessage.warning(`授权未完成：status=${result.status ?? '未知'}`)
  }

  void loadSharedFolders()
}

onMounted(() => {
  window.addEventListener('message', handleAuthMessage)
  void loadEnv()
})

onBeforeUnmount(() => {
  window.removeEventListener('message', handleAuthMessage)
})
</script>

<style scoped>
.fnos-section {
  padding: 16px;
  margin-bottom: var(--pv-page-gutter);
}

.fnos-section:last-child {
  margin-bottom: 0;
}

.fnos-alert {
  margin-bottom: 12px;
}

.fnos-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.fnos-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.fnos-control {
  max-width: 520px;
  width: 100%;
}

/* 说明文字紧跟在控件/字段值之后，独占一行时靠 display:block 换行。
   放在 el-descriptions 单元格里时是行内元素，所以这里不写 display。 */
.fnos-hint {
  font-size: 12px;
  margin-left: 8px;
  font-weight: normal;
}

.fnos-row + .fnos-hint,
.el-form-item .fnos-hint {
  display: block;
  margin-left: 0;
  margin-top: 4px;
  line-height: 1.5;
}

.fnos-inline-tag {
  margin-left: 8px;
}

/* 绝对路径必须完整可见：截断后没法判断是不是授权目录写错了。 */
.fnos-path {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.fnos-logs {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fnos-log {
  border: 1px solid var(--pv-divider-color);
  border-radius: var(--pv-radius);
  overflow: hidden;
}

.fnos-log-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--el-fill-color-light);
}

.fnos-log-title {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}

.fnos-log-time {
  margin-left: auto;
  font-size: 12px;
  flex-shrink: 0;
}

.fnos-log-body {
  margin: 0;
  padding: 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}

code {
  padding: 1px 4px;
  background: var(--el-fill-color);
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
</style>
