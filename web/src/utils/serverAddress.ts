/**
 * 服务器地址的校验与规范化（Web 端即时提示用）。
 *
 * 本文件是服务端 `server/app/core/server_address.py::validate_address`（以及
 * `validate_address_pair`）的逐步镜像，步骤编号与服务端注释一一对应。改动时两边
 * 同步，并用共享向量 `protocol/server-qr-v1.json` 的 address_validation /
 * server_web_only / address_pairs 三组对照。服务器二维码协议 v1，见
 * protocol/README.md 与 protocol/server-qr-v1.json。
 *
 * 服务端的校验才是权威，这里只用于在保存前给出即时提示。
 *
 * 约定：
 * - 不 import 任何模块，可单独编译（向量对照脚本依赖这一点）。
 * - 不用 `new URL()`：WHATWG URL 会把 `1.2.3` 补全成 IPv4、把 `01.2.3.4` 按八进制
 *   解释、还会转换 IDN，和服务端规则不一致。IPv6 的展开与 RFC 5952 压缩都手写。
 * - 正则一律整串匹配 `/^…$/`，不加 `m` 标志；唯一例外是第 4 步端口预检，它本来
 *   就是前缀匹配 `/^…/`。数字只用 `[0-9]`，只有通过 `[0-9]` 匹配之后才转数字。
 */

export type AddressErrorCode =
  | 'TOO_LONG'
  | 'BAD_SCHEME'
  | 'INVALID'
  | 'BAD_PORT'
  | 'HAS_EXTRA'
  | 'SAME_AS_OTHER'

/**
 * 单个地址的校验结果。输入为空（表示清除）时 canonical 与 error 都是 null；
 * 校验失败时 canonical 为 null、error 为错误码。
 */
export interface AddressCheck {
  canonical: string | null
  error: AddressErrorCode | null
}

const MAX_ADDRESS_LENGTH = 255

const DEFAULT_PORTS: Record<string, number> = { http: 80, https: 443 }

