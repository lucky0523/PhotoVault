<!--
  飞牛授权回调页 —— openAppAuth 的 redirectUri 落点。

  只在独立浏览器页面（isStandaloneWeb = true）的路由授权链路里用到：飞牛系统
  完成授权后跳到这里，把结果带在 URL 上。

  页面要做的事按文档只有两件：
    1. 用 parseAppAuthCallback 解析当前 URL 里的授权结果；
    2. 通知原应用页面刷新授权状态，然后关掉自己。

  两个细节值得记下来：

  - state 的校验放在原页面而不是这里。回调页是被 window.open 打开的辅助窗口，
    虽然规范规定它会拿到 opener 的 sessionStorage 副本，但 target='_self' 时
    根本没有第二个窗口，而"原页面重新加载后自己读 sessionStorage"这条路无论
    哪种 target 都成立。所以这里只负责如实转发，校验统一由测试页做。

  - postMessage 的第二个参数写成 window.location.origin 而不是 '*'：
    授权结果不该广播给任意页面。同时原页面也会校验 event.origin。

  本文件只在飞牛构建（__FNOS_BUILD__）里被打包。

  参考：https://developer.fnnas.com/api/calling/
-->
<template>
  <div class="fnos-callback">
    <el-result :icon="resultIcon" :title="title" :sub-title="subTitle">
      <template #extra>
        <div class="fnos-callback-detail">
          <pre v-if="detail" class="fnos-callback-json">{{ detail }}</pre>
          <p class="fnos-callback-tip">
            {{ notified ? '已通知原页面刷新授权状态。' : '没有找到打开本页的原页面，请回到原页面点「刷新已授权目录」。' }}
          </p>
          <el-button v-if="!closed" @click="closeSelf">关闭本页</el-button>
        </div>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FNOS_AUTH_MESSAGE_TYPE, parseAuthCallback, toErrorMessage } from '@/utils/fnos'
import type { AppAuthResult } from '@trimjs/web-app'

/** el-result 的 icon 取值 */
const resultIcon = ref<'success' | 'warning' | 'error' | 'info'>('info')
const title = ref('正在解析授权结果…')
const subTitle = ref('')
const detail = ref('')
const notified = ref(false)
const closed = ref(false)

/** 自动关闭前留出的时间，让使用者能看到解析结果。 */
const AUTO_CLOSE_DELAY_MS = 1500

function describe(result: AppAuthResult): void {
  detail.value = JSON.stringify(result, null, 2)

  if (result.status === 'success') {
    resultIcon.value = 'success'
    title.value = '授权成功'
    subTitle.value = result.path?.length
      ? `已授权目录：${result.path.join('、')}`
      : '系统未返回目录路径'
    return
  }

  if (result.error === 'access_denied') {
    resultIcon.value = 'error'
    title.value = '授权被拒绝'
    subTitle.value = '仅管理员可进行此操作（access_denied）'
    return
  }

  if (result.status === 'cancel') {
    resultIcon.value = 'warning'
    title.value = '已取消授权'
    subTitle.value = ''
    return
  }

  resultIcon.value = 'warning'
  title.value = '授权未完成'
  subTitle.value = `status=${result.status ?? '未知'}${result.error ? `，error=${result.error}` : ''}`
}

/** 通知原页面。拿不到 opener 不是错误，只是要让用户知道得手动刷新。 */
function notifyOpener(result: AppAuthResult): void {
  try {
    if (window.opener && !window.opener.closed) {
      window.opener.postMessage(
        { type: FNOS_AUTH_MESSAGE_TYPE, result },
        window.location.origin
      )
      notified.value = true
    }
  } catch {
    // 跨源或窗口已关闭，保持 notified = false，页面上会提示手动刷新
    notified.value = false
  }
}

function closeSelf(): void {
  // window.close() 只对脚本打开的窗口有效；target='_self' 或被当成新标签页打开时
  // 会静默失败，所以用 closed 记下来，把提示留在页面上而不是假装已关闭。
  window.close()
  closed.value = true
}

onMounted(async () => {
  let result: AppAuthResult
  try {
    result = await parseAuthCallback()
  } catch (e) {
    resultIcon.value = 'error'
    title.value = '解析授权回调失败'
    subTitle.value = toErrorMessage(e)
    return
  }

  describe(result)
  notifyOpener(result)

  if (notified.value) {
    setTimeout(closeSelf, AUTO_CLOSE_DELAY_MS)
  }
})
</script>

<style scoped>
.fnos-callback {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  box-sizing: border-box;
  background: var(--el-bg-color-page, #f5f7fa);
}

.fnos-callback-detail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.fnos-callback-json {
  margin: 0;
  padding: 10px 12px;
  max-width: 520px;
  max-height: 240px;
  overflow: auto;
  text-align: left;
  background: var(--el-fill-color-light);
  border-radius: var(--pv-radius, 8px);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.fnos-callback-tip {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
