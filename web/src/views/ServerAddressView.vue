<!--
  服务器地址 —— 设置分组下的管理员页面。

  配置手机 App 扫描「服务器二维码」时拿到的公网地址和局域网地址。这里填的是
  配置值，留空时服务端按回退规则自动补（当前访问地址、自动检测的局域网地址），
  二维码里实际用的地址以对话框显示为准。服务器二维码协议 v1，见
  protocol/README.md 与 protocol/server-qr-v1.json。

  前端校验（utils/serverAddress.ts）只用于即时提示，服务端的校验才是权威：
  绕过前端校验时，服务端 422 返回的字段错误会回填到对应输入框。
-->
<template>
  <div class="server-address-view pv-page">
    <PageHeader
      title="服务器地址"
      subtitle="手机 App 扫描服务器二维码时使用的地址"
      :icon="Link"
    />

    <div v-loading="loading" class="server-address-body">
      <el-alert
        v-if="loadError"
        type="error"
        :title="loadError"
        :closable="false"
        show-icon
      />

      <section v-else-if="loaded" class="server-address-panel pv-panel">
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="公网地址" :error="errors.public.text">
            <el-input
              v-model="form.public"
              placeholder="例如 https://photos.example.com"
              clearable
              maxlength="1024"
              @input="onFieldInput('public')"
            />
            <p class="field-hint pv-muted">
              手机不在家时使用，例如 Cloudflare Tunnel 绑定的域名。留空时，如果当前通过公网域名打开本页面，二维码会使用当前访问地址。如果当前用局域网主机名（例如 http://nas:8000）打开本页面，请务必填写公网地址。
            </p>
          </el-form-item>

          <el-form-item label="局域网地址" :error="errors.lan.text">
            <el-input
              v-model="form.lan"
              :placeholder="detectedLanAddress || '例如 http://192.168.1.10:8000'"
              clearable
              maxlength="1024"
              @input="onFieldInput('lan')"
            />
            <p class="field-hint pv-muted">
              手机在家时直连使用。留空时自动使用检测到的地址（当前检测到：{{ detectedLanAddress || '未检测到' }}）；如果当前用局域网地址打开本页面，则优先使用当前访问地址。使用 Docker 端口映射或反向代理时，请填写手机实际能访问的地址。
            </p>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
          </el-form-item>
        </el-form>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Link } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { getServerAddressSettings, updateServerAddressSettings } from '@/api/server'
import type { ServerAddressSettings } from '@/api/server'
import { addressErrorText, validateServerAddressPair } from '@/utils/serverAddress'
import type { AddressCheck, AddressErrorCode } from '@/utils/serverAddress'

type Field = 'public' | 'lan'

interface FieldError {
  code: AddressErrorCode | null
  /** el-form-item 的 :error；空串表示无错误 */
  text: string
}

const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const saving = ref(false)
const detectedLanAddress = ref<string | null>(null)

const form = reactive<Record<Field, string>>({ public: '', lan: '' })
const errors = reactive<Record<Field, FieldError>>({
  public: { code: null, text: '' },
  lan: { code: null, text: '' },
})

// 服务端 422 detail.fields 的键名 → 本页字段。
const SERVER_FIELD_KEYS: Record<Field, 'public_address' | 'lan_address_override'> = {
  public: 'public_address',
  lan: 'lan_address_override',
}

/**
 * 提取接口错误文案。在 ClientVersionsView 的同名函数基础上多一个对象分支：
 * 本功能的 422 detail 是 { code, message, fields }。
 */
function getErrorMessage(error: any, fallback: string): string {
  const detail = error?.response?.data?.detail
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    return detail
      .map((item) => {
        const field = Array.isArray(item?.loc) ? item.loc.at(-1) : ''
        return `${field ? `${field}: ` : ''}${item?.msg || '请求参数无效'}`
      })
      .join('；')
  }
  if (detail && typeof detail === 'object' && typeof detail.message === 'string' && detail.message) {
    return detail.message
  }
  return fallback
}

function applySettings(settings: ServerAddressSettings) {
  form.public = settings.public_address
  form.lan = settings.lan_address_override
  detectedLanAddress.value = settings.detected_lan_address
}

function setFieldError(field: Field, check: AddressCheck) {
  errors[field].code = check.error
  errors[field].text = check.error ? addressErrorText(check.error, field) : ''
}

// 输入时清掉本字段的错误；「与另一项相同」是两个字段一起标的，也一并清掉另一项。
function onFieldInput(field: Field) {
  errors[field].code = null
  errors[field].text = ''
  const other: Field = field === 'public' ? 'lan' : 'public'
  if (errors[other].code === 'SAME_AS_OTHER') {
    errors[other].code = null
    errors[other].text = ''
  }
}

async function loadSettings() {
  loading.value = true
  loadError.value = ''
  try {
    applySettings(await getServerAddressSettings())
    loaded.value = true
  } catch (error) {
    loadError.value = getErrorMessage(error, '获取服务器地址设置失败')
  } finally {
    loading.value = false
  }
}

async function onSave() {
  if (saving.value) return

  // 无论结果如何都覆盖两个字段的错误，这样也会清掉上一次服务端 422 留下的错误。
  const checks = validateServerAddressPair(form.public, form.lan)
  setFieldError('public', checks.public)
  setFieldError('lan', checks.lan)
  if (checks.public.error || checks.lan.error) return

  saving.value = true
  try {
    applySettings(
      await updateServerAddressSettings({
        public_address: form.public.trim(),
        lan_address_override: form.lan.trim(),
      })
    )
    ElMessage.success('已保存')
  } catch (error: any) {
    const fields = error?.response?.status === 422 ? error.response.data?.detail?.fields : null
    if (fields && typeof fields === 'object') {
      for (const field of ['public', 'lan'] as const) {
        const item = fields[SERVER_FIELD_KEYS[field]]
        if (item && typeof item.message === 'string') {
          errors[field].code = (item.code as AddressErrorCode) ?? null
          errors[field].text = item.message
        }
      }
    } else {
      ElMessage.error(getErrorMessage(error, '保存失败'))
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.server-address-body {
  margin-top: var(--pv-page-gutter);
  min-height: 200px;
}

.server-address-panel {
  padding: 16px;
  max-width: 640px;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