// 第 4 步端口预检：前缀匹配（只锚定开头），语义与修正后的 Android PORT_REGEX 一致，
// 但数字写成 [0-9]。
const PORT_PRECHECK = /^[A-Za-z][A-Za-z0-9+.-]*:\/\/(\[[^\]]+\]|[^/:?#@\[]+):([0-9]+)/
const AUTHORITY_END = /[/?#\\]/
const NON_PRINTABLE_ASCII = /[^\x21-\x7e]/
const PORT_DIGITS = /^[0-9]{1,5}$/
const IPV6_CHARS = /^[0-9A-Fa-f:]*$/
const HEXTET = /^[0-9A-Fa-f]{1,4}$/
const DIGITS_AND_DOTS = /^[0-9.]+$/
const IPV4_OCTET = /^(?:0|[1-9][0-9]{0,2})$/
const HOST_LABEL = /^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$/

const ERROR_TEXT: Record<Exclude<AddressErrorCode, 'SAME_AS_OTHER'>, string> = {
  TOO_LONG: '地址过长（最多 255 个字符）',
  BAD_SCHEME: '仅支持 http:// 或 https://',
  INVALID: '地址格式不正确',
  BAD_PORT: '端口须在 1–65535 之间',
  HAS_EXTRA: '地址只能包含协议、主机和端口',
}

/** 错误码对应的中文文案，与服务端 422 和 Android values-zh 措辞相同。 */
export function addressErrorText(code: AddressErrorCode, field: 'public' | 'lan'): string {
  if (code === 'SAME_AS_OTHER') {
    return field === 'public' ? '与局域网地址相同' : '与公网地址相同'
  }
  return ERROR_TEXT[code]
}

function fail(error: AddressErrorCode): AddressCheck {
  return { canonical: null, error }
}

/**
 * 按 Python `ipaddress.IPv6Address` 的规则把不带方括号的 IPv6 文本解析成 8 个 16 位
 * 分组；不合法时返回 null。调用前已保证只含 [0-9A-Fa-f:]，所以没有 IPv4 尾部和 zone id。
 */
function parseIpv6Groups(text: string): number[] | null {
  const parts = text.split(':')
  // 最少 3 段（例如 "::"），最多 9 段（8 组加一个 "::" 带来的空段）。
  if (parts.length < 3 || parts.length > 9) return null

  // 至多一个 "::"：只检查首尾以外的空段。
  let skipIndex = -1
  for (let i = 1; i < parts.length - 1; i++) {
    if (parts[i] === '') {
      if (skipIndex !== -1) return null
      skipIndex = i
    }
  }

  let partsHi: number
  let partsLo: number
  let partsSkipped: number
  if (skipIndex !== -1) {
    partsHi = skipIndex
    partsLo = parts.length - skipIndex - 1
    // 开头的 ":" 只允许作为 "::" 的一部分，结尾同理。
    if (parts[0] === '') {
      partsHi -= 1
      if (partsHi !== 0) return null
    }
    if (parts[parts.length - 1] === '') {
      partsLo -= 1
      if (partsLo !== 0) return null
    }
    partsSkipped = 8 - (partsHi + partsLo)
    if (partsSkipped < 1) return null
  } else {
    if (parts.length !== 8) return null
    partsHi = parts.length
    partsLo = 0
    partsSkipped = 0
  }

  const groups: number[] = []
  for (let i = 0; i < partsHi; i++) {
    if (!HEXTET.test(parts[i])) return null
    groups.push(parseInt(parts[i], 16))
  }
  for (let i = 0; i < partsSkipped; i++) groups.push(0)
  for (let i = parts.length - partsLo; i < parts.length; i++) {
    if (!HEXTET.test(parts[i])) return null
    groups.push(parseInt(parts[i], 16))
  }
  return groups
}

/** RFC 5952：小写、去前导零，把最长的（至少 2 组）连续全零段压成 "::"，等长取第一段。 */
function compressIpv6(groups: number[]): string {
  let bestStart = -1
  let bestLen = 0
  let curStart = -1
  let curLen = 0
  for (let i = 0; i < groups.length; i++) {
    if (groups[i] === 0) {
      if (curStart === -1) curStart = i
      curLen += 1
      if (curLen > bestLen) {
        bestLen = curLen
        bestStart = curStart
      }
    } else {
      curStart = -1
      curLen = 0
    }
  }
  const hex = groups.map((g) => g.toString(16))
  if (bestLen > 1) {
    const head = hex.slice(0, bestStart).join(':')
    const tail = hex.slice(bestStart + bestLen).join(':')
    return `${head}::${tail}`
  }
  return hex.join(':')
}

/** 方括号内 IPv6 文本的 RFC 5952 形式；不可接受时返回 null。 */
function canonicalIpv6(text: string): string | null {
  // 同时排除 zone id（%）和点分 IPv4 尾部。
  if (!IPV6_CHARS.test(text)) return null
  const groups = parseIpv6Groups(text)
  if (groups === null) return null
  // IPv4 映射地址 ::ffff:0:0/96。
  if (groups.slice(0, 5).every((g) => g === 0) && groups[5] === 0xffff) return null
  return compressIpv6(groups)
}

/** 校验并规范化一个服务器地址（设计 §3.1 第 1–13 步）。 */
export function validateServerAddress(raw: string | null | undefined): AddressCheck {
  // 1. 为空表示清除。
  if (raw === null || raw === undefined || raw.trim() === '') {
    return { canonical: null, error: null }
  }

  // 2. 去首尾空白，限制原始长度。
  const t = raw.trim()
  if (t.length > MAX_ADDRESS_LENGTH) return fail('TOO_LONG')

  // 3. 协议。
  let scheme: string
  let rest: string
  const schemeEnd = t.indexOf('://')
  if (schemeEnd !== -1) {
    scheme = t.slice(0, schemeEnd).toLowerCase()
    if (!(scheme === 'http' || scheme === 'https')) return fail('BAD_SCHEME')
    rest = t.slice(schemeEnd + 3)
  } else {
    scheme = 'http'
    rest = t
  }

  // 4. 端口预检（仅前缀匹配）。
  const pre = PORT_PRECHECK.exec(`${scheme}://${rest}`)
  if (pre !== null) {
    const digits = pre[2]
    if (digits.length > 5) return fail('BAD_PORT')
    const value = Number(digits)
    if (value < 1 || value > 65535) return fail('BAD_PORT')
  }

  // 5. 把 authority 与路径 / 查询 / 片段 / 反斜杠分开。
  const end = rest.search(AUTHORITY_END)
  const authority = end === -1 ? rest : rest.slice(0, end)
  const remainder = end === -1 ? '' : rest.slice(end)

  // 6. userinfo。
  const hasUserinfo = authority.includes('@')
  const hostport = authority.slice(authority.lastIndexOf('@') + 1)

  // 7. 只允许可打印 ASCII（不接受 IDN、空白和控制字符）。
  if (NON_PRINTABLE_ASCII.test(hostport)) return fail('INVALID')

  let host: string
  let port: number | null = null
  if (hostport.startsWith('[')) {
    // 8. IPv6 字面量。
    const close = hostport.indexOf(']')
    if (close === -1) return fail('INVALID')
    const after = hostport.slice(close + 1)
    if (after !== '') {
      if (!after.startsWith(':') || !PORT_DIGITS.test(after.slice(1))) return fail('INVALID')
      port = Number(after.slice(1))
    }
    const compressed = canonicalIpv6(hostport.slice(1, close))
    if (compressed === null) return fail('INVALID')
    host = `[${compressed}]`
  } else {
    // 9. IPv4 字面量或主机名。
    const colonCount = hostport.split(':').length - 1
    if (colonCount > 1) return fail('INVALID')
    if (colonCount === 1) {
      const colon = hostport.indexOf(':')
      const portText = hostport.slice(colon + 1)
      if (!PORT_DIGITS.test(portText)) return fail('INVALID')
      port = Number(portText)
      host = hostport.slice(0, colon)
    } else {
      host = hostport
    }
    host = host.toLowerCase()
    if (DIGITS_AND_DOTS.test(host)) {
      const octets = host.split('.')
      if (octets.length !== 4) return fail('INVALID')
      for (const octet of octets) {
        if (!IPV4_OCTET.test(octet) || Number(octet) > 255) return fail('INVALID')
      }
    } else {
      if (host === '' || host.length > 253) return fail('INVALID')
      for (const label of host.split('.')) {
        if (!HOST_LABEL.test(label)) return fail('INVALID')
      }
    }
  }

  // 10. 端口范围（只有第 4 步没看到端口时才会走到这里，例如 http://u@a:70000）。
  if (port !== null && (port < 1 || port > 65535)) return fail('INVALID')

  // 11. 只能包含协议、主机和端口。
  if (hasUserinfo || !(remainder === '' || remainder === '/')) return fail('HAS_EXTRA')

  // 12. 规范形式；默认端口省略。
  let canonical = `${scheme}://${host}`
  if (port !== null && port !== DEFAULT_PORTS[scheme]) canonical += `:${port}`

  // 13. 补上 "http://" 之后可能超长。
  if (canonical.length > MAX_ADDRESS_LENGTH) return fail('TOO_LONG')
  return { canonical, error: null }
}

/** 分别校验两个地址；两者都合法、都不为空且规范形式相同时，两个字段都标 SAME_AS_OTHER。 */
export function validateServerAddressPair(
  publicRaw: string,
  lanRaw: string
): { public: AddressCheck; lan: AddressCheck } {
  const pub = validateServerAddress(publicRaw)
  const lan = validateServerAddress(lanRaw)
  if (
    pub.error === null &&
    lan.error === null &&
    pub.canonical !== null &&
    pub.canonical === lan.canonical
  ) {
    return { public: fail('SAME_AS_OTHER'), lan: fail('SAME_AS_OTHER') }
  }
  return { public: pub, lan }
}
