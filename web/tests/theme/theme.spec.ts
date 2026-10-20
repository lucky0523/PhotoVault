import { expect, test, type Locator, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import {
  availableYears,
  buildHeatmap,
  computeThresholds,
  formatDayTooltip,
  heatmapBounds,
  levelOf,
  monthlyRows,
  parseDay,
  rangeSummary,
} from '../../src/components/stats/heatmapModel'
import { formatBytes } from '../../src/components/stats/format'

const screenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-theme-yingji-red-2026-10-14/screenshots/web'
mkdirSync(screenshotDir, { recursive: true })

const types = ['primary', 'success', 'warning', 'danger'] as const
const rgb = (hex: string) => {
  const value = Number.parseInt(hex.slice(1), 16)
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`
}

// Every disabled el-button (all types, solid/plain/dashed) uses one neutral grey triplet.
const disabled = ['#58677E', '#EFF0F2', '#CFD3DA']

const solid = {
  primary: { default: ['#FFFFFF', '#D92D4F', '#D92D4F'], hover: ['#FFFFFF', '#B3123A', '#B3123A'], active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled },
  success: { default: ['#00391C', '#6ECF9C', '#6ECF9C'], hover: ['#00391C', '#8CD9B1', '#8CD9B1'], active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled },
  warning: { default: ['#422B00', '#FFA91F', '#FFA91F'], hover: ['#422B00', '#FFC25C', '#FFC25C'], active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled },
  danger: { default: ['#FFFFFF', '#D92D4F', '#D92D4F'], hover: ['#FFFFFF', '#B3123A', '#B3123A'], active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled },
}

const plain = {
  primary: { default: ['#B3123A', '#FCEAED', '#D92D4F'], hover: ['#FFFFFF', '#D92D4F', '#D92D4F'], active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled: solid.primary.disabled },
  success: { default: ['#00391C', '#F1FAF5', '#007A3D'], hover: ['#00391C', '#E2F5EB', '#007A3D'], active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled: solid.success.disabled },
  warning: { default: ['#422B00', '#FFF6E8', '#A86200'], hover: ['#422B00', '#FFEDD1', '#A86200'], active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled: solid.warning.disabled },
  danger: { default: ['#B3123A', '#FFF2F4', '#D92D4F'], hover: ['#FFFFFF', '#D92D4F', '#D92D4F'], active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled: solid.danger.disabled },
}

// Focus outline uses the active ink.
const links = {
  primary: { ink: '#B3123A', hover: '#B3123A', active: '#B3123A' },
  success: { ink: '#007A3D', hover: '#006633', active: '#00592D' },
  warning: { ink: '#422B00', hover: '#422B00', active: '#A86200' },
  danger: { ink: '#D92D4F', hover: '#B3123A', active: '#9F293B' },
}

async function colors(locator: Locator) {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      color: style.color,
      background: style.backgroundColor,
      border: style.borderTopColor,
      outlineColor: style.outlineColor,
      outlineStyle: style.outlineStyle,
      outlineWidth: style.outlineWidth,
      outlineOffset: style.outlineOffset,
      boxShadow: style.boxShadow,
    }
  })
}

function expectedTriplet(values: string[]) {
  return { color: rgb(values[0]), background: rgb(values[1]), border: rgb(values[2]) }
}

async function expectTriplet(locator: Locator, values: string[]) {
  expect(await colors(locator)).toMatchObject(expectedTriplet(values))
}

async function expectHover(locator: Locator, values: string[]) {
  await locator.hover()
  await locator.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
  await expectTriplet(locator, values)
}

async function expectActive(page: Page, locator: Locator, values: string[]) {
  const box = await locator.boundingBox()
  if (!box) throw new Error('element has no bounding box')
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await locator.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
  await expectTriplet(locator, values)
  await page.mouse.up()
  await page.mouse.move(0, 0)
}

async function expectActiveColor(page: Page, locator: Locator, color: string) {
  const box = await locator.boundingBox()
  if (!box) throw new Error('element has no bounding box')
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await locator.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
  expect((await colors(locator)).color).toBe(rgb(color))
  await page.mouse.up()
  await page.mouse.move(0, 0)
}

async function expectFocus(page: Page, locator: Locator, color: string) {
  const targetIndex = await locator.evaluate((target) => {
    const candidates = [...document.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])')]
      .filter((element) => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden')
    const index = candidates.indexOf(target as HTMLElement)
    if (index > 0) candidates[index - 1].focus()
    return index
  })
  expect(targetIndex).toBeGreaterThan(0)
  await page.keyboard.press('Tab')
  expect(await locator.evaluate((element) => document.activeElement === element)).toBe(true)
  expect(await colors(locator)).toMatchObject({
    outlineColor: rgb(color),
    outlineStyle: 'solid',
    outlineWidth: '2px',
    outlineOffset: '2px',
  })
}

const transparentPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+3fBqAAAAAElFTkSuQmCC', 'base64')

const galleryFile = {
  id: 101,
  file_name: 'yingji-sample.jpg',
  file_size: 2457600,
  mime_type: 'image/jpeg',
  media_type: 'photo',
  thumbnail_url: '',
  exif_time: '2026-10-14T10:30:00',
  created_at: '2026-10-14 10:31:00',
  device_name: '测试手机',
  focal_length: 35,
}

const trashItem = {
  ...galleryFile,
  id: 201,
  file_path: '/trash/yingji-sample.jpg',
  original_path: '/测试手机/yingji-sample.jpg',
  display_path: '测试手机/yingji-sample.jpg',
  deleted_at: '2026-10-14T11:00:00',
  deleted_batch_id: 'fixture-batch',
  expires_at: '2026-11-14T11:00:00',
  is_reference: false,
}

const listResponse = {
  current_path: '',
  parent_path: null,
  directories: [],
  files: [galleryFile],
  total_files: 1,
  page: 1,
  page_size: 50,
}

const distribution = (key: string, label: string, count: number) => ({
  kind: 'distribution',
  buckets: [{ key, label, count, share: 100 }],
  unknown: 0,
  unknown_share: 0,
  total: count,
})

const statsResponse = {
  schema_version: 1,
  scope: { path: '', recursive: true, tz: 'Asia/Shanghai', tz_source: 'request', includes_custom_paths: true },
  generated_at: '2026-10-14T12:00:00Z',
  pending_metadata_count: 0,
  notices: [],
  overview: {
    total: { count: 3, bytes: 7340032 },
    motion_photo_count: 1,
    categories: {
      kind: 'distribution',
      buckets: [
        { key: 'photo', label: '照片', count: 2, share: 66.7, bytes: 5242880, bytes_share: 71.4 },
        { key: 'video', label: '视频', count: 1, share: 33.3, bytes: 2097152, bytes_share: 28.6 },
      ],
      unknown: 0, unknown_share: 0, total: 3, total_bytes: 7340032,
    },
    formats: {
      kind: 'distribution',
      buckets: [{ key: 'jpg', label: 'JPEG', count: 3, share: 100, bytes: 7340032, bytes_share: 100 }],
      unknown: 0, unknown_share: 0, total: 3, total_bytes: 7340032,
    },
  },
  photo: {
    scene_ev: {
      ...distribution('ev10', '8–10', 2),
      summary: { samples: 2, median: 9.2, mean: 9.35 },
    },
  },
  video: {},
}

/** 时间线 mock 用的文件记录（与 FileInfoResponse 同形）。 */
type MockFile = {
  id: number
  file_name: string
  exif_time?: string | null
  created_at: string
  device_name?: string
  focal_length?: number | null
  /** 所在虚拟目录（目录筛选 path / recursive 用），默认 'Pixel 8' */
  dir?: string
  /** 按数据统计口径的拍摄日期（capture_date 筛选用），默认取 exif_time ?? created_at 的日期部分 */
  capture_date?: string
  [key: string]: unknown
}

interface MockRequest {
  method: string
  path: string
  params: URLSearchParams
  search: string
  startedAt: number
  finishedAt?: number
  status?: number
}

interface ApiMockOptions {
  trashItems?: unknown[]
  clientVersions?: unknown[]
  /** 时间线 facets / summary / files 的数据源，默认 [galleryFile]；测试可直接改 api.files 模拟并发写入 */
  timelineFiles?: MockFile[]
  /** 制造慢响应 */
  delayMs?: (url: URL) => number
  /** 处理第 callIndex 次 files 请求前修改 mock 数据（模拟另一台设备并发备份） */
  beforeFiles?: (url: URL, callIndex: number, files: MockFile[]) => void
  /** 返回 true 时 summary 回 500 */
  failSummary?: (callIndex: number) => boolean
  /** 服务端不认识浏览器时区：带 tz 的时间线请求一律 422 invalid_tz */
  rejectTz?: boolean
  /** GET /files/browse 返回的子目录（DirectoryTreeSelect 用），键是父目录虚拟路径（'' = 根）；默认没有目录 */
  browseDirs?: Record<string, { name: string; path: string }[]>
  /** GET /stats/media 的响应，默认 statsResponse（没有 overview.daily，模拟旧版服务端） */
  statsResponse?: unknown
}

// ---- 时间线服务端语义的 mock 实现 ----
// 与 server/app/services/filename_search.py 同语义，仅供 mock（从已删除的 web/src/utils/filenameSearch.ts 搬来）。
const SEARCH_SEPARATORS = /[\s_\-.()]+/g
const normalizeForSearch = (s: string) => s.normalize('NFKC').toLowerCase()
const parseSearchQuery = (q: string) => normalizeForSearch(q).split(/\s+/).filter((k) => k.length > 0)
function matchesFilename(fileName: string, keywords: string[]): boolean {
  if (keywords.length === 0) return true
  const name = normalizeForSearch(fileName)
  const strippedName = name.replace(SEARCH_SEPARATORS, '')
  return keywords.every((k) => {
    const stripped = k.replace(SEARCH_SEPARATORS, '')
    if (stripped.length === 0) return name.includes(k)
    return strippedName.includes(stripped)
  })
}
function mockFileExt(name: string): string {
  const i = name.lastIndexOf('.')
  if (i < 0 || i === name.length - 1) return ''
  return name.slice(i + 1).toUpperCase()
}
function mockFocalBucket(focal?: number | null): string {
  if (focal == null || focal <= 0) return 'unknown'
  if (focal <= 20) return 'ultrawide'
  if (focal <= 35) return 'wide'
  if (focal <= 70) return 'standard'
  if (focal <= 135) return 'tele'
  return 'supertele'
}
const MOCK_FOCAL_ORDER = ['ultrawide', 'wide', 'standard', 'tele', 'supertele', 'unknown']
// mock 数据统一使用无时区的 exif_time，本地年月日直接取字符串前缀，不依赖 Node 进程的时区
const mockTimeKey = (f: MockFile) => (f.exif_time ?? f.created_at) as string
const mockLocalDate = (f: MockFile) => mockTimeKey(f).slice(0, 10)
const mockLocalMonth = (f: MockFile) => mockTimeKey(f).slice(0, 7)

function filterTimeline(files: MockFile[], params: URLSearchParams): MockFile[] {
  const keywords = parseSearchQuery(params.get('q') ?? '')
  const devices = params.getAll('device')
  const formats = params.getAll('format')
  const focals = params.getAll('focal')
  const dateFrom = params.get('date_from')
  const dateTo = params.get('date_to')
  const path = params.get('path') ?? ''
  const recursive = params.get('recursive') !== 'false'
  const captureDate = params.get('capture_date')
  return files.filter((f) =>
    matchesFilename(f.file_name, keywords) &&
    (devices.length === 0 || devices.includes(f.device_name ?? '')) &&
    (formats.length === 0 || formats.includes(mockFileExt(f.file_name))) &&
    (focals.length === 0 || focals.includes(mockFocalBucket(f.focal_length))) &&
    (!dateFrom || !dateTo || (mockLocalDate(f) >= dateFrom && mockLocalDate(f) <= dateTo)) &&
    (path === '' || mockDir(f) === path || (recursive && mockDir(f).startsWith(`${path}/`))) &&
    (!captureDate || (f.capture_date ?? mockLocalDate(f)) === captureDate))
}

const mockDir = (f: MockFile) => f.dir ?? 'Pixel 8'

/** (exif_time ?? created_at) DESC, id DESC */
function sortTimeline(files: MockFile[]): MockFile[] {
  return [...files].sort((a, b) => {
    const ka = mockTimeKey(a)
    const kb = mockTimeKey(b)
    if (ka !== kb) return ka < kb ? 1 : -1
    return b.id - a.id
  })
}

function timelineFacets(files: MockFile[]) {
  const present = new Set(files.map((f) => mockFocalBucket(f.focal_length)))
  return {
    tz: 'Asia/Shanghai',
    devices: [...new Set(files.map((f) => f.device_name ?? '').filter((d) => d !== ''))],
    formats: [...new Set(files.map((f) => mockFileExt(f.file_name)).filter((x) => x !== ''))],
    focal_buckets: MOCK_FOCAL_ORDER.filter((b) => present.has(b)),
    photo_dates: [...new Set(files.map(mockLocalDate))].sort(),
  }
}

function timelineSummary(files: MockFile[], params: URLSearchParams) {
  const counts = new Map<string, number>()
  for (const f of filterTimeline(files, params)) {
    const ym = mockLocalMonth(f)
    counts.set(ym, (counts.get(ym) ?? 0) + 1)
  }
  const months = [...counts.entries()]
    .sort(([a], [b]) => (a < b ? 1 : -1))
    .map(([ym, count]) => ({ year: Number(ym.slice(0, 4)), month: Number(ym.slice(5, 7)), count }))
  return {
    tz: 'Asia/Shanghai',
    total_count: files.length,
    filtered_count: months.reduce((n, m) => n + m.count, 0),
    months,
  }
}

function timelineFilesPage(files: MockFile[], params: URLSearchParams) {
  const month = params.get('month') ?? ''
  const limit = Number(params.get('limit') ?? 200)
  let rows = sortTimeline(filterTimeline(files, params).filter((f) => mockLocalMonth(f) === month))
  const afterTime = params.get('after_time')
  const afterId = params.get('after_id')
  if (afterTime !== null && afterId !== null) {
    const id = Number(afterId)
    rows = rows.filter((f) => mockTimeKey(f) < afterTime || (mockTimeKey(f) === afterTime && f.id < id))
  } else {
    rows = rows.slice(Number(params.get('offset') ?? 0))
  }
  return { month, files: rows.slice(0, limit), has_more: rows.length > limit }
}

async function installApiMock(page: Page, options: ApiMockOptions = {}) {
  const trashItems = options.trashItems ?? [trashItem]
  const files: MockFile[] = [...(options.timelineFiles ?? [galleryFile])]
  const unknown: string[] = []
  const requests: MockRequest[] = []
  const calls = { facets: 0, summary: 0, files: 0 }
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const key = `${request.method()} ${url.pathname}`
    const log: MockRequest = {
      method: request.method(),
      path: url.pathname,
      params: url.searchParams,
      search: url.search,
      startedAt: Date.now(),
    }
    requests.push(log)
    // 页面可能已经 abort 了这个请求（过期响应、删除时作废），fulfill 失败不算错
    const send = async (status: number, body: unknown) => {
      log.status = status
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }).catch(() => {})
      log.finishedAt = Date.now()
    }
    const json = (body: unknown) => send(200, body)

    if (key === 'GET /api/v1/setup/status') return json({ initialized: true, needs_work_dir: false })
    if (key === 'GET /api/v1/auth/registration-status') return json({ allow_registration: true })
    if (key === 'GET /api/v1/files/config') return json({ trash_retention_days: 30 })
    if (key === 'GET /api/v1/files/trash') return json({ items: trashItems, total: trashItems.length, page: 1, page_size: Number(url.searchParams.get('page_size') ?? 50) })
    if (key === 'GET /api/v1/admin/client-versions') return json(options.clientVersions ?? [])
    if (key === 'GET /api/v1/files/list') return json(listResponse)
    if (key === 'GET /api/v1/files/browse') {
      const parent = url.searchParams.get('path') ?? ''
      const directories = (options.browseDirs?.[parent] ?? []).map((d) => ({
        ...d, file_count: 1, size: 0, backed_up_count: 1, trashed_count: 0, purged_count: 0,
      }))
      return json({ ...listResponse, current_path: parent, directories, files: [] })
    }
    if (key === 'GET /api/v1/stats/media') return json(options.statsResponse ?? statsResponse)
    if (key.startsWith('GET /api/v1/files/thumbnail/')) {
      log.status = 200
      return route.fulfill({ status: 200, contentType: 'image/png', body: transparentPng }).catch(() => {})
    }

    const timeline = /^GET \/api\/v1\/files\/timeline\/(facets|summary|files)$/.exec(key)
    if (timeline) {
      const kind = timeline[1] as keyof typeof calls
      const callIndex = calls[kind]++
      if (kind === 'files') options.beforeFiles?.(url, callIndex, files)
      const delay = options.delayMs?.(url) ?? 0
      if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay))
      if (options.rejectTz && url.searchParams.has('tz')) {
        return send(422, { detail: { code: 'invalid_tz', message: `无效的时区: ${url.searchParams.get('tz')}` } })
      }
      if (kind === 'facets') return json(timelineFacets(files))
      if (kind === 'summary') {
        if (options.failSummary?.(callIndex)) return send(500, { detail: '时间线查询失败，请稍后重试' })
        return json(timelineSummary(files, url.searchParams))
      }
      return json(timelineFilesPage(files, url.searchParams))
    }

    const deleted = /^DELETE \/api\/v1\/files\/(\d+)$/.exec(key)
    if (deleted) {
      const index = files.findIndex((f) => f.id === Number(deleted[1]))
      if (index >= 0) files.splice(index, 1)
      return json({ success: true, message: '已移入回收站' })
    }

    unknown.push(`${key}${url.search}`)
    await send(500, { detail: `Unknown Playwright API fixture: ${key}` })
  })
  return { unknown, requests, files }
}

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('access_token', 'fixture.access.token')
    localStorage.setItem('refresh_token', 'fixture.refresh.token')
    localStorage.setItem('user_info', JSON.stringify({ id: 1, username: '主题验收员', is_admin: true }))
  })
}

test('Element Plus 2.14 state matrix and derived controls use Yingji colors', async ({ page }) => {
  test.setTimeout(60_000)
  const { unknown } = await installApiMock(page)
  await page.goto('/tests/theme/theme-fixture.html')

  const rootTokens = await page.locator('html').evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      primary: style.getPropertyValue('--el-color-primary').trim(),
      primaryRgb: style.getPropertyValue('--el-color-primary-rgb').trim(),
      strong: style.getPropertyValue('--pv-brand-strong').trim(),
    }
  })
  expect(rootTokens).toEqual({ primary: '#D92D4F', primaryRgb: '217, 45, 79', strong: '#B3123A' })

  for (const type of types) {
    const solidButton = page.getByTestId(`solid-${type}`)
    await expectTriplet(solidButton, solid[type].default)
    await expectHover(solidButton, solid[type].hover)
    await expectActive(page, solidButton, solid[type].active)
    await expectFocus(page, solidButton, solid[type].focus)
    await expectTriplet(page.getByTestId(`solid-${type}-disabled`), solid[type].disabled)

    const plainButton = page.getByTestId(`plain-${type}`)
    await expectTriplet(plainButton, plain[type].default)
    await expectHover(plainButton, plain[type].hover)
    await expectActive(page, plainButton, plain[type].active)
    await expectFocus(page, plainButton, plain[type].focus)
    await expectTriplet(page.getByTestId(`plain-${type}-disabled`), plain[type].disabled)

    for (const prefix of ['link-button', 'element-link']) {
      const link = page.getByTestId(`${prefix}-${type}`)
      await page.mouse.move(0, 0)
      await link.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
      expect((await colors(link)).color).toBe(rgb(links[type].ink))
      await link.hover()
      await link.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
      expect((await colors(link)).color).toBe(rgb(links[type].hover))
      await expectActiveColor(page, link, links[type].active)
      await expectFocus(page, link, links[type].active)
      expect((await colors(page.getByTestId(`${prefix}-${type}-disabled`))).color).toBe(rgb('#58677E'))
    }
  }

  for (const id of ['solid-default-disabled', 'plain-default-disabled', 'solid-info-disabled', 'plain-info-disabled', 'dashed-danger-disabled']) {
    await expectTriplet(page.getByTestId(id), disabled)
  }
  // Hovering a disabled button must not reveal a type color.
  await expectHover(page.getByTestId('plain-danger-disabled'), disabled)
  await expectHover(page.getByTestId('solid-success-disabled'), disabled)
  // Loading sets the disabled attribute but not .is-disabled, so it keeps the brand fill.
  await expectTriplet(page.getByTestId('solid-primary-loading'), solid.primary.default)

  await page.getByTestId('checkbox').click()
  await page.getByTestId('checkbox').evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  expect((await colors(page.getByTestId('checkbox').locator('.el-checkbox__inner'))).background).toBe(rgb('#D92D4F'))
  await page.getByTestId('radio').click()
  await page.getByTestId('radio').evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  expect((await colors(page.getByTestId('radio').locator('.el-radio__inner'))).background).toBe(rgb('#D92D4F'))
  await page.getByTestId('switch').click()
  await page.getByTestId('switch').evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  expect((await colors(page.getByTestId('switch').locator('.el-switch__core'))).background).toBe(rgb('#D92D4F'))
  const input = page.getByPlaceholder('聚焦输入框')
  await input.focus()
  await input.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 250)))
  expect((await colors(input.locator('xpath=..'))).boxShadow).toContain('rgb(217, 45, 79)')
  expect((await colors(page.locator('.el-pagination .el-pager .is-active'))).background).toBe(rgb('#D92D4F'))
  expect((await colors(page.locator('.el-progress-bar__inner'))).background).toBe(rgb('#D92D4F'))
  await expect(page.getByTestId('loading-target').locator('.el-loading-spinner')).toBeVisible()
  expect(await page.getByTestId('loading-target').locator('.el-loading-spinner .path').evaluate((element) => getComputedStyle(element).stroke)).toBe(rgb('#D92D4F'))

  await page.getByTestId('open-dialog').click()
  const dialogButton = page.getByTestId('dialog-primary')
  await expect(dialogButton).toBeVisible()
  await expectTriplet(dialogButton, solid.primary.default)

  const regression = JSON.parse(await page.getByTestId('statistics-colors').textContent() ?? '{}')
  expect(regression).toEqual({
    colors: { photo: '#4395EB', video: '#FF858A', other: '#465265', unknown: '#787878' },
    text: '#606266',
    series: [
      { color: '#4395EB', dashed: false, pointStyle: 'circle' },
      { color: '#FF858A', dashed: true, pointStyle: 'triangle' },
    ],
  })
  expect(JSON.stringify(regression)).not.toContain('#D92D4F')

  await page.screenshot({ path: `${screenshotDir}/interactions.png`, fullPage: true })
  expect(unknown).toEqual([])
})

test('real TimelineView and Trash header actions expose approved states', async ({ page }) => {
  const { unknown } = await installApiMock(page)
  await page.goto('/tests/theme/theme-fixture.html')

  await page.getByTestId('show-timeline').click()
  const year = page.locator('.year-item').first()
  await expect(year).toBeVisible()
  await year.click()
  await page.locator('.month-item').first().click()
  await year.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  expect(await colors(year)).toMatchObject({ color: rgb('#B3123A'), background: rgb('#FCEAED') })

  await page.locator('.filter-select').first().click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: '测试手机' }).click()
  const badge = page.locator('.filter-badge')
  await expect(badge).toBeVisible()
  expect(await colors(badge)).toMatchObject({ color: rgb('#B3123A'), background: rgb('#FCEAED') })

  await page.getByTestId('show-trash').click()
  const restoreSelected = page.getByRole('button', { name: /恢复已选/ })
  const restoreAll = page.getByRole('button', { name: /恢复所有文件/ })
  const purgeSelected = page.getByRole('button', { name: /彻底删除已选/ })
  const clearAll = page.getByRole('button', { name: /清空回收站/ })
  await expect(restoreAll).toBeEnabled()
  // The buttons flip from disabled to enabled when the list loads; let the color transition settle.
  await restoreAll.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  // One item, nothing selected: selection actions are grey, whole-trash actions keep their colors.
  for (const action of [restoreSelected, purgeSelected]) {
    await expect(action).toBeDisabled()
    await expectTriplet(action, disabled)
  }
  await expectTriplet(restoreAll, solid.success.default)
  await expectTriplet(clearAll, solid.danger.default)

  // One item selected: restore pair is soft green, delete pair is brand red with white text.
  await page.locator('.el-table__body-wrapper .el-checkbox').first().click()
  await expect(restoreSelected).toBeEnabled()
  await expect(purgeSelected).toBeEnabled()
  await purgeSelected.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 750)))
  const actions = [
    [restoreSelected, solid.success],
    [restoreAll, solid.success],
    [purgeSelected, solid.danger],
    [clearAll, solid.danger],
  ] as const
  for (const [action, states] of actions) {
    await expectTriplet(action, states.default)
    await expectHover(action, states.hover)
    // Pressing opens the confirm box; Escape cancels without side effects.
    await expectActive(page, action, states.active)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()
    await expectFocus(page, action, states.focus)
  }
  expect(unknown).toEqual([])
})

test('Trash row actions use green 恢复 and red 彻底删除 links', async ({ page }) => {
  const { unknown } = await installApiMock(page)
  await page.goto('/tests/theme/theme-fixture.html')
  await page.getByTestId('show-trash').click()

  const row = page.locator('.el-table__body tr.el-table__row').first()
  const restore = row.getByRole('button', { name: '恢复', exact: true })
  const purge = row.getByRole('button', { name: '彻底删除', exact: true })
  await expect(restore).toBeVisible()

  await page.mouse.move(0, 0)
  await restore.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 250)))
  expect((await colors(restore)).color).toBe(rgb(links.success.ink))
  expect((await colors(purge)).color).toBe(rgb(links.danger.ink))

  // Over the #F5F7FA row-hover fill, #D92D4F (4.41:1) gives way to #B3123A; green #007A3D still passes (5.08:1).
  await row.locator('td').nth(2).hover()
  await restore.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 250)))
  await expect(row).toHaveClass(/hover-row/)
  expect((await colors(purge.locator('xpath=ancestor::td[1]'))).background).toBe(rgb('#F5F7FA'))
  expect((await colors(restore)).color).toBe(rgb(links.success.ink))
  expect((await colors(purge)).color).toBe(rgb('#B3123A'))
  await page.screenshot({ path: `${screenshotDir}/trash-row-actions-hover.png`, fullPage: true })

  for (const [action, states] of [[restore, links.success], [purge, links.danger]] as const) {
    await action.hover()
    await action.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 150)))
    expect((await colors(action)).color).toBe(rgb(states.hover))
    // Pressing opens the confirm box; Escape cancels without side effects.
    await expectActiveColor(page, action, states.active)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()
    await expectFocus(page, action, states.active)
  }
  expect(unknown).toEqual([])
})

test('Trash empty state shows all four header actions in neutral grey', async ({ page }) => {
  const { unknown } = await installApiMock(page, { trashItems: [] })
  await page.goto('/tests/theme/theme-fixture.html')
  await page.getByTestId('show-trash').click()
  await expect(page.getByText('回收站为空')).toBeVisible()

  // success 恢复已选 / success 恢复所有文件 / danger 彻底删除已选 / danger 清空回收站
  const actions = [/恢复已选/, /恢复所有文件/, /彻底删除已选/, /清空回收站/].map((name) => page.getByRole('button', { name }))
  for (const action of actions) {
    await expect(action).toBeDisabled()
    await expectTriplet(action, disabled)
  }
  await expectHover(actions[3], disabled)

  await page.screenshot({ path: `${screenshotDir}/trash-empty-disabled.png`, fullPage: true })
  expect(unknown).toEqual([])
})

test('ClientVersions upload dialog disables 上传并校验 in neutral grey', async ({ page }) => {
  const { unknown } = await installApiMock(page, { clientVersions: [] })
  await authenticate(page)
  await page.goto('/settings/client-versions')
  await expect(page.getByText('尚未上传 Android 客户端')).toBeVisible()
  await page.getByRole('button', { name: '上传 Android APK' }).click()

  const upload = page.getByRole('dialog').getByRole('button', { name: /上传并校验/ })
  await expect(upload).toBeDisabled()
  await upload.evaluate(() => new Promise((resolve) => window.setTimeout(resolve, 400)))
  await expectTriplet(upload, disabled)

  await page.screenshot({ path: `${screenshotDir}/client-versions-upload-disabled.png`, fullPage: true })
  expect(unknown).toEqual([])
})

test('captures login, main gallery, and statistics in real Chromium', async ({ page }) => {
  const { unknown } = await installApiMock(page)

  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '影集' })).toBeVisible()
  await page.screenshot({ path: `${screenshotDir}/login.png`, fullPage: true })

  await authenticate(page)
  await page.reload()
  await page.goto('/photos')
  await expect(page.getByText('图片浏览', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('yingji-sample.jpg').first()).toBeVisible()
  await page.screenshot({ path: `${screenshotDir}/main-gallery.png`, fullPage: true })

  await page.goto('/statistics')
  await expect(page.getByText('数据统计', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('场景亮度（EV100）')).toBeVisible()
  await expect(page.locator('canvas').first()).toBeVisible()
  await page.screenshot({ path: `${screenshotDir}/statistics.png`, fullPage: true })

  expect(unknown).toEqual([])
})

const timelineScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-web-timeline-datepicker-overflow-2026-10-17/screenshots'

test('Timeline date range picker stays inside the header card', async ({ page }) => {
  test.setTimeout(90_000)
  mkdirSync(timelineScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page)
  await authenticate(page)
  const tolerance = 0.5

  async function measure() {
    return page.evaluate(() => {
      const rect = (element: Element | null) => {
        if (!element) throw new Error('missing element')
        const r = element.getBoundingClientRect()
        return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
      }
      const card = document.querySelector('.timeline-view .pv-page-header')!
      const editor = document.querySelector<HTMLElement>('.timeline-view .date-filter .el-date-editor')!
      const style = getComputedStyle(card)
      return {
        card: rect(card),
        padding: {
          left: Number.parseFloat(style.paddingLeft),
          right: Number.parseFloat(style.paddingRight),
          top: Number.parseFloat(style.paddingTop),
          bottom: Number.parseFloat(style.paddingBottom),
        },
        left: rect(document.querySelector('.timeline-view .pv-page-header__left')),
        wrapper: rect(document.querySelector('.timeline-view .date-filter')),
        editor: rect(editor),
        editorScroll: { scrollWidth: editor.scrollWidth, clientWidth: editor.clientWidth },
        filters: rect(document.querySelector('.timeline-view .tl-filters')),
        // 两行筛选栏里的每个控件
        extra: [...document.querySelectorAll('.timeline-view .tl-filter-row > *')].map(rect),
        rows: [...document.querySelectorAll('.timeline-view .tl-filter-row')].map((row) => [...row.children].map(rect)),
      }
    })
  }

  for (const filtered of [false, true]) {
    for (const width of [1920, 1440, 1280, 1024, 900, 800]) {
      await page.setViewportSize({ width, height: 900 })
      if (!filtered) {
        await page.goto('/timeline')
      } else if (width === 1920) {
        await page.goto('/timeline')
        await page.locator('.timeline-view .filter-select').first().click()
        await page.locator('.el-select-dropdown__item').filter({ hasText: '测试手机' }).click()
        await page.keyboard.press('Escape')
        await expect(page.locator('.timeline-view .filter-badge')).toBeVisible()
      }
      await expect(page.locator('.timeline-view .date-filter .el-date-editor')).toBeVisible()
      await page.waitForTimeout(300)
      const m = await measure()
      const label = `${width} ${filtered ? 'filtered' : 'default'}`
      const inner = {
        left: m.card.left + m.padding.left,
        right: m.card.right - m.padding.right,
        top: m.card.top + m.padding.top,
        bottom: m.card.bottom - m.padding.bottom,
      }
      console.log(label, JSON.stringify(m))
      await page.locator('.timeline-view .pv-page-head').screenshot({
        path: `${timelineScreenshotDir}/timeline-header-${width}-${filtered ? 'filtered' : 'default'}.png`,
      })

      expect.soft(m.editor.right, `${label} editor right`).toBeLessThanOrEqual(inner.right + tolerance)
      expect.soft(m.editor.left, `${label} editor left`).toBeGreaterThanOrEqual(inner.left - tolerance)
      expect.soft(m.editor.top, `${label} editor top`).toBeGreaterThanOrEqual(inner.top - tolerance)
      expect.soft(m.editor.bottom, `${label} editor bottom`).toBeLessThanOrEqual(inner.bottom + tolerance)
      expect.soft(Math.abs(m.editor.width - m.wrapper.width), `${label} editor vs wrapper width`).toBeLessThanOrEqual(tolerance)
      expect.soft(m.editorScroll.scrollWidth, `${label} editor scrollWidth`).toBeLessThanOrEqual(m.editorScroll.clientWidth)
      for (const [index, child] of m.extra.entries()) {
        expect.soft(child.left, `${label} extra[${index}] left`).toBeGreaterThanOrEqual(inner.left - tolerance)
        expect.soft(child.right, `${label} extra[${index}] right`).toBeLessThanOrEqual(inner.right + tolerance)
      }

      for (const [index, child] of m.extra.entries()) {
        expect.soft(child.top, `${label} extra[${index}] top`).toBeGreaterThanOrEqual(inner.top - tolerance)
        expect.soft(child.bottom, `${label} extra[${index}] bottom`).toBeLessThanOrEqual(inner.bottom + tolerance)
      }

      // 筛选栏有意改成两行：>= 1440 时两行各自排成一行（同一行的控件垂直居中对齐），
      // >= 1280 时标题仍在整块筛选栏左侧。
      expect.soft(m.rows.length, `${label} filter rows`).toBe(2)
      if (width >= 1440) {
        for (const [r, row] of m.rows.entries()) {
          const center = (b: { top: number; height: number }) => b.top + b.height / 2
          for (const [index, child] of row.entries()) {
            expect.soft(Math.abs(center(child) - center(row[0])), `${label} row ${r + 1} child ${index} on one line`).toBeLessThanOrEqual(1)
          }
        }
      }
      if (width >= 1280) {
        expect.soft(m.left.right, `${label} title beside filters`).toBeLessThan(m.filters.left)
      }
    }
  }
  expect(unknown).toEqual([])
})

// 时间线（服务端分页 + 服务端搜索）的 mock 截图
const timelineTaskScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-timeline-server-paging-search-2026-10-17/screenshots/mock'
const followupScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-timeline-server-paging-search-2026-10-17/screenshots/followup'

test('Timeline filename search matches fuzzily across the whole library', async ({ page }) => {
  test.setTimeout(90_000)
  mkdirSync(timelineTaskScreenshotDir, { recursive: true })
  const pad = (n: number, len = 2) => String(n).padStart(len, '0')
  const allFiles: MockFile[] = Array.from({ length: 250 }, (_, i) => ({
    ...galleryFile,
    id: 1000 + i,
    file_name: `DSC_${pad(i, 4)}.JPG`,
    exif_time: `2025-${pad((i % 12) + 1)}-${pad((i % 28) + 1)}T10:00:00`,
  }))
  // 特殊文件放在最旧的月份里，首屏不会加载到，证明搜索覆盖整个图库（服务端执行）。
  allFiles.push(
    { ...galleryFile, id: 2001, file_name: 'IMG_1234.JPG', device_name: '测试手机', exif_time: '2024-03-01T10:00:00' },
    { ...galleryFile, id: 2002, file_name: 'Screenshot 2024-05-01 (1).png', mime_type: 'image/png', device_name: '另一台手机', exif_time: '2024-05-01T10:00:00' },
    { ...galleryFile, id: 2003, file_name: 'a%b_c.jpg', device_name: '另一台手机', exif_time: '2024-06-01T10:00:00' },
  )
  const total = allFiles.length
  const { unknown, requests } = await installApiMock(page, { timelineFiles: allFiles })
  const consoleIssues = collectConsoleIssues(page)
  await authenticate(page)
  await page.setViewportSize({ width: 1920, height: 900 })
  await page.goto('/timeline')

  const tiles = page.locator('.timeline-view .pv-tile')
  const names = page.locator('.timeline-view .pv-tile__name')
  const search = page.locator('.timeline-view .filename-search')
  const searchInput = search.locator('input')
  const badge = page.locator('.timeline-view .filter-badge')
  const subtitle = page.locator('.timeline-view .pv-page-header .pv-muted')
  const head = page.locator('.timeline-view .pv-page-head')
  const yearCount = (year: number) => page.locator('.timeline-view .year-item').filter({ hasText: `${year}年` }).locator('.year-count')
  // 窗口化渲染下 DOM 里只有视口附近的瓦片，总数看副标题和时间轴
  const expectWholeLibrary = async () => {
    await expect(subtitle).toHaveText(`共 ${total} 张照片`)
    await expect(yearCount(2025)).toHaveText('250')
    await expect(yearCount(2024)).toHaveText('3')
  }
  const summaryQueries = () => requests.filter((r) => r.path.endsWith('/timeline/summary')).map((r) => r.params.get('q'))
  const summaryCount = () => requests.filter((r) => r.path.endsWith('/timeline/summary')).length
  const dataRequestCount = () => requests.filter((r) => /\/timeline\/(summary|files)$/.test(r.path)).length
  await expectWholeLibrary()

  // 只有按回车才搜索；expect 自带重试，会等到结果更新。
  const searchFor = async (q: string) => {
    await searchInput.fill(q)
    await searchInput.press('Enter')
  }
  /** 等 600ms，期间没有任何 summary / files 请求。 */
  const expectNoDataRequests = async (label: string) => {
    const before = dataRequestCount()
    await page.waitForTimeout(600)
    expect(dataRequestCount(), label).toBe(before)
  }

  // 1. 两行筛选栏：搜索框是第 1 行的第一个控件，设备筛选框是第 2 行的第一个控件（在搜索框下方）；
  //    搜索框宽约 200px；占位文字完整显示不截断，回车提示在 title 里
  //    （“搜索文件名，回车搜索”需要 140px，输入框只有 134px，放不下）。
  await expect(page.locator('.timeline-view .tl-filter-row--primary > .filename-search:first-child')).toHaveCount(1)
  const device = page.locator('.timeline-view .tl-filter-row--secondary > .filter-select:first-child')
  await expect(device).toHaveCount(1)
  await expect(device).toContainText('设备')
  await expect(searchInput).toHaveAttribute('placeholder', '搜索文件名')
  await expect(searchInput).toHaveAttribute('title', '输入文件名后按回车搜索')
  await expect(searchInput).toHaveAttribute('aria-label', '搜索文件名')
  await expect(searchInput).toHaveAttribute('maxlength', '200')
  const searchBox = (await search.boundingBox())!
  const deviceBox = (await device.boundingBox())!
  expect(searchBox.y + searchBox.height).toBeLessThanOrEqual(deviceBox.y)
  expect(Math.abs(searchBox.width - 200)).toBeLessThanOrEqual(0.5)
  const placeholderFit = await searchInput.evaluate((input: HTMLInputElement) => {
    const style = getComputedStyle(input)
    const ctx = document.createElement('canvas').getContext('2d')!
    ctx.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    const box = input.clientWidth - Number.parseFloat(style.paddingLeft) - Number.parseFloat(style.paddingRight)
    return { text: ctx.measureText(input.placeholder).width, box, rejected: ctx.measureText('搜索文件名，回车搜索').width }
  })
  expect(placeholderFit.text, JSON.stringify(placeholderFit)).toBeLessThanOrEqual(placeholderFit.box)
  console.log('placeholder fit 1920', JSON.stringify(placeholderFit))
  await head.screenshot({ path: `${timelineTaskScreenshotDir}/01-search-before-device-1920.png` })
  // 回车搜索改动的标题栏截图（1920 / 1440）
  mkdirSync(followupScreenshotDir, { recursive: true })
  await head.screenshot({ path: `${followupScreenshotDir}/header-enter-search-1920.png` })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.waitForTimeout(300)
  expect(Math.abs((await search.boundingBox())!.width - 200)).toBeLessThanOrEqual(0.5)
  await head.screenshot({ path: `${followupScreenshotDir}/header-enter-search-1440.png` })
  await page.setViewportSize({ width: 1920, height: 900 })

  // 1b. 打字不触发请求，结果、徽标、描边都不变；回车只发一次 summary。
  await searchInput.pressSequentially('img_12', { delay: 50 })
  await expectNoDataRequests('typing')
  await expect(badge).toBeHidden()
  await expect(search).not.toHaveClass(/is-active/)
  await expectWholeLibrary()
  const beforeEnter = summaryCount()
  await searchInput.press('Enter')
  await expect(names).toHaveText(['IMG_1234.JPG'])
  await page.waitForTimeout(600)
  expect(summaryCount()).toBe(beforeEnter + 1)
  expect(summaryQueries().at(-1)).toBe('img_12')
  await expect(badge).toContainText('已筛选 1 项')

  // 1c. 搜索词相同（含首尾空白）再按回车不重新请求。
  await searchInput.press('Enter')
  await expectNoDataRequests('same query Enter')
  await searchInput.fill(' img_12 ')
  await searchInput.press('Enter')
  await expectNoDataRequests('same trimmed query Enter')

  // 1d. 输入法组字时按回车（确认候选词）不搜索。
  await searchInput.fill('img_12x')
  for (const variant of ['isComposing', 'keyCode229'] as const) {
    await searchInput.evaluate((input, v) => {
      const event = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true, isComposing: v === 'isComposing' })
      if (v === 'keyCode229') Object.defineProperty(event, 'keyCode', { get: () => 229 })
      input.dispatchEvent(event)
    }, variant)
    await expectNoDataRequests(`IME Enter ${variant}`)
  }
  await expect(names).toHaveText(['IMG_1234.JPG'])

  // 1e. 手动删空输入框、不按回车：不重新请求，仍是搜索结果；再按回车才恢复全部。
  await searchInput.fill('img_12')
  await searchInput.press('ControlOrMeta+a')
  await searchInput.press('Backspace')
  await expect(searchInput).toHaveValue('')
  await expectNoDataRequests('delete to empty')
  await expect(names).toHaveText(['IMG_1234.JPG'])
  await expect(badge).toContainText('已筛选 1 项')
  const beforeEmptyEnter = summaryCount()
  await searchInput.press('Enter')
  await expectWholeLibrary()
  await expect(badge).toBeHidden()
  expect(summaryCount()).toBe(beforeEmptyEnter + 1)
  expect(summaryQueries().at(-1)).toBeNull()

  // 2. 部分文件名，大小写 / 分隔符 / 全角都不敏感；搜索词随 summary 请求发给服务端。
  for (const q of ['img_12', 'IMG12', 'img-12', 'ＩＭＧ_１２']) {
    await searchFor(q)
    await expect.poll(summaryQueries, q).toContain(q)
    await expect(tiles, q).toHaveCount(1)
    await expect(names, q).toHaveText(['IMG_1234.JPG'])
  }
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/02-partial-name-img12.png` })

  // 3. % 按字面匹配，多关键词 AND。
  await searchFor('%')
  await expect(names).toHaveText(['a%b_c.jpg'])
  await searchFor('screenshot 2024')
  await expect.poll(summaryQueries).toContain('screenshot 2024')
  await expect(names).toHaveText(['Screenshot 2024-05-01 (1).png'])

  // 4. 搜索算作一个筛选条件，输入框有品牌红描边。
  await expect(badge).toBeVisible()
  await expect(badge).toContainText('已筛选 1 项')
  await searchInput.blur()
  await expect.poll(() => search.locator('.el-input__wrapper').evaluate((el) => getComputedStyle(el).boxShadow)).toContain('rgb(217, 45, 79)')
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.waitForTimeout(300)
  await head.screenshot({ path: `${timelineTaskScreenshotDir}/03-search-active-header-1440.png` })
  await page.setViewportSize({ width: 1920, height: 900 })

  // 5. 与设备筛选取交集。
  await searchFor('jpg')
  await expect(badge).toContainText(`${total - 1}/${total} 张`)
  await page.locator('.timeline-view .filter-select').first().click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: '另一台手机' }).click()
  await page.keyboard.press('Escape')
  await expect(names).toHaveText(['a%b_c.jpg'])
  await expect(badge).toContainText('已筛选 2 项')
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/04-search-plus-device.png` })

  // 6. 无结果时提示筛选无匹配，而不是“暂无备份图片”。
  await searchFor('zzz-nomatch')
  await expect(page.getByText('没有符合筛选条件的照片')).toBeVisible()

  // 7. 清除筛选同时清空输入框和搜索词。
  await page.getByRole('button', { name: /清除筛选/ }).click()
  await expect(searchInput).toHaveValue('')
  await expect(badge).toBeHidden()
  await expectWholeLibrary()

  // 8. 输入框自带的清除按钮不用回车，立即恢复全部结果。
  await searchFor('img_12')
  await expect(tiles).toHaveCount(1)
  const beforeClear = summaryCount()
  await search.hover()
  await search.locator('.el-input__clear').click()
  await expect(searchInput).toHaveValue('')
  await expectWholeLibrary()
  expect(summaryCount()).toBe(beforeClear + 1)
  expect(summaryQueries().at(-1)).toBeNull()
  await expect(badge).toBeHidden()
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/05-cleared-restored.png` })

  expect(summaryQueries()).toContain(null)
  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

// ---------------------------------------------------------------------------
// 时间线：窗口化渲染、按需分页、跳转、预览、删除、对账（design.md V3 新增用例 1–11）
// ---------------------------------------------------------------------------

type LayoutSnapshot = {
  cols: number
  tile: number
  pitch: number
  gap: number
  headerBlock: number
  groupGap: number
  sections: { key: string; top: number; height: number; rows: number }[]
  bottomSpacer: number
  totalHeight: number
}

const pad2 = (n: number, len = 2) => String(n).padStart(len, '0')

/**
 * 生成某月 count 个文件：下标 0 最新，文件名 `${prefix}_${5 位下标}.JPG` 就是它在本月的位置。
 * 时间分布在 1–28 日之间，插入用的“更新”文件放在 29 日（见 topFiles）。
 */
function monthFiles(month: string, count: number, startId: number, prefix: string, extra: Partial<MockFile> = {}): MockFile[] {
  const span = 28 * 86400
  const step = Math.max(1, Math.floor(span / count))
  return Array.from({ length: count }, (_, i) => {
    const t = span - 1 - i * step
    const day = Math.floor(t / 86400) + 1
    const h = Math.floor((t % 86400) / 3600)
    const m = Math.floor((t % 3600) / 60)
    const s = t % 60
    return {
      ...galleryFile,
      id: startId + i,
      file_name: `${prefix}_${pad2(i, 5)}.JPG`,
      exif_time: `${month}-${pad2(day)}T${pad2(h)}:${pad2(m)}:${pad2(s)}`,
      ...extra,
    }
  })
}

/** 比该月所有 monthFiles 都新的 n 个文件（模拟另一台设备刚备份上来）。 */
function topFiles(month: string, n: number, startId: number, prefix: string): MockFile[] {
  return Array.from({ length: n }, (_, i) => ({
    ...galleryFile,
    id: startId + i,
    file_name: `${prefix}_${pad2(i, 3)}.JPG`,
    exif_time: `${month}-29T12:${pad2(Math.floor(i / 60))}:${pad2(i % 60)}`,
  }))
}

function collectConsoleIssues(page: Page) {
  const duplicateKeys: string[] = []
  const warnings: string[] = []
  page.on('console', (msg) => {
    const type = msg.type()
    if ((type === 'warning' || type === 'error') && msg.text().includes('Duplicate keys')) duplicateKeys.push(msg.text())
    if (type === 'warning') warnings.push(msg.text())
  })
  return { duplicateKeys, warnings }
}

async function openTimeline(page: Page, width = 1440, height = 900) {
  await authenticate(page)
  await page.setViewportSize({ width, height })
  await page.goto('/timeline')
  await expect(page.locator('.timeline-view .photo-group').first()).toBeVisible()
}

async function readLayout(page: Page): Promise<LayoutSnapshot> {
  return page.evaluate(() => {
    const layout = (window as unknown as { __pvTimelineLayout?: unknown }).__pvTimelineLayout
    if (!layout) throw new Error('window.__pvTimelineLayout is missing')
    return JSON.parse(JSON.stringify(layout))
  })
}

const pageBody = (page: Page) => page.locator('.timeline-view .pv-page-body')

async function scrollBodyTo(page: Page, top: number) {
  await pageBody(page).evaluate((el, t) => { el.scrollTop = t }, top)
}

async function scrollBodyBy(page: Page, delta: number) {
  await pageBody(page).evaluate((el, d) => { el.scrollTop += d }, delta)
}

/** 把第 sectionIndex 个月里下标 index 所在的行滚到视口顶部。 */
async function scrollToIndex(page: Page, sectionIndex: number, index: number) {
  await pageBody(page).evaluate((el, [si, idx]) => {
    const L = (window as unknown as { __pvTimelineLayout: LayoutSnapshot }).__pvTimelineLayout
    const content = el.querySelector<HTMLElement>('.photos-content')!
    const sec = L.sections[si]
    el.scrollTop = content.offsetTop + sec.top + L.headerBlock + Math.floor(idx / L.cols) * L.pitch
  }, [sectionIndex, index] as const)
}

const tileByName = (page: Page, name: string) =>
  page.locator('.timeline-view .pv-tile').filter({ has: page.locator('.pv-tile__name', { hasText: new RegExp(`^${name.replace(/[.%]/g, '\\$&')}$`) }) })

/** 当前 DOM 里所有已加载瓦片的文件名。 */
async function renderedNames(page: Page): Promise<string[]> {
  return page.locator('.timeline-view .pv-tile__name').allTextContents()
}

/** 与视口相交的瓦片文件名。 */
async function namesInViewport(page: Page): Promise<string[]> {
  return pageBody(page).evaluate((body) => {
    const r = body.getBoundingClientRect()
    return [...body.querySelectorAll<HTMLElement>('.pv-tile:not(.is-placeholder)')]
      .filter((t) => {
        const b = t.getBoundingClientRect()
        return b.bottom > r.top && b.top < r.bottom
      })
      .map((t) => t.querySelector('.pv-tile__name')?.textContent ?? '')
  })
}

/** 等滚动防抖（最长 250ms）过去，再等可见窗口里的占位瓦片都被替换、时间线请求都已返回。 */
async function settle(page: Page, requests?: MockRequest[]) {
  await page.waitForTimeout(350)
  await expect.poll(async () => {
    const placeholders = await page.locator('.timeline-view .pv-tile.is-placeholder').count()
    const pending = (requests ?? []).filter((r) => r.path.includes('/timeline/') && r.finishedAt === undefined).length
    return placeholders + pending
  }, { timeout: 10_000 }).toBe(0)
}

/** 第一个瓦片的宽度与布局 tile 之差 ≤ 0.5 px，第一行的瓦片数等于 cols。 */
async function expectTilesMatchLayout(page: Page) {
  await expect.poll(() => page.evaluate(() => {
    const L = (window as unknown as { __pvTimelineLayout?: LayoutSnapshot }).__pvTimelineLayout
    const grid = document.querySelector('.timeline-view .photo-group .pv-photo-grid')
    const tiles = grid ? [...grid.querySelectorAll<HTMLElement>('.pv-tile')] : []
    if (!L || tiles.length === 0) return null
    const first = tiles[0].getBoundingClientRect()
    const sameRow = tiles.filter((t) => Math.abs(t.getBoundingClientRect().top - first.top) < 1).length
    return { widthOk: Math.abs(first.width - L.tile) <= 0.5, rowMatchesCols: sameRow === L.cols }
  })).toEqual({ widthOk: true, rowMatchesCols: true })
}

const filesRequests = (requests: MockRequest[], month?: string) =>
  requests.filter((r) => r.path.endsWith('/timeline/files') && (month === undefined || r.params.get('month') === month))

const monthCount = (page: Page, month: number) =>
  page.locator('.timeline-view .month-item').filter({ has: page.locator('.month-label', { hasText: new RegExp(`^${month}月$`) }) }).locator('.month-count')

const groupByTitle = (page: Page, title: string) =>
  page.locator('.timeline-view .photo-group').filter({ has: page.locator('.pv-group-title', { hasText: title }) })

/** 分组顶边与 .pv-page-body 可视区顶边的距离（跳转目标 scrollTop = offsetTop + 区块 top）。 */
async function groupOffsetFromBodyTop(page: Page, title: string): Promise<number> {
  const group = groupByTitle(page, title)
  return group.evaluate((el) => {
    const body = el.closest<HTMLElement>('.pv-page-body')!
    return el.getBoundingClientRect().top - body.getBoundingClientRect().top
  })
}

test('Timeline windowed rendering loads months on demand with keyset paging', async ({ page }) => {
  test.setTimeout(120_000)
  const timelineFiles = [1, 2, 3, 4, 5, 6].flatMap((m) => monthFiles(`2025-${pad2(m)}`, 500, m * 10_000, `M${pad2(m)}`))
  const { unknown, requests } = await installApiMock(page, { timelineFiles })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)

  await expect(tileByName(page, 'M06_00000.JPG')).toBeInViewport()
  await expectTilesMatchLayout(page)
  expect(requests.some((r) => r.path === '/api/v1/files/all')).toBe(false)

  // 首屏 files 请求不经过 80ms 防抖：紧跟在 summary 响应之后发出
  const summary = requests.find((r) => r.path.endsWith('/timeline/summary') && r.status === 200)!
  const firstFiles = filesRequests(requests)[0]
  expect(firstFiles.startedAt - summary.finishedAt!).toBeLessThan(60)
  expect(firstFiles.params.get('offset')).toBe('0')

  // 从头滚到底，每 500px 采样一次瓦片数
  const maxTiles = await pageBody(page).evaluate(async (body) => {
    const frame = () => new Promise((resolve) => requestAnimationFrame(resolve))
    let max = 0
    for (let top = 0; top < body.scrollHeight - body.clientHeight; top += 500) {
      body.scrollTop = top
      await frame()
      await frame()
      await new Promise((resolve) => setTimeout(resolve, 30))
      max = Math.max(max, body.querySelectorAll('.pv-tile').length)
    }
    body.scrollTop = body.scrollHeight
    await frame()
    await frame()
    return Math.max(max, body.querySelectorAll('.pv-tile').length)
  })
  expect(maxTiles).toBeGreaterThan(0)
  expect(maxTiles).toBeLessThan(400)
  await expect(tileByName(page, 'M01_00499.JPG')).toBeInViewport({ timeout: 10_000 })
  // 顺序向下续页走 keyset
  expect(filesRequests(requests).some((r) => r.params.has('after_time') && r.params.has('after_id') && !r.params.has('offset'))).toBe(true)
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/06-windowed-bottom.png` })

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline jumps to the farthest unloaded month and scrolls back up', async ({ page }) => {
  test.setTimeout(90_000)
  const timelineFiles = [1, 2, 3, 4, 5, 6].flatMap((m) => monthFiles(`2025-${pad2(m)}`, 500, m * 10_000, `M${pad2(m)}`))
  const { unknown, requests } = await installApiMock(page, { timelineFiles })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)

  await page.locator('.timeline-view .month-item').filter({ hasText: /^1月/ }).click()
  await expect(groupByTitle(page, '2025年1月')).toBeVisible()
  expect(Math.abs(await groupOffsetFromBodyTop(page, '2025年1月'))).toBeLessThanOrEqual(2)
  await expect(tileByName(page, 'M01_00000.JPG')).toBeInViewport()
  await expect(page.locator('.timeline-view .month-item.active .month-label')).toHaveText('1月')
  expect(filesRequests(requests, '2025-01').some((r) => r.params.get('offset') === '0')).toBe(true)
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/07-jump-farthest-month.png` })

  // 向上滚一屏：上一个月的照片出现，高亮跟随
  const clientHeight = await pageBody(page).evaluate((el) => el.clientHeight)
  await scrollBodyBy(page, -clientHeight)
  await expect.poll(async () => (await namesInViewport(page)).some((n) => n.startsWith('M02_'))).toBe(true)
  await expect(page.locator('.timeline-view .month-item.active .month-label')).toHaveText('2月')

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline jump reaches a tiny last month at the bottom', async ({ page }) => {
  const timelineFiles = [
    ...monthFiles('2025-06', 500, 60_000, 'M06'),
    ...monthFiles('2025-05', 500, 50_000, 'M05'),
    ...monthFiles('2025-04', 2, 40_000, 'M04'),
  ]
  const { unknown, requests } = await installApiMock(page, { timelineFiles })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page, 1440, 900)

  await page.locator('.timeline-view .month-item').filter({ hasText: /^4月/ }).click()
  await expect(tileByName(page, 'M04_00000.JPG')).toBeInViewport()
  await expect(tileByName(page, 'M04_00001.JPG')).toBeInViewport()
  await page.waitForTimeout(600)
  // 底部垫高让只有一行的月份也能滚到视口顶部；跳转期间和到达后高亮都是被点的月份
  expect(Math.abs(await groupOffsetFromBodyTop(page, '2025年4月'))).toBeLessThanOrEqual(2)
  await expect(page.locator('.timeline-view .month-item.active .month-label')).toHaveText('4月')
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/08-jump-tiny-last-month.png` })

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline short smooth jump keeps the clicked month highlighted every frame', async ({ page }) => {
  const timelineFiles = [6, 5, 4, 3].flatMap((m) => monthFiles(`2025-${pad2(m)}`, 30, m * 10_000, `M${pad2(m)}`))
  const { unknown, requests } = await installApiMock(page, { timelineFiles })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)

  const samples = await page.evaluate(async () => {
    const body = document.querySelector<HTMLElement>('.timeline-view .pv-page-body')!
    const item = [...document.querySelectorAll<HTMLElement>('.timeline-view .month-item')]
      .find((el) => el.querySelector('.month-label')?.textContent === '5月')!
    item.click()
    const out: { active: string | null; top: number }[] = []
    const start = performance.now()
    while (performance.now() - start < 1200) {
      await new Promise((resolve) => requestAnimationFrame(resolve))
      out.push({
        active: document.querySelector('.timeline-view .month-item.active .month-label')?.textContent ?? null,
        top: body.scrollTop,
      })
    }
    return out
  })
  // 距离 ≤ 3 屏走平滑滚动：途经多个中间位置，且每一帧高亮都是被点的月份
  expect(new Set(samples.map((s) => Math.round(s.top))).size).toBeGreaterThan(2)
  expect(samples.filter((s) => s.active !== '5月')).toEqual([])
  expect(Math.abs(await groupOffsetFromBodyTop(page, '2025年5月'))).toBeLessThanOrEqual(2)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline big month loads the middle by offset and fills upwards', async ({ page }) => {
  test.setTimeout(90_000)
  const { unknown, requests } = await installApiMock(page, { timelineFiles: monthFiles('2025-03', 5000, 100_000, 'B03') })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)

  const L = await readLayout(page)
  await scrollToIndex(page, 0, 2500)
  const topIndex = Math.floor(2500 / L.cols) * L.cols
  await expect(tileByName(page, `B03_${pad2(topIndex, 5)}.JPG`)).toBeInViewport()
  const middleOffsets = filesRequests(requests, '2025-03').map((r) => Number(r.params.get('offset') ?? -1)).filter((o) => o > 0)
  expect(middleOffsets.length).toBeGreaterThan(0)
  const firstMiddle = middleOffsets[0]

  // 向上滚一屏：前一格未加载，向上补洞用 offset
  const clientHeight = await pageBody(page).evaluate((el) => el.clientHeight)
  await scrollBodyBy(page, -clientHeight)
  const upIndex = await pageBody(page).evaluate((body) => {
    const L = (window as unknown as { __pvTimelineLayout: LayoutSnapshot }).__pvTimelineLayout
    const content = body.querySelector<HTMLElement>('.photos-content')!
    const y = body.scrollTop - content.offsetTop - L.sections[0].top - L.headerBlock
    return Math.max(0, Math.ceil(y / L.pitch)) * L.cols
  })
  await expect(tileByName(page, `B03_${pad2(upIndex, 5)}.JPG`)).toBeInViewport()
  await expect.poll(() => filesRequests(requests, '2025-03').some((r) => {
    const offset = Number(r.params.get('offset') ?? -1)
    return offset > 0 && offset < firstMiddle
  })).toBe(true)
  await settle(page, requests)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline filter change discards a slower stale summary and repeats device keys', async ({ page }) => {
  const timelineFiles = [
    ...monthFiles('2025-06', 20, 60_000, 'DA', { device_name: 'Pixel-A' }),
    ...monthFiles('2025-05', 20, 50_000, 'DB', { device_name: 'Pixel-B' }),
  ]
  let delayed = false
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles,
    delayMs: (url) => {
      const devices = url.searchParams.getAll('device')
      if (!delayed && url.pathname.endsWith('/summary') && devices.length === 1 && devices[0] === 'Pixel-A') {
        delayed = true
        return 800
      }
      return 0
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)

  const badge = page.locator('.timeline-view .filter-badge')
  await page.locator('.timeline-view .filter-select').first().click()
  const option = (name: string) => page.locator('.el-select-dropdown__item').filter({ hasText: name })
  // 先选 A（慢），紧接着改成 B
  await option('Pixel-A').click()
  await option('Pixel-A').click()
  await option('Pixel-B').click()
  await expect(badge).toContainText('已筛选 1 项 · 20/40 张')
  await page.waitForTimeout(1200) // 慢的 A 响应已经回来
  await expect(badge).toContainText('已筛选 1 项 · 20/40 张')
  expect((await renderedNames(page)).every((n) => n.startsWith('DB_'))).toBe(true)
  expect(delayed).toBe(true)

  // 多选以重复键 device=A&device=B 发送
  await option('Pixel-A').click()
  await page.keyboard.press('Escape')
  await expect(badge).toContainText('已筛选 1 项 · 40/40 张')
  const both = requests.filter((r) => r.path.endsWith('/timeline/summary') && r.params.getAll('device').length === 2)
  expect(both.length).toBeGreaterThan(0)
  expect(both[0].params.getAll('device').sort()).toEqual(['Pixel-A', 'Pixel-B'])
  expect(both[0].search).not.toContain('%5B')
  expect(both[0].search).not.toContain('[')
  expect(filesRequests(requests).filter((r) => r.params.getAll('device').length === 2).length).toBeGreaterThan(0)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline preview crosses the page boundary inside a month', async ({ page }) => {
  test.setTimeout(90_000)
  const timelineFiles = monthFiles('2025-05', 450, 50_000, 'P')
  let slowArmed = false
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles,
    delayMs: (url) => {
      if (slowArmed && url.pathname.endsWith('/timeline/files')) {
        slowArmed = false
        return 600
      }
      return 0
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)
  expect(filesRequests(requests).length).toBe(1) // 第 1～200 张

  const counter = page.locator('.lightbox-counter')
  const fileName = page.locator('.lightbox-filename')

  // a. 打开第 200 张，按两次 → 到第 202 张
  await scrollToIndex(page, 0, 199)
  await expect(tileByName(page, 'P_00199.JPG')).toBeInViewport()
  await tileByName(page, 'P_00199.JPG').click()
  await expect(counter).toHaveText('200 / 450')
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect(fileName).toHaveText('P_00201.JPG')
  await expect(counter).toHaveText('202 / 450')
  // 跨过第 200 张的分页边界：用第 200 张做 keyset 游标
  const id199 = String(timelineFiles[199].id)
  expect(filesRequests(requests).some((r) => r.params.get('after_id') === id199)).toBe(true)
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/09-preview-across-page.png` })
  await page.keyboard.press('Escape')
  await expect(counter).toBeHidden()

  // b. 打开预览时该月有一个慢的可见窗口请求在途：按 → 后第 201 张最终显示
  await page.reload()
  await expect(page.locator('.timeline-view .photo-group').first()).toBeVisible()
  await settle(page, requests)
  const before = filesRequests(requests).length
  slowArmed = true
  await scrollToIndex(page, 0, 199)
  await tileByName(page, 'P_00199.JPG').click()
  await expect(counter).toHaveText('200 / 450')
  await expect.poll(() => filesRequests(requests).length).toBeGreaterThan(before)
  const slow = filesRequests(requests)[before]
  const pressedAt = Date.now()
  await page.keyboard.press('ArrowRight')
  await expect(counter).toHaveText('201 / 450')
  await expect(fileName).toHaveText('P_00200.JPG', { timeout: 5_000 })
  expect(slow.finishedAt === undefined || slow.finishedAt > pressedAt).toBe(true)
  await page.keyboard.press('Escape')

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

async function deleteViaContextMenu(page: Page, name: string, beforeConfirm?: () => Promise<void>) {
  await tileByName(page, name).click({ button: 'right' })
  await page.locator('.pv-context-menu__item.is-danger').click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  if (beforeConfirm) await beforeConfirm()
  await box.getByRole('button', { name: '移入回收站' }).click()
}

test('Timeline delete updates counts, row layout and facets without duplicates', async ({ page }) => {
  test.setTimeout(90_000)
  let slowArmed = false
  const { unknown, requests, files } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-06', 10, 60_000, 'X06'),
    delayMs: (url) => {
      if (slowArmed && url.pathname.endsWith('/timeline/files') && url.searchParams.has('after_id')) {
        slowArmed = false
        return 3000
      }
      return 0
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  // 让第一个月正好比整行多 1 张：删掉 1 张后最后一行消失
  const { cols } = await readLayout(page)
  const count = cols * 50 + 1
  files.splice(0, files.length, ...monthFiles('2025-06', count, 60_000, 'X06'), ...monthFiles('2025-05', 50, 50_000, 'X05'))
  await page.reload()
  await expect(tileByName(page, 'X06_00000.JPG')).toBeInViewport()
  await settle(page, requests)
  const before = await readLayout(page)
  const subtitle = page.locator('.timeline-view .pv-page-header .pv-muted')
  await expect(subtitle).toHaveText(`共 ${count + 50} 张照片`)
  await expect(monthCount(page, 6)).toHaveText(String(count))

  // 制造一个该月的在途请求（慢的 keyset 续页），然后回到顶部删除第一张
  slowArmed = true
  await scrollToIndex(page, 0, 210)
  const inflight = () => filesRequests(requests, '2025-06').find((r) => r.params.has('after_id') && r.finishedAt === undefined)
  await expect.poll(() => inflight() !== undefined).toBe(true)
  const slow = inflight()!
  await scrollBodyTo(page, 0)
  const facetsBefore = requests.filter((r) => r.path.endsWith('/timeline/facets')).length
  await deleteViaContextMenu(page, 'X06_00000.JPG')
  await expect(tileByName(page, 'X06_00000.JPG')).toHaveCount(0)
  const deleteRequest = requests.find((r) => r.method === 'DELETE')!
  expect(deleteRequest.path).toBe(`/api/v1/files/${60_000}`)
  // 删除时该月的慢请求确实还在途
  expect(slow.finishedAt === undefined || slow.finishedAt > deleteRequest.startedAt).toBe(true)

  await expect(subtitle).toHaveText(`共 ${count + 50 - 1} 张照片`)
  await expect(monthCount(page, 6)).toHaveText(String(count - 1))
  await expect(page.locator('.timeline-view .year-item .year-count')).toHaveText(String(count + 50 - 1))
  const after = await readLayout(page)
  expect(after.sections[0].rows).toBe(before.sections[0].rows - 1)
  expect(Math.abs(before.sections[0].height - after.sections[0].height - before.pitch)).toBeLessThan(0.01)
  await expect.poll(() => requests.filter((r) => r.path.endsWith('/timeline/facets')).length).toBeGreaterThan(facetsBefore)
  await expect(tileByName(page, 'X06_00001.JPG')).toBeInViewport()

  // 被 abort 的段重新请求后，该月没有重复文件名
  await scrollToIndex(page, 0, 210)
  await settle(page, requests)
  const names = await renderedNames(page)
  expect(new Set(names).size).toBe(names.length)
  expect(names).not.toContain('X06_00000.JPG')

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline delete after the slot was invalidated never removes another photo', async ({ page }) => {
  test.setTimeout(120_000)
  let insertArmed = false
  const { unknown, requests, files } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-07', 600, 70_000, 'S'),
    beforeFiles: (_url, _callIndex, data) => {
      if (!insertArmed) return
      insertArmed = false
      data.push(...topFiles('2025-07', 3, 79_000, 'NEW'))
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests) // 第 0～199 张（插入之前）

  // 跳到中部：offset 请求（插入之前）
  await scrollToIndex(page, 0, 450)
  await expect(tileByName(page, 'S_00450.JPG')).toBeInViewport()
  await settle(page, requests)
  const visibleBefore = (await namesInViewport(page)).filter((n) => n !== 'S_00450.JPG')
  expect(visibleBefore.length).toBeGreaterThan(5)

  await deleteViaContextMenu(page, 'S_00450.JPG', async () => {
    // 确认框打开期间：另一台设备往该月最前面插入 3 张；往上滚，先用 offset 拿到插入之后的一段，
    // 再从第 199 张做 keyset 续页——它的下标按插入之前算，和下面那段重叠，触发整月作废
    insertArmed = true
    const n = filesRequests(requests).length
    await scrollToIndex(page, 0, 300)
    await expect.poll(() => filesRequests(requests).length).toBeGreaterThan(n)
    await settle(page, requests)
    const m = filesRequests(requests).length
    await scrollToIndex(page, 0, 205)
    await expect.poll(() => filesRequests(requests).slice(m).some((r) => r.params.get('after_id') === String(70_000 + 199))).toBe(true)
    await settle(page, requests)
    expect(insertArmed).toBe(false)
  })
  await expect.poll(() => requests.some((r) => r.method === 'DELETE' && r.finishedAt !== undefined)).toBe(true)
  expect(files.some((f) => f.file_name === 'S_00450.JPG')).toBe(false)

  // 该月重新加载后，删除前可见的其他照片一张都没少，被删的不再出现
  const firstVisible = Math.min(...visibleBefore.map((n) => Number(n.slice(2, 7))))
  await scrollToIndex(page, 0, firstVisible + 3)
  await settle(page, requests)
  await expect.poll(async () => {
    const names = await renderedNames(page)
    return visibleBefore.filter((n) => !names.includes(n))
  }).toEqual([])
  expect(await renderedNames(page)).not.toContain('S_00450.JPG')

  // 滚到底完成对账：600 + 3 − 1 = 602
  await pageBody(page).evaluate((el) => { el.scrollTop = el.scrollHeight })
  await settle(page, requests)
  await expect(monthCount(page, 7)).toHaveText('602')
  await expect(page.locator('.timeline-view .pv-page-header .pv-muted')).toHaveText('共 602 张照片')
  const names = await renderedNames(page)
  expect(new Set(names).size).toBe(names.length)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline concurrent inserts never produce duplicate tiles', async ({ page }) => {
  test.setTimeout(120_000)
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-08', 600, 80_000, 'C'),
    // 第二次 files 请求之前，另一台设备往该月最前面插入 3 张
    beforeFiles: (_url, callIndex, data) => {
      if (callIndex === 1) data.push(...topFiles('2025-08', 3, 89_000, 'NEW'))
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)

  const expectNoDuplicates = async () => {
    const names = await renderedNames(page)
    expect(new Set(names).size, names.join(',')).toBe(names.length)
  }
  // 先跳到中部：offset 请求（插入之后）
  await scrollToIndex(page, 0, 360)
  await settle(page, requests)
  expect(filesRequests(requests)[1].params.get('offset')).not.toBeNull()
  await expectNoDuplicates()

  // 继续向下滚到底（keyset），再向上滚回月初附近：从第 199 张续页时与下面的段重叠
  const clientHeight = await pageBody(page).evaluate((el) => el.clientHeight)
  for (;;) {
    const atBottom = await pageBody(page).evaluate((el) => el.scrollTop + el.clientHeight >= el.scrollHeight - 1)
    if (atBottom) break
    await scrollBodyBy(page, clientHeight)
    await settle(page, requests)
    await expectNoDuplicates()
  }
  await expect(monthCount(page, 8)).toHaveText('603')
  for (;;) {
    const L = await readLayout(page)
    const top = await pageBody(page).evaluate((el) => el.scrollTop - el.querySelector<HTMLElement>('.photos-content')!.offsetTop)
    if (top <= L.headerBlock + Math.floor(205 / L.cols) * L.pitch) break
    await scrollBodyBy(page, -clientHeight / 2)
    await settle(page, requests)
    await expectNoDuplicates()
  }
  expect(filesRequests(requests).some((r) => r.params.get('after_id') === String(80_000 + 199))).toBe(true)
  await settle(page, requests)
  await expectNoDuplicates()
  await expect(monthCount(page, 8)).toHaveText('603')
  await expect(page.locator('.timeline-view .pv-page-header .pv-muted')).toHaveText('共 603 张照片')

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

// code-review 第 1 条的场景：keyset 冲突时返回段的下标已经过时，不能保留
test('Timeline keyset conflict after a concurrent insert refills the month by offset', async ({ page }) => {
  test.setTimeout(120_000)
  let insertArmed = false
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-09', 600, 90_000, 'K'),
    beforeFiles: (_url, _callIndex, data) => {
      if (!insertArmed) return
      insertArmed = false
      data.push(...topFiles('2025-09', 3, 99_000, 'NEW'))
    },
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests) // 槽位 0～199 = 插入之前的第 0～199 张（offset 0）
  expect(filesRequests(requests)).toHaveLength(1)

  // 下一次 files 请求之前，另一台设备往该月最前面插入 3 张；跳到中部，用 offset 拿到插入之后的一段
  insertArmed = true
  await scrollToIndex(page, 0, 400)
  await settle(page, requests)
  expect(insertArmed).toBe(false)
  const offset = Number(filesRequests(requests)[1].params.get('offset'))
  expect(offset).toBeGreaterThan(200)
  expect(offset).toBeLessThanOrEqual(400)

  // 从插入之前加载的第 199 张做 keyset 续页：它按插入之前的位置写入 200～399，与中部那段重叠 → 冲突
  const n = filesRequests(requests).length
  await scrollToIndex(page, 0, 205)
  await expect.poll(() => filesRequests(requests).slice(n).some((r) => r.params.get('after_id') === String(90_000 + 199))).toBe(true)
  await settle(page, requests)

  // 从头滚到尾：603 张每张都出现过，任何时刻都没有重复
  await scrollBodyTo(page, 0)
  await settle(page, requests)
  const clientHeight = await pageBody(page).evaluate((el) => el.clientHeight)
  const seen = new Set<string>()
  for (;;) {
    const names = await renderedNames(page)
    expect(new Set(names).size, names.join(',')).toBe(names.length)
    for (const name of names) seen.add(name)
    const atBottom = await pageBody(page).evaluate((el) => el.scrollTop + el.clientHeight >= el.scrollHeight - 1)
    if (atBottom) break
    await scrollBodyBy(page, clientHeight)
    await settle(page, requests)
  }
  const expected = [...topFiles('2025-09', 3, 99_000, 'NEW'), ...monthFiles('2025-09', 600, 90_000, 'K')].map((f) => f.file_name)
  expect([...seen].sort()).toEqual(expected.sort())
  await expect(monthCount(page, 9)).toHaveText('603')
  await expect(page.locator('.timeline-view .pv-page-header .pv-muted')).toHaveText('共 603 张照片')

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline falls back to the server time zone when the browser zone is rejected', async ({ page }) => {
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-06', 30, 60_000, 'TZ'),
    rejectTz: true,
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await expect(tileByName(page, 'TZ_00000.JPG')).toBeInViewport()
  await settle(page, requests)

  const timeline = requests.filter((r) => r.path.includes('/timeline/'))
  const firstWithoutTz = timeline.findIndex((r) => !r.params.has('tz'))
  expect(firstWithoutTz).toBeGreaterThan(0)
  expect(timeline.slice(0, firstWithoutTz).every((r) => r.status === 422)).toBe(true)
  expect(timeline.slice(firstWithoutTz).filter((r) => r.params.has('tz'))).toEqual([])
  expect(filesRequests(requests).length).toBeGreaterThan(0)
  expect(consoleIssues.warnings.filter((w) => w.includes('Server does not recognise browser time zone'))).toHaveLength(1)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline first-load failure shows a retry state instead of a blank body', async ({ page }) => {
  const { unknown } = await installApiMock(page, {
    timelineFiles: monthFiles('2025-06', 40, 60_000, 'R'),
    failSummary: (callIndex) => callIndex === 0,
  })
  const consoleIssues = collectConsoleIssues(page)
  await authenticate(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/timeline')

  const body = pageBody(page)
  await expect(body.locator('.el-empty__description')).toHaveText('加载时间线失败')
  const retry = body.getByRole('button', { name: '重试' })
  await expect(retry).toBeVisible()
  await expect(body.locator('.pv-tile')).toHaveCount(0)
  await expect(body.locator('.pv-loading')).toHaveCount(0)
  await page.screenshot({ path: `${timelineTaskScreenshotDir}/10-first-load-failed.png` })

  await retry.click()
  await expect(tileByName(page, 'R_00000.JPG')).toBeInViewport()
  await expectTilesMatchLayout(page)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline layout stays exact across empty results and viewport resizes', async ({ page }) => {
  const timelineFiles = [...monthFiles('2025-06', 30, 60_000, 'E06'), ...monthFiles('2025-05', 30, 50_000, 'E05')]
  const { unknown, requests } = await installApiMock(page, { timelineFiles })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await expectTilesMatchLayout(page)

  const searchInput = page.locator('.timeline-view .filename-search input')
  await searchInput.fill('zzz-nomatch')
  await searchInput.press('Enter')
  await expect(page.getByText('没有符合筛选条件的照片')).toBeVisible()
  await searchInput.fill('')
  await searchInput.press('Enter')
  await expect(tileByName(page, 'E06_00000.JPG')).toBeInViewport()
  await expectTilesMatchLayout(page)

  // .photos-content 被重建后再改视口宽度：ResizeObserver 观察的是常驻的 .pv-page-body
  await page.setViewportSize({ width: 1000, height: 900 })
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  await expectTilesMatchLayout(page)
  expect((await readLayout(page)).cols).toBeGreaterThanOrEqual(1)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline count reconciliation above the viewport does not shift visible photos', async ({ page }) => {
  test.setTimeout(90_000)
  let cols = 0
  let inserted = false
  const { unknown, requests } = await installApiMock(page, {
    timelineFiles: [...monthFiles('2025-08', 600, 80_000, 'A'), ...monthFiles('2025-07', 600, 70_000, 'B')],
    // A 末尾几行落在视口上方多渲染的那一屏里：处理这个 A 请求前往 A 最前面插入整整一行
    beforeFiles: (url, _callIndex, data) => {
      if (!inserted && cols > 0 && url.searchParams.get('month') === '2025-08' && Number(url.searchParams.get('offset') ?? 0) > 0) {
        inserted = true
        data.push(...topFiles('2025-08', cols, 89_000, 'NEWA'))
      }
    },
    // 并让 B 的请求先完成
    delayMs: (url) => (url.searchParams.get('month') === '2025-08' && Number(url.searchParams.get('offset') ?? 0) > 0 ? 1000 : 0),
  })
  const consoleIssues = collectConsoleIssues(page)
  await openTimeline(page)
  await settle(page, requests)
  cols = (await readLayout(page)).cols

  await page.locator('.timeline-view .month-item').filter({ hasText: /^7月/ }).click()
  const firstB = tileByName(page, 'B_00000.JPG')
  await expect(firstB).toBeInViewport()
  const topBefore = await firstB.evaluate((el) => el.getBoundingClientRect().top)
  expect(inserted).toBe(true)
  await expect(monthCount(page, 8)).toHaveText(String(600 + cols))
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  const topAfter = await firstB.evaluate((el) => el.getBoundingClientRect().top)
  expect(Math.abs(topAfter - topBefore)).toBeLessThanOrEqual(1)
  expect(Math.abs(await groupOffsetFromBodyTop(page, '2025年7月'))).toBeLessThanOrEqual(2)
  expect(filesRequests(requests, '2025-08').some((r) => Number(r.params.get('offset') ?? 0) > 0)).toBe(true)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

// ---------------------------------------------------------------------------
// 时间线：目录筛选、单日拍摄日期筛选、筛选状态写入 URL、两行筛选栏
// ---------------------------------------------------------------------------

const statsHeatmapScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-web-stats-heatmap-2026-10-19/screenshots'

/** 两个目录 + 子目录 + 另一个顶层目录；UTC_SHIFT 的拍摄日期（数据统计口径）与时间线日期不同。 */
const folderDayFiles: MockFile[] = [
  { ...galleryFile, id: 3001, file_name: 'DCIM_DAY_A.JPG', dir: 'Pixel 8/DCIM', exif_time: '2026-10-19T09:00:00', device_name: '测试手机' },
  { ...galleryFile, id: 3002, file_name: 'DCIM_DAY_B.JPG', dir: 'Pixel 8/DCIM', exif_time: '2026-10-19T18:00:00', device_name: '另一台手机' },
  { ...galleryFile, id: 3003, file_name: 'DCIM_OTHER_DAY.JPG', dir: 'Pixel 8/DCIM', exif_time: '2026-10-18T09:00:00', device_name: '测试手机' },
  { ...galleryFile, id: 3004, file_name: 'SUB_DAY.JPG', dir: 'Pixel 8/DCIM/Camera', exif_time: '2026-10-19T10:00:00', device_name: '测试手机' },
  { ...galleryFile, id: 3005, file_name: 'ROOT_DAY.JPG', dir: 'Pixel 8', exif_time: '2026-10-19T11:00:00', device_name: '测试手机' },
  { ...galleryFile, id: 3006, file_name: 'OTHER_DIR.JPG', dir: 'iPhone', exif_time: '2026-09-01T11:00:00', device_name: '测试手机' },
  { ...galleryFile, id: 3007, file_name: 'UTC_SHIFT.JPG', dir: 'Pixel 8/DCIM', exif_time: '2026-10-18T23:30:00', capture_date: '2026-10-19', device_name: '测试手机' },
]

const folderBrowseDirs = {
  '': [{ name: 'Pixel 8', path: 'Pixel 8' }, { name: 'iPhone', path: 'iPhone' }],
  'Pixel 8': [{ name: 'DCIM', path: 'Pixel 8/DCIM' }],
  'Pixel 8/DCIM': [{ name: 'Camera', path: 'Pixel 8/DCIM/Camera' }],
}

const summaryRequests = (requests: MockRequest[]) => requests.filter((r) => r.path.endsWith('/timeline/summary'))
const urlQuery = (page: Page) => new URL(page.url()).searchParams

function timelineFilterLocators(page: Page) {
  const view = page.locator('.timeline-view')
  return {
    names: view.locator('.pv-tile__name'),
    badge: view.locator('.filter-badge'),
    tag: view.locator('.capture-day-tag'),
    dir: view.locator('.dir-filter'),
    recursive: view.locator('.recursive-switch .el-switch'),
    device: view.locator('.filter-select').first(),
  }
}

/** 等 500ms，期间 summary 请求数不再变化，并返回该数量。 */
async function stableSummaryCount(page: Page, requests: MockRequest[]): Promise<number> {
  const before = summaryRequests(requests).length
  await page.waitForTimeout(500)
  expect(summaryRequests(requests).length).toBe(before)
  return before
}

test('Timeline deep link restores folder, capture-day and device filters and sends them to the server', async ({ page }) => {
  test.setTimeout(60_000)
  const { unknown, requests } = await installApiMock(page, { timelineFiles: folderDayFiles, browseDirs: folderBrowseDirs })
  const consoleIssues = collectConsoleIssues(page)
  await authenticate(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/timeline?day=2026-10-19&path=Pixel%208/DCIM&recursive=0&device=测试手机')
  const ui = timelineFilterLocators(page)

  // 控件从 URL 恢复
  await expect(ui.tag).toHaveText('拍摄日期：2026年10月19日')
  await expect(ui.dir).toContainText('DCIM')
  await expect(ui.dir.locator('input')).toHaveAttribute('aria-label', '选择目录')
  await expect(ui.recursive).not.toHaveClass(/is-checked/)
  await expect(ui.recursive).not.toHaveClass(/is-disabled/)
  await expect(ui.device).toContainText('测试手机')
  await expect(ui.badge).toContainText('已筛选 3 项 · 2/7 张')
  // 只有 DCIM 目录本身（不含 Camera 子目录）、测试手机、拍摄日期 10 月 19 日（UTC_SHIFT 按统计口径属于 19 日）
  await expect(ui.names).toHaveText(['DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG'])

  // 第一次 summary 就带上全部条件，没有先发一次不带筛选的请求
  expect(await stableSummaryCount(page, requests)).toBe(1)
  const dataRequests = requests.filter((r) => /\/timeline\/(summary|files)$/.test(r.path))
  expect(dataRequests.length).toBeGreaterThanOrEqual(2)
  for (const r of dataRequests) {
    expect(r.params.get('capture_date'), r.search).toBe('2026-10-19')
    expect(r.params.get('path'), r.search).toBe('Pixel 8/DCIM')
    expect(r.params.get('recursive'), r.search).toBe('false')
    expect(r.params.getAll('device'), r.search).toEqual(['测试手机'])
  }

  // 拍摄日期标签的提示说明筛选口径
  await ui.tag.hover()
  await expect(page.getByText('按数据统计口径（拍摄时间、当地时间）筛选')).toBeVisible()

  // 关闭标签：URL 去掉 day，下一次请求不再带 capture_date，其余条件保留；只重新请求一次
  const before = summaryRequests(requests).length
  await ui.tag.locator('.el-tag__close').click()
  await expect(ui.tag).toHaveCount(0)
  await expect.poll(() => urlQuery(page).has('day')).toBe(false)
  expect(urlQuery(page).get('path')).toBe('Pixel 8/DCIM')
  expect(urlQuery(page).get('recursive')).toBe('0')
  expect(urlQuery(page).getAll('device')).toEqual(['测试手机'])
  await expect(ui.badge).toContainText('已筛选 2 项 · 3/7 张')
  await expect(ui.names).toHaveText(['DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG', 'DCIM_OTHER_DAY.JPG'])
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  const last = summaryRequests(requests).at(-1)!
  expect(last.params.has('capture_date')).toBe(false)
  expect(last.params.get('path')).toBe('Pixel 8/DCIM')
  expect(last.params.get('recursive')).toBe('false')
  expect(filesRequests(requests).at(-1)!.params.has('capture_date')).toBe(false)

  // 刷新页面：同样的筛选从 URL 恢复
  await page.reload()
  await expect(ui.names).toHaveText(['DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG', 'DCIM_OTHER_DAY.JPG'])
  await expect(ui.tag).toHaveCount(0)
  await expect(ui.recursive).not.toHaveClass(/is-checked/)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline folder select and 包含子目录 switch update the URL and requests; 清除筛选 empties the URL', async ({ page }) => {
  test.setTimeout(60_000)
  const { unknown, requests } = await installApiMock(page, { timelineFiles: folderDayFiles, browseDirs: folderBrowseDirs })
  const consoleIssues = collectConsoleIssues(page)
  await authenticate(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/timeline')
  const ui = timelineFilterLocators(page)
  await expect(ui.names).toHaveCount(7)

  // 根目录：包含子目录恒为开且不可切换
  await expect(ui.dir).toContainText('全部')
  await expect(ui.recursive).toHaveClass(/is-checked/)
  await expect(ui.recursive).toHaveClass(/is-disabled/)
  await ui.recursive.hover()
  await expect(page.getByText('根目录始终包含全部文件')).toBeVisible()

  // 选择目录 Pixel 8：URL 写入 path（replace，不新增历史），请求带 path、不带 recursive
  const historyLength = await page.evaluate(() => history.length)
  let before = await stableSummaryCount(page, requests)
  await ui.dir.locator('.el-select__wrapper').click()
  await page.locator('.el-tree-node__content').filter({ hasText: /^Pixel 8$/ }).click()
  await expect.poll(() => urlQuery(page).get('path')).toBe('Pixel 8')
  expect(urlQuery(page).has('recursive')).toBe(false)
  await expect(ui.dir).toContainText('Pixel 8')
  await expect(ui.badge).toContainText('已筛选 1 项 · 6/7 张')
  await expect(ui.recursive).toHaveClass(/is-checked/)
  await expect(ui.recursive).not.toHaveClass(/is-disabled/)
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  expect(summaryRequests(requests).at(-1)!.params.get('path')).toBe('Pixel 8')
  expect(summaryRequests(requests).at(-1)!.params.has('recursive')).toBe(false)
  expect(await page.evaluate(() => history.length)).toBe(historyLength)

  // 关掉包含子目录：URL recursive=0，请求 recursive=false，只剩目录本身的文件
  before = summaryRequests(requests).length
  await ui.recursive.click()
  await expect.poll(() => urlQuery(page).get('recursive')).toBe('0')
  await expect(ui.names).toHaveText(['ROOT_DAY.JPG'])
  await expect(ui.badge).toContainText('已筛选 1 项 · 1/7 张')
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  expect(summaryRequests(requests).at(-1)!.params.get('recursive')).toBe('false')
  expect(filesRequests(requests).at(-1)!.params.get('path')).toBe('Pixel 8')
  expect(filesRequests(requests).at(-1)!.params.get('recursive')).toBe('false')

  // 再选一个子目录（展开 Pixel 8 → DCIM）：保留不含子目录
  before = summaryRequests(requests).length
  await ui.dir.locator('.el-select__wrapper').click()
  const pixelNode = page.locator('.el-tree-node').filter({ has: page.locator('> .el-tree-node__content', { hasText: /^Pixel 8$/ }) })
  await pixelNode.locator('> .el-tree-node__content .el-tree-node__expand-icon').click()
  await page.locator('.el-tree-node__content').filter({ hasText: /^DCIM$/ }).click()
  await expect.poll(() => urlQuery(page).get('path')).toBe('Pixel 8/DCIM')
  expect(urlQuery(page).get('recursive')).toBe('0')
  await expect(ui.names).toHaveText(['DCIM_DAY_B.JPG', 'DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG', 'DCIM_OTHER_DAY.JPG'])
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)

  // 清除筛选：URL query 清空，回到根目录、包含子目录
  before = summaryRequests(requests).length
  await page.getByRole('button', { name: /清除筛选/ }).click()
  await expect.poll(() => new URL(page.url()).search).toBe('')
  await expect(ui.names).toHaveCount(7)
  await expect(ui.badge).toBeHidden()
  await expect(ui.dir).toContainText('全部')
  await expect(ui.recursive).toHaveClass(/is-checked/)
  await expect(ui.recursive).toHaveClass(/is-disabled/)
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  const last = summaryRequests(requests).at(-1)!
  expect(last.params.has('path')).toBe(false)
  expect(last.params.has('recursive')).toBe(false)

  expect(consoleIssues.duplicateKeys).toEqual([])
  expect(unknown).toEqual([])
})

test('Timeline browser back / forward re-applies the filters stored in the URL', async ({ page }) => {
  test.setTimeout(60_000)
  const { unknown, requests } = await installApiMock(page, { timelineFiles: folderDayFiles })
  await authenticate(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  // 状态 A：深链接
  await page.goto('/timeline?device=另一台手机')
  const ui = timelineFilterLocators(page)
  await expect(ui.names).toHaveText(['DCIM_DAY_B.JPG'])

  // 侧边栏「时间线」是一次 push（新的历史记录），然后在控件上改成状态 B（replace 当前记录）
  await page.locator('.el-menu-item').filter({ hasText: '时间线' }).click()
  await expect(ui.names).toHaveCount(7)
  await expect(ui.badge).toBeHidden()
  await ui.device.click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: '测试手机' }).click()
  await page.keyboard.press('Escape')
  await expect.poll(() => urlQuery(page).getAll('device')).toEqual(['测试手机'])
  await expect(ui.names).toHaveCount(6)

  // 后退：回到状态 A，控件和请求都跟着恢复，只请求一次
  let before = await stableSummaryCount(page, requests)
  await page.goBack()
  await expect.poll(() => urlQuery(page).getAll('device')).toEqual(['另一台手机'])
  await expect(ui.names).toHaveText(['DCIM_DAY_B.JPG'])
  await expect(ui.device).toContainText('另一台手机')
  await expect(ui.device).not.toContainText('测试手机')
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  expect(summaryRequests(requests).at(-1)!.params.getAll('device')).toEqual(['另一台手机'])

  // 前进：回到状态 B
  before = summaryRequests(requests).length
  await page.goForward()
  await expect.poll(() => urlQuery(page).getAll('device')).toEqual(['测试手机'])
  await expect(ui.names).toHaveCount(6)
  await expect(ui.device).toContainText('测试手机')
  expect(await stableSummaryCount(page, requests)).toBe(before + 1)
  expect(summaryRequests(requests).at(-1)!.params.getAll('device')).toEqual(['测试手机'])

  expect(unknown).toEqual([])
})

/** 两行筛选栏的几何信息：卡片内边距内的区域、每行及其中控件、是否有横向溢出。 */
async function measureFilterBar(page: Page) {
  return page.evaluate(() => {
    const rect = (element: Element) => {
      const r = element.getBoundingClientRect()
      return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
    }
    const card = document.querySelector('.timeline-view .pv-page-header')!
    const style = getComputedStyle(card)
    const r = rect(card)
    const head = document.querySelector<HTMLElement>('.timeline-view .pv-page-head')!
    const tag = document.querySelector<HTMLElement>('.timeline-view .capture-day-tag .el-tag__content')
    return {
      inner: {
        left: r.left + Number.parseFloat(style.paddingLeft),
        right: r.right - Number.parseFloat(style.paddingRight),
        top: r.top + Number.parseFloat(style.paddingTop),
        bottom: r.bottom - Number.parseFloat(style.paddingBottom),
      },
      title: rect(document.querySelector('.timeline-view .pv-page-header__left')!),
      filters: rect(document.querySelector('.timeline-view .tl-filters')!),
      rows: [...document.querySelectorAll('.timeline-view .tl-filter-row')].map((row) => ({
        box: rect(row),
        children: [...row.children].map(rect),
      })),
      overflow: {
        doc: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        head: head.scrollWidth - head.clientWidth,
        card: card.scrollWidth - card.clientWidth,
      },
      tagClipped: tag ? tag.scrollWidth > tag.clientWidth : false,
      bg: getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color').trim(),
    }
  })
}

function expectFilterBarInsideCard(m: Awaited<ReturnType<typeof measureFilterBar>>, label: string) {
  const tol = 0.5
  expect.soft(m.rows.length, `${label} rows`).toBe(2)
  for (const [i, row] of m.rows.entries()) {
    for (const [j, b] of [row.box, ...row.children].entries()) {
      const what = j === 0 ? `${label} row ${i + 1}` : `${label} row ${i + 1} child ${j - 1}`
      expect.soft(b.left, `${what} left`).toBeGreaterThanOrEqual(m.inner.left - tol)
      expect.soft(b.right, `${what} right`).toBeLessThanOrEqual(m.inner.right + tol)
      expect.soft(b.top, `${what} top`).toBeGreaterThanOrEqual(m.inner.top - tol)
      expect.soft(b.bottom, `${what} bottom`).toBeLessThanOrEqual(m.inner.bottom + tol)
    }
  }
  // 第 1 行在第 2 行上方，不重叠
  expect.soft(m.rows[0].box.bottom, `${label} row order`).toBeLessThanOrEqual(m.rows[1].box.top + tol)
  expect.soft(m.overflow, `${label} horizontal overflow`).toEqual({ doc: 0, head: 0, card: 0 })
  expect.soft(m.tagClipped, `${label} capture-day tag clipped`).toBe(false)
}

test('Timeline two-row filter bar stays inside the header card in light and simulated dark themes', async ({ page }) => {
  test.setTimeout(120_000)
  mkdirSync(statsHeatmapScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { timelineFiles: folderDayFiles, browseDirs: folderBrowseDirs })
  await authenticate(page)
  const head = page.locator('.timeline-view .pv-page-head')
  const ui = timelineFilterLocators(page)

  for (const theme of ['light', 'dark'] as const) {
    for (const width of [1920, 1440, 1024, 800]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/timeline')
      // 窄视口下标题栏更高，窗口化渲染只画出视口里的瓦片，所以看副标题的总数
      await expect(page.locator('.timeline-view .pv-page-header .pv-muted')).toHaveText('共 7 张照片')
      await expect(ui.names.first()).toBeVisible()
      if (theme === 'dark') {
        await page.evaluate(() => document.documentElement.classList.add('dark'))
        await page.addStyleTag({ url: '/node_modules/element-plus/theme-chalk/dark/css-vars.css' })
      }
      await page.waitForTimeout(300)
      const m = await measureFilterBar(page)
      const label = `${width} ${theme}`
      console.log(label, JSON.stringify(m))
      // 暗色模拟确实生效（Element Plus 暗色变量 --el-bg-color: #141414）
      if (theme === 'dark') expect(m.bg, `${label} theme variables`).toBe('#141414')
      else expect(m.bg, `${label} theme variables`).not.toBe('#141414')
      expectFilterBarInsideCard(m, label)
      if (width >= 1440) {
        // 宽屏：标题在左，两行各自排成一行
        expect.soft(m.title.right, `${label} title beside filters`).toBeLessThan(m.filters.left)
        for (const [i, row] of m.rows.entries()) {
          const center = (b: { top: number; height: number }) => b.top + b.height / 2
          for (const child of row.children) {
            expect.soft(Math.abs(center(child) - center(row.children[0])), `${label} row ${i + 1} single line`).toBeLessThanOrEqual(1)
          }
        }
      }
      await head.screenshot({ path: `${statsHeatmapScreenshotDir}/timeline-filters-${width}-${theme}.png` })
    }
  }

  // 目录 + 拍摄日期 + 设备同时生效
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/timeline?day=2026-10-19&path=Pixel%208/DCIM&device=测试手机')
  await expect(ui.tag).toHaveText('拍摄日期：2026年10月19日')
  await expect(ui.names).toHaveText(['SUB_DAY.JPG', 'DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG'])
  await page.waitForTimeout(300)
  const m = await measureFilterBar(page)
  console.log('1440 active', JSON.stringify(m))
  expectFilterBarInsideCard(m, '1440 active')
  expect.soft(m.title.right, '1440 active title beside filters').toBeLessThan(m.filters.left)
  await head.screenshot({ path: `${statsHeatmapScreenshotDir}/timeline-filters-active-1440-light.png` })

  expect(unknown).toEqual([])
})

// ---------------------------------------------------------------------------
// 数据统计「每日文件数量」热力图
// ---------------------------------------------------------------------------

/**
 * 确定性的每日计数：2025-01-01 … 2026-10-17 按公式生成 0–8（0 的日子不出现在 days 里），
 * 另有离群的一天 2025-07-04 = 300 和 today 2026-10-19 = 12；2026-10-18 没有文件；2024 年没有数据。
 */
function buildDailyFixture(): Record<string, number> {
  const days: Record<string, number> = {}
  for (let ms = Date.UTC(2025, 0, 1), i = 0; ms <= Date.UTC(2026, 9, 17); ms += 86_400_000, i++) {
    const v = (i * 7 + (i % 5) * 3) % 11
    if (v >= 3) days[new Date(ms).toISOString().slice(0, 10)] = v - 2
  }
  days['2025-07-04'] = 300
  days['2026-10-19'] = 12
  return days
}

const dailyDays = buildDailyFixture()
const sumDays = (pick: (day: string) => boolean) =>
  Object.entries(dailyDays).filter(([day]) => pick(day)).reduce((n, [, c]) => n + c, 0)
/** 最近一年 = 2025-10-20 … 2026-10-19（直接按字符串比较，不经过 heatmapModel） */
const recentTotal = sumDays((d) => d >= '2025-10-20' && d <= '2026-10-19')
const year2025Total = sumDays((d) => d.startsWith('2025-'))

const statsResponseWithDaily = {
  ...statsResponse,
  overview: {
    ...statsResponse.overview,
    daily: {
      kind: 'calendar',
      today: '2026-10-19',
      days: dailyDays,
      unknown: 3,
      total: sumDays(() => true) + 3,
    },
  },
}

test('heatmapModel computes thresholds, grid, month labels and texts (Node side)', () => {
  // 四分位分界
  expect(computeThresholds([])).toEqual([0, 0, 0])
  expect(computeThresholds([0, 0])).toEqual([0, 0, 0])
  expect(computeThresholds([1, 2, 3, 4, 5, 6, 7, 8])).toEqual([2, 4, 6])
  const uniform = computeThresholds([5, 5, 5, 5, 0])
  expect(uniform).toEqual([5, 5, 5])
  expect(levelOf(5, uniform)).toBe(1)
  const outlier = computeThresholds([...Array(9).fill(1), 300])
  expect(outlier).toEqual([1, 1, 1])
  expect(levelOf(300, outlier)).toBe(4)
  expect(levelOf(1, outlier)).toBe(1)
  expect([0, 1, 2, 3, 4, 5, 6, 7, 8].map((c) => levelOf(c, [2, 4, 6]))).toEqual([0, 1, 1, 2, 2, 3, 3, 4, 4])

  // 范围：最近一年 / 闰日 / 公历年
  const day = (ms: number) => new Date(ms).toISOString().slice(0, 10)
  const bounds = (today: string, range: 'recent' | number) => {
    const b = heatmapBounds(today, range)
    return [day(b.start), day(b.end)]
  }
  expect(bounds('2026-10-19', 'recent')).toEqual(['2025-10-20', '2026-10-19'])
  expect(bounds('2024-02-29', 'recent')).toEqual(['2023-03-02', '2024-02-29'])
  expect(bounds('2025-02-28', 'recent')).toEqual(['2024-02-29', '2025-02-28'])
  expect(bounds('2025-03-01', 'recent')).toEqual(['2024-03-02', '2025-03-01'])
  expect(bounds('2026-10-19', 2024)).toEqual(['2024-01-01', '2024-12-31'])
  expect(parseDay('2026-10-19')).toBe(Date.UTC(2026, 9, 19))

  // 最近一年的网格：每列 7 格、53–54 列、范围外为 null、周一在第 0 行
  for (const today of ['2026-10-19', '2026-10-18', '2024-02-29', '2025-12-31', '2026-01-04']) {
    const m = buildHeatmap(dailyDays, today, 'recent')
    expect(m.weeks.length, today).toBeGreaterThanOrEqual(53)
    expect(m.weeks.length, today).toBeLessThanOrEqual(54)
    for (const week of m.weeks) expect(week.length).toBe(7)
    const cells = m.weeks.flat().filter((c) => c !== null)
    expect(cells[0]!.date, today).toBe(m.start)
    expect(cells.at(-1)!.date, today).toBe(today)
    expect(cells.length, today).toBe(Math.round((parseDay(today) - parseDay(m.start)) / 86_400_000) + 1)
  }
  const recent = buildHeatmap(dailyDays, '2026-10-19', 'recent')
  expect(recent.weeks.length).toBe(53)
  expect(recent.total).toBe(recentTotal)
  expect(recent.weeks[0][0]!.date).toBe('2025-10-20')
  expect(recent.weeks[52][0]).toMatchObject({ date: '2026-10-19', count: 12, level: 4, label: '2026年10月19日：12 个文件' })
  expect(recent.weeks[52].slice(1)).toEqual([null, null, null, null, null, null])
  expect(recent.weeks[51][6]).toMatchObject({ date: '2026-10-18', count: 0, level: 0, label: '2026年10月18日：无文件' })
  // 月份标签：每月 1 日所在的列，间隔 ≥ 3 列。起点 2025-10-20 所在的第 0 列离 11 月 1 日（第 1 列）
  // 不足 3 列，所以第 0 列不标「10月」
  expect(recent.monthLabels.map((l) => l.label)).toEqual(['11月', '12月', '1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月'])
  expect(recent.monthLabels[0].col).toBe(1)
  for (let i = 1; i < recent.monthLabels.length; i++) {
    expect(recent.monthLabels[i].col - recent.monthLabels[i - 1].col).toBeGreaterThanOrEqual(3)
  }
  // 起点 2025-10-10：11 月 1 日在第 3 列，第 0 列标上起点所在的 10 月
  expect(buildHeatmap({}, '2026-10-09', 'recent').monthLabels.slice(0, 2)).toEqual([{ col: 0, label: '10月' }, { col: 3, label: '11月' }])
  // 起点当周就有 1 日：标签就在第 0 列
  expect(buildHeatmap({}, '2026-10-30', 'recent').monthLabels[0]).toEqual({ col: 0, label: '11月' })

  // 公历年：1 月 1 日 … 12 月 31 日，1月 … 12月 依次出现
  const y2025 = buildHeatmap(dailyDays, '2026-10-19', 2025)
  expect(y2025.start).toBe('2025-01-01')
  expect(y2025.end).toBe('2025-12-31')
  expect(y2025.total).toBe(year2025Total)
  expect(y2025.weeks.length).toBe(53)
  expect(y2025.weeks.flat().filter((c) => c !== null).length).toBe(365)
  expect(y2025.monthLabels.map((l) => l.label)).toEqual(Array.from({ length: 12 }, (_, i) => `${i + 1}月`))
  for (let i = 1; i < 12; i++) expect(y2025.monthLabels[i].col).toBeGreaterThan(y2025.monthLabels[i - 1].col + 2)
  expect(y2025.weeks.flat().find((c) => c?.date === '2025-07-04')).toMatchObject({ count: 300, level: 4 })
  // 当年：today 之后不画，但网格和月份标签仍覆盖全年
  const y2026 = buildHeatmap(dailyDays, '2026-10-19', 2026)
  expect(y2026.drawEnd).toBe('2026-10-19')
  expect(y2026.weeks.flat().filter((c) => c !== null).at(-1)!.date).toBe('2026-10-19')
  expect(y2026.monthLabels.map((l) => l.label)).toEqual(Array.from({ length: 12 }, (_, i) => `${i + 1}月`))

  // 文案、年份、按月汇总
  expect(formatDayTooltip('2026-10-19', 12)).toBe('2026年10月19日：12 个文件')
  expect(formatDayTooltip('2026-01-05', 1234)).toBe('2026年1月5日：1,234 个文件')
  expect(formatDayTooltip('2026-10-18', 0)).toBe('2026年10月18日：无文件')
  expect(rangeSummary('recent', 1234)).toBe('过去一年共 1,234 个文件')
  expect(rangeSummary(2025, 7)).toBe('2025年共 7 个文件')
  expect(availableYears(dailyDays)).toEqual([2026, 2025])
  expect(availableYears({ '2024-01-01': 0, '2023-05-01': 2 })).toEqual([2023])
  const rows = monthlyRows(recent)
  expect(rows.map((r) => r.label)).toEqual([
    '2025年10月', '2025年11月', '2025年12月', '2026年1月', '2026年2月', '2026年3月', '2026年4月',
    '2026年5月', '2026年6月', '2026年7月', '2026年8月', '2026年9月', '2026年10月',
  ])
  expect(rows.reduce((n, r) => n + r.count, 0)).toBe(recentTotal)
  expect(rows[1].count).toBe(sumDays((d) => d.startsWith('2025-11-')))
})

const heatmap = (page: Page) => page.locator('.stat-heatmap')
const heatCell = (page: Page, date: string) => heatmap(page).locator(`rect[data-date="${date}"]`)
const heatTip = (page: Page) => page.locator('.stat-heatmap-tip')

async function openStatistics(page: Page, url = '/statistics', width = 1440, height = 900) {
  await authenticate(page)
  await page.setViewportSize({ width, height })
  await page.goto(url)
  await expect(heatmap(page).locator('figcaption')).toHaveText('每日文件数量')
}

test('Statistics 每日文件数量 heatmap shows Chinese labels, tooltip, legend and the year selector', async ({ page }) => {
  test.setTimeout(60_000)
  mkdirSync(statsHeatmapScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { statsResponse: statsResponseWithDaily })
  await openStatistics(page)
  const card = heatmap(page)
  const summary = card.locator('.stat-heatmap__summary')

  await expect(summary).toHaveText(`过去一年共 ${recentTotal.toLocaleString()} 个文件`)
  const labels = await card.locator('text.stat-heatmap__label').allTextContents()
  expect(labels.filter((t) => t.startsWith('周'))).toEqual(['周一', '周三', '周五'])
  expect(labels.filter((t) => t.endsWith('月'))).toEqual(['11月', '12月', '1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月'])
  await expect(card.locator('.stat-heatmap__legend')).toHaveText(/^\s*少\s*多\s*$/)
  await expect(card.locator('.stat-heatmap__swatch')).toHaveCount(5)
  // 卡片上不显示说明文字（只在「查看数据」弹窗里）
  await expect(card.getByText('按拍摄日期（当地时间）统计照片和视频；点击日期可在时间线中查看')).toHaveCount(0)
  await expect(card.locator('.stat-chart-note')).toHaveCount(0)

  // 「查看数据」和图例同一行：按钮在左、图例在右，垂直居中对齐
  const dataBox = await card.getByRole('button', { name: '查看数据' }).boundingBox()
  const legendBox = await card.locator('.stat-heatmap__legend').boundingBox()
  expect(dataBox && legendBox).toBeTruthy()
  expect(dataBox!.x + dataBox!.width).toBeLessThan(legendBox!.x)
  expect(Math.abs((dataBox!.y + dataBox!.height / 2) - (legendBox!.y + legendBox!.height / 2))).toBeLessThan(2)

  // 7 行（周一在上）× 53 列
  const grid = await card.locator('rect[data-date]').evaluateAll((rects) => ({
    xs: new Set(rects.map((r) => r.getAttribute('x'))).size,
    ys: new Set(rects.map((r) => r.getAttribute('y'))).size,
    count: rects.length,
    levels: [...new Set(rects.map((r) => r.getAttribute('class')!.match(/lv-(\d)/)![1]))].sort(),
  }))
  expect(grid).toEqual({ xs: 53, ys: 7, count: 365, levels: ['0', '1', '2', '3', '4'] })
  const mondayY = await heatCell(page, '2026-10-19').getAttribute('y')
  expect(await heatCell(page, '2026-10-12').getAttribute('y')).toBe(mondayY)
  expect(Number(await heatCell(page, '2026-10-18').getAttribute('y'))).toBeGreaterThan(Number(mondayY))
  // 浅色色板：12 个文件是最深的一级
  await expect(heatCell(page, '2026-10-19')).toHaveClass(/lv-4/)
  expect(await heatCell(page, '2026-10-19').evaluate((el) => getComputedStyle(el).fill)).toBe(rgb('#216E39'))
  expect(await heatCell(page, '2026-10-18').evaluate((el) => getComputedStyle(el).fill)).toBe(rgb('#EBEDF0'))
  await expect(heatCell(page, '2026-10-19')).toHaveAttribute('aria-label', '2026年10月19日：12 个文件，按 Enter 在时间线中查看')
  await expect(heatCell(page, '2026-10-18')).toHaveAttribute('aria-label', '2026年10月18日：无文件')
  // 最近一年滚到最右（宽屏下不需要滚动时为 0）
  const scroll = await card.locator('.stat-heatmap__scroll').evaluate((el) => el.scrollWidth - el.clientWidth - el.scrollLeft)
  expect(scroll).toBeLessThanOrEqual(1)
  await card.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-light.png` })

  // 悬停提示
  await heatCell(page, '2026-10-19').hover()
  await expect(heatTip(page)).toBeVisible()
  await expect(heatTip(page)).toHaveText('2026年10月19日：12 个文件')
  await page.waitForTimeout(300)
  await card.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-light-tooltip.png` })
  await heatCell(page, '2026-10-18').hover()
  await expect(heatTip(page)).toHaveText('2026年10月18日：无文件')
  await page.mouse.move(0, 0)
  await expect(heatTip(page)).toBeHidden()

  // 「统计时排除未知项」对热力图没有影响，卡片里也没有「未知」
  const snapshot = () => card.evaluate((el) => ({ text: (el as HTMLElement).innerText, svg: el.querySelector('svg')!.outerHTML }))
  const before = await snapshot()
  expect(before.text).not.toContain('未知')
  const unknownSwitch = page.locator('.unknown-switch .el-switch')
  await expect(unknownSwitch).toHaveClass(/is-checked/)
  await unknownSwitch.click()
  await expect(unknownSwitch).not.toHaveClass(/is-checked/)
  await expect.poll(() => urlQuery(page).get('unknown')).toBe('1')
  await page.waitForTimeout(200)
  expect(await snapshot()).toEqual(before)

  // 年份选择器：最近一年 + 有数据的年份（没有 2024年）
  const select = card.locator('.stat-heatmap__range')
  await expect(select.locator('input')).toHaveAttribute('aria-label', '选择统计年份')
  await select.click()
  const options = page.locator('.el-select-dropdown__item:visible')
  await expect(options).toHaveText(['最近一年', '2026年', '2025年'])
  await options.filter({ hasText: '2025年' }).click()
  await expect(summary).toHaveText(`2025年共 ${year2025Total.toLocaleString()} 个文件`)
  await expect(card.locator('rect[data-date]')).toHaveCount(365)
  await expect(heatCell(page, '2025-01-01')).toHaveCount(1)
  await expect(heatCell(page, '2026-01-01')).toHaveCount(0)
  await expect(heatCell(page, '2025-07-04')).toHaveClass(/lv-4/)
  expect(await card.locator('.stat-heatmap__scroll').evaluate((el) => el.scrollLeft)).toBe(0)
  const yearLabels = await card.locator('text.stat-heatmap__label').allTextContents()
  expect(yearLabels.filter((t) => t.endsWith('月'))).toEqual(Array.from({ length: 12 }, (_, i) => `${i + 1}月`))
  await page.mouse.move(0, 0)
  await page.waitForTimeout(300)
  await card.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-year-2025.png` })

  // 「查看数据」：按月汇总 + 合计，没有「未知」行
  await card.getByRole('button', { name: '查看数据' }).click()
  const dialog = page.getByRole('dialog', { name: '每日文件数量数据' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('th')).toHaveText(['月份', '文件数'])
  const body = dialog.locator('tbody tr')
  await expect(body).toHaveCount(13)
  await expect(body.first()).toHaveText(/2025年1月\s*\d+/)
  await expect(body.last()).toHaveText(new RegExp(`合计\\s*${year2025Total}`))
  await expect(dialog).not.toContainText('未知')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()

  expect(unknown).toEqual([])
})

test('Statistics heatmap uses the dark palette under html.dark and scrolls inside the card at 375px', async ({ page }) => {
  test.setTimeout(60_000)
  mkdirSync(statsHeatmapScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { statsResponse: statsResponseWithDaily })
  await openStatistics(page)
  const card = heatmap(page)

  // 模拟暗色：html.dark + Element Plus 暗色变量
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await page.addStyleTag({ url: '/node_modules/element-plus/theme-chalk/dark/css-vars.css' })
  await page.waitForTimeout(300)
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color').trim())).toBe('#141414')
  expect(await heatCell(page, '2026-10-19').evaluate((el) => getComputedStyle(el).fill)).toBe('rgb(57, 211, 83)')
  expect(await heatCell(page, '2026-10-18').evaluate((el) => getComputedStyle(el).fill)).toBe(rgb('#2A2E35'))
  const darkColors = await card.evaluate((el) => ({
    background: getComputedStyle(el).backgroundColor,
    overlay: getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color-overlay').trim(),
    label: getComputedStyle(el.querySelector('text.stat-heatmap__label')!).fill,
    regular: getComputedStyle(document.documentElement).getPropertyValue('--el-text-color-regular').trim(),
  }))
  expect(darkColors.background).toBe(rgb(darkColors.overlay.toUpperCase()))
  expect(darkColors.label).toBe(rgb(darkColors.regular.toUpperCase()))
  await heatCell(page, '2026-10-19').hover()
  await expect(heatTip(page)).toHaveText('2026年10月19日：12 个文件')
  await page.waitForTimeout(300)
  await card.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-dark.png` })

  // 375px：页面不出现横向滚动，网格在自己的容器里滚动（最近一年滚到最右）
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/statistics')
  await expect(card.locator('figcaption')).toHaveText('每日文件数量')
  await card.scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  const narrow = await page.evaluate(() => {
    const wrap = document.querySelector<HTMLElement>('.stat-heatmap__scroll')!
    return {
      doc: document.documentElement.scrollWidth,
      inner: window.innerWidth,
      scrollWidth: wrap.scrollWidth,
      clientWidth: wrap.clientWidth,
      remaining: wrap.scrollWidth - wrap.clientWidth - wrap.scrollLeft,
    }
  })
  expect(narrow.doc).toBeLessThanOrEqual(narrow.inner)
  expect(narrow.scrollWidth).toBeGreaterThan(narrow.clientWidth)
  expect(narrow.remaining).toBeLessThanOrEqual(1)
  // 今天的格子在可见区域内，图例没有被挤出卡片
  const cardBox = (await card.boundingBox())!
  const todayBox = (await heatCell(page, '2026-10-19').boundingBox())!
  expect(todayBox.x + todayBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width)
  // 标题不被年份选择器挤成多行
  expect((await card.locator('figcaption').boundingBox())!.height).toBeLessThan(24)
  const legendBox = (await card.locator('.stat-heatmap__legend').boundingBox())!
  expect(legendBox.x + legendBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width)
  await page.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-narrow-375.png`, fullPage: true })

  expect(unknown).toEqual([])
})

test('Statistics heatmap day opens the timeline filtered to that day and the current folder', async ({ page }) => {
  test.setTimeout(60_000)
  mkdirSync(statsHeatmapScreenshotDir, { recursive: true })
  const { unknown, requests } = await installApiMock(page, {
    statsResponse: statsResponseWithDaily,
    timelineFiles: folderDayFiles,
    browseDirs: folderBrowseDirs,
  })
  await openStatistics(page, '/statistics?path=Pixel%208/DCIM&recursive=0')

  // 没有文件的日子不可点击（aria-disabled，Playwright 默认不点 disabled 元素，这里强制点一下）
  await expect(heatCell(page, '2026-10-18')).toHaveCSS('cursor', 'auto')
  await expect(heatCell(page, '2026-10-19')).toHaveCSS('cursor', 'pointer')
  await heatCell(page, '2026-10-18').click({ force: true })
  await page.waitForTimeout(300)
  expect(new URL(page.url()).pathname).toBe('/statistics')

  // 点击有文件的日子：跳到时间线，带上日期和目录范围
  await heatCell(page, '2026-10-19').click()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/timeline')
  expect(Object.fromEntries(urlQuery(page))).toEqual({ day: '2026-10-19', path: 'Pixel 8/DCIM', recursive: '0' })
  const ui = timelineFilterLocators(page)
  await expect(ui.tag).toHaveText('拍摄日期：2026年10月19日')
  await expect(ui.dir).toContainText('DCIM')
  await expect(ui.recursive).not.toHaveClass(/is-checked/)
  await expect(ui.names).toHaveText(['DCIM_DAY_B.JPG', 'DCIM_DAY_A.JPG', 'UTC_SHIFT.JPG'])
  const summary = summaryRequests(requests).at(-1)!
  expect(summary.params.get('capture_date')).toBe('2026-10-19')
  expect(summary.params.get('path')).toBe('Pixel 8/DCIM')
  expect(summary.params.get('recursive')).toBe('false')
  await page.waitForTimeout(300)
  await page.screenshot({ path: `${statsHeatmapScreenshotDir}/heatmap-click-timeline.png`, fullPage: true })

  // 后退回到统计页（push 留下了历史），目录范围仍在
  await page.goBack()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/statistics')
  expect(urlQuery(page).get('path')).toBe('Pixel 8/DCIM')
  await expect(heatmap(page).locator('figcaption')).toHaveText('每日文件数量')

  // 键盘：只有一个 Tab 停靠点（今天），方向键移动，Enter 打开；没有文件的日子 Enter 不跳转
  await expect(heatmap(page).locator('rect[tabindex="0"]')).toHaveCount(1)
  await expect(heatCell(page, '2026-10-19')).toHaveAttribute('tabindex', '0')
  await heatCell(page, '2026-10-19').focus()
  await expect(heatTip(page)).toHaveText('2026年10月19日：12 个文件')
  await page.keyboard.press('ArrowUp')
  await expect(heatCell(page, '2026-10-18')).toBeFocused()
  await expect(heatCell(page, '2026-10-18')).toHaveAttribute('tabindex', '0')
  await expect(heatCell(page, '2026-10-19')).toHaveAttribute('tabindex', '-1')
  await expect(heatTip(page)).toHaveText('2026年10月18日：无文件')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(300)
  expect(new URL(page.url()).pathname).toBe('/statistics')
  await page.keyboard.press('ArrowLeft')
  await expect(heatCell(page, '2026-10-11')).toBeFocused()
  await page.keyboard.press('Home')
  await expect(heatCell(page, '2025-10-20')).toBeFocused()
  await page.keyboard.press('End')
  await expect(heatCell(page, '2026-10-19')).toBeFocused()
  // 已在最后一天：再往后不动
  await page.keyboard.press('ArrowRight')
  await expect(heatCell(page, '2026-10-19')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/timeline')
  expect(Object.fromEntries(urlQuery(page))).toEqual({ day: '2026-10-19', path: 'Pixel 8/DCIM', recursive: '0' })
  await expect(ui.tag).toHaveText('拍摄日期：2026年10月19日')

  expect(unknown).toEqual([])
})

// ---------------------------------------------------------------------------
// 数据统计：「每日文件数量」与总览同一行，卡片按网格宽度
// ---------------------------------------------------------------------------

const statsLayoutScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-web-stats-heatmap-layout-2026-10-19'

async function measureOverviewRow(page: Page) {
  return page.evaluate(() => {
    const box = (el: Element) => {
      const r = el.getBoundingClientRect()
      return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
    }
    const row = document.querySelector('.stat-overview')!
    const grid = row.parentElement!
    const wrap = document.querySelector<HTMLElement>('.stat-heatmap__scroll')!
    const body = document.querySelector<HTMLElement>('.stats-page > .pv-page-body')!
    return {
      row: box(row),
      grid: box(grid),
      numbers: box(row.querySelector('.stat-numbers')!),
      heat: box(row.querySelector('.stat-heatmap')!),
      svgWidth: Number(row.querySelector('.stat-heatmap__svg')!.getAttribute('width')),
      numberColumns: new Set([...row.querySelectorAll('.stat-number')].map((el) => Math.round(el.getBoundingClientRect().left))).size,
      scroll: { scrollWidth: wrap.scrollWidth, clientWidth: wrap.clientWidth, remaining: wrap.scrollWidth - wrap.clientWidth - wrap.scrollLeft },
      body: { scrollWidth: body.scrollWidth, clientWidth: body.clientWidth },
      doc: document.documentElement.scrollWidth,
      inner: window.innerWidth,
      otherWide: [...grid.children].filter((el) => el !== row && el.classList.contains('is-wide')).map((el) => el.getBoundingClientRect().width),
    }
  })
}

test('Statistics 总览 and 每日文件数量 share one row, wrap on narrow screens and never overflow the page', async ({ page }) => {
  test.setTimeout(120_000)
  mkdirSync(statsLayoutScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { statsResponse: statsResponseWithDaily })
  await openStatistics(page)
  await expect(page.locator('.stat-overview')).toHaveClass(/has-heatmap/)
  // 切换年份不改变卡片宽度（按最宽的可选范围定宽）
  const recentWidth = (await heatmap(page).boundingBox())!.width
  await heatmap(page).locator('.stat-heatmap__range').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '2025年' }).click()
  await expect(heatmap(page).locator('.stat-heatmap__summary')).toHaveText(/^2025年共/)
  expect((await heatmap(page).boundingBox())!.width).toBe(recentWidth)

  for (const theme of ['light', 'dark'] as const) {
    for (const width of [1920, 1440, 1024, 800, 375]) {
      const at = `${width} ${theme}`
      // 每个宽度重新打开页面：热力图在加载时滚到最右，Chart.js 画布也按新宽度绘制
      await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
      await page.goto('/statistics')
      await expect(heatmap(page).locator('figcaption')).toHaveText('每日文件数量')
      if (theme === 'dark') {
        // 模拟暗色：html.dark + Element Plus 暗色变量（同上面的暗色测试）
        await page.evaluate(() => document.documentElement.classList.add('dark'))
        await page.addStyleTag({ url: '/node_modules/element-plus/theme-chalk/dark/css-vars.css' })
      }
      await page.waitForTimeout(300)
      const m = await measureOverviewRow(page)
      console.log(at, JSON.stringify(m))

      // 页面（文档和内容区）都不出现横向滚动
      expect.soft(m.doc, `${at} document overflow`).toBeLessThanOrEqual(m.inner)
      expect.soft(m.body.scrollWidth, `${at} page body overflow`).toBeLessThanOrEqual(m.body.clientWidth)
      // 卡片 = 网格宽度 + 内边距，放不下时不超过这一行
      expect.soft(m.heat.width, `${at} card inside row`).toBeLessThanOrEqual(m.row.width + 0.5)
      if (m.row.width >= m.svgWidth + 32) {
        expect.soft(Math.abs(m.heat.width - (m.svgWidth + 32)), `${at} card = grid + padding`).toBeLessThanOrEqual(1)
      }
      // 类型占比 / 格式占比所在的并排行（拍摄趋势同样是 is-wide，本 fixture 没有月度数据所以不显示）仍然整行宽
      expect.soft(m.otherWide.length, `${at} other wide cards`).toBe(1)
      for (const w of m.otherWide) expect.soft(Math.abs(w - m.grid.width), `${at} other card full width`).toBeLessThanOrEqual(1)

      if (width >= 1440) {
        // 同一行：顶部和高度对齐，数字在左、热力图在右，间距 12px，热力图不占满整行
        expect.soft(Math.abs(m.numbers.top - m.heat.top), `${at} same top`).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(m.numbers.height - m.heat.height), `${at} same height`).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(m.numbers.left - m.row.left), `${at} numbers at left`).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(m.heat.right - m.row.right), `${at} heatmap at right`).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(m.heat.left - m.numbers.right - 12), `${at} gap`).toBeLessThanOrEqual(1)
        expect.soft(m.heat.width, `${at} not full width`).toBeLessThan(m.row.width - 100)
        expect.soft(m.numberColumns, `${at} 2 number columns`).toBe(2)
        expect.soft(m.scroll.scrollWidth, `${at} no grid scroll`).toBeLessThanOrEqual(m.scroll.clientWidth)
      } else {
        // 放不下：热力图换到数字卡片下面
        expect.soft(m.heat.top, `${at} wrapped`).toBeGreaterThanOrEqual(m.numbers.bottom)
      }
      if (width <= 800) {
        // 网格在卡片里横向滚动，最近一年滚到最右
        expect.soft(m.scroll.scrollWidth, `${at} grid scrolls`).toBeGreaterThan(m.scroll.clientWidth)
        expect.soft(m.scroll.remaining, `${at} scrolled right`).toBeLessThanOrEqual(1)
      }
      if (width === 375) expect.soft(m.numberColumns, `${at} 1 number column`).toBe(1)
      if (theme === 'dark') {
        const dark = await page.evaluate(() => ({
          background: getComputedStyle(document.querySelector('.stat-number')!).backgroundColor,
          overlay: getComputedStyle(document.documentElement).getPropertyValue('--el-bg-color-overlay').trim(),
        }))
        expect.soft(dark.background, `${at} dark number card`).toBe(rgb(dark.overlay.toUpperCase()))
      }

      await page.mouse.move(0, 0)
      await page.evaluate(() => {
        document.querySelector<HTMLElement>('.stats-page > .pv-page-body')!.scrollTop = 0
      })
      // 宽屏下整行都在首屏；375px 时数字卡片竖排，把热力图卡片滚进视口
      await heatmap(page).scrollIntoViewIfNeeded()
      await page.waitForTimeout(200)
      await page.screenshot({ path: `${statsLayoutScreenshotDir}/stats-${width}-${theme}.png` })
    }
  }

  expect(unknown).toEqual([])
})

test('Statistics without daily keeps the number cards in one line', async ({ page }) => {
  const { unknown } = await installApiMock(page)
  await authenticate(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/statistics')
  const row = page.locator('.stat-overview')
  await expect(row.locator('.stat-number')).toHaveCount(4)
  await expect(row).not.toHaveClass(/has-heatmap/)
  await expect(page.locator('.stat-heatmap')).toHaveCount(0)
  const lefts = await row.locator('.stat-number').evaluateAll((els) => new Set(els.map((el) => Math.round(el.getBoundingClientRect().left))).size)
  expect(lefts).toBe(4)
  expect(unknown).toEqual([])
})

// ---------------------------------------------------------------------------
// 数据统计：「类型占比」「格式占比」同一行，图例在卡片右下角与「查看数据」同一行
// ---------------------------------------------------------------------------

const statsShareScreenshotDir = '/Users/huo/PycharmProjects/PhotoVault/.agents/tasks/PhotoVault-web-stats-type-format-row-2026-10-19'

/** 1000 个文件、每个 10 MiB；格式占比有 11 种格式（key 与服务端 FORMAT_GROUPS 一致），图例必然换行 */
const shareBucket = (key: string, label: string, count: number) => ({
  key, label, count, share: count / 10, bytes: count * 10485760, bytes_share: count / 10,
})
const statsResponseManyFormats = {
  ...statsResponseWithDaily,
  overview: {
    ...statsResponseWithDaily.overview,
    total: { count: 1000, bytes: 10485760000 },
    categories: {
      kind: 'distribution',
      buckets: [shareBucket('photo', '照片', 812), shareBucket('video', '视频', 170), shareBucket('other', '其他', 18)],
      unknown: 0, unknown_share: 0, total: 1000, total_bytes: 10485760000,
    },
    formats: {
      kind: 'distribution',
      buckets: [
        shareBucket('jpeg', 'JPEG', 412), shareBucket('heic', 'HEIC/HEIF', 203), shareBucket('png', 'PNG', 96),
        shareBucket('webp', 'WebP', 31), shareBucket('gif', 'GIF', 12), shareBucket('raw', 'RAW', 58),
        shareBucket('mp4', 'MP4', 117), shareBucket('mov', 'MOV', 44), shareBucket('video_other', '其他视频', 9),
        shareBucket('no_ext', '无扩展名', 7), shareBucket('misc', '其他格式', 11),
      ],
      unknown: 0, unknown_share: 0, total: 1000, total_bytes: 10485760000,
    },
  },
}

const shareCard = (page: Page, title: string) =>
  page.locator('.stat-share-row > .stat-card').filter({ has: page.locator('figcaption', { hasText: title }) })

async function measureShareRow(page: Page) {
  return page.evaluate(() => {
    const box = (el: Element | null) => {
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
    }
    const row = document.querySelector('.stat-share-row')!
    const body = document.querySelector<HTMLElement>('.stats-page > .pv-page-body')!
    const cards = [...row.querySelectorAll(':scope > .stat-card')].map((card) => ({
      title: card.querySelector('figcaption')!.textContent!.trim(),
      box: box(card)!,
      data: box(card.querySelector('.stat-card__data')),
      button: box(card.querySelector('.stat-card__data .el-button')),
      legend: box(card.querySelector('.stat-card__legend')),
      items: [...card.querySelectorAll('.stat-card__legend li')].map((li) => box(li)!),
      canvasWidth: card.querySelector('canvas')!.clientWidth,
      plotWidth: card.querySelector<HTMLElement>('.stat-chart__plot')!.clientWidth,
      background: getComputedStyle(card).backgroundColor,
      legendColor: getComputedStyle(card.querySelector('.stat-card__legend') ?? card).color,
    }))
    return {
      row: box(row)!,
      grid: box(row.parentElement)!,
      cards,
      doc: document.documentElement.scrollWidth,
      inner: window.innerWidth,
      body: { scrollWidth: body.scrollWidth, clientWidth: body.clientWidth },
    }
  })
}

type ShareMeasure = Awaited<ReturnType<typeof measureShareRow>>

/** 并排 / 换行、不溢出、Chart.js 按卡片宽度重绘 */
function expectShareRowLayout(m: ShareMeasure, at: string, expectSameRow?: boolean) {
  expect.soft(m.doc, `${at} document overflow`).toBeLessThanOrEqual(m.inner)
  expect.soft(m.body.scrollWidth, `${at} page body overflow`).toBeLessThanOrEqual(m.body.clientWidth)
  expect.soft(Math.abs(m.row.width - m.grid.width), `${at} row = grid width`).toBeLessThanOrEqual(1)
  expect(m.cards.map((c) => c.title)).toEqual(['类型占比', '格式占比'])
  const [type, format] = m.cards
  for (const c of m.cards) {
    expect.soft(c.box.right, `${at} ${c.title} inside row`).toBeLessThanOrEqual(m.row.right + 0.5)
    expect.soft(c.box.left, `${at} ${c.title} inside row (left)`).toBeGreaterThanOrEqual(m.row.left - 0.5)
    expect.soft(Math.abs(c.canvasWidth - c.plotWidth), `${at} ${c.title} canvas = plot width`).toBeLessThanOrEqual(1)
  }
  const sameRow = m.row.width >= 692
  if (expectSameRow !== undefined) expect.soft(sameRow, `${at} expected same row = ${expectSameRow}`).toBe(expectSameRow)
  if (sameRow) {
    expect.soft(Math.abs(type.box.top - format.box.top), `${at} same top`).toBeLessThanOrEqual(1)
    expect.soft(Math.abs(type.box.height - format.box.height), `${at} same height`).toBeLessThanOrEqual(1)
    expect.soft(Math.abs(type.box.left - m.row.left), `${at} type at left`).toBeLessThanOrEqual(1)
    expect.soft(Math.abs(format.box.right - m.row.right), `${at} format at right`).toBeLessThanOrEqual(1)
    expect.soft(Math.abs(format.box.left - type.box.right - 12), `${at} gap 12`).toBeLessThanOrEqual(1)
    for (const c of m.cards) {
      expect.soft(Math.abs(c.box.width - (m.row.width - 12) / 2), `${at} ${c.title} half width`).toBeLessThanOrEqual(1)
    }
  } else {
    expect.soft(format.box.top, `${at} wrapped`).toBeGreaterThanOrEqual(type.box.bottom + 11)
    for (const c of m.cards) {
      expect.soft(Math.abs(c.box.width - m.row.width), `${at} ${c.title} full row width`).toBeLessThanOrEqual(1)
    }
  }
}

async function applySimulatedDark(page: Page) {
  // 模拟暗色：html.dark + Element Plus 暗色变量（同上面的暗色测试）
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await page.addStyleTag({ url: '/node_modules/element-plus/theme-chalk/dark/css-vars.css' })
}

test('Statistics 类型占比 and 格式占比 share one row on wide screens and wrap on narrow ones', async ({ page }) => {
  test.setTimeout(120_000)
  mkdirSync(statsShareScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { statsResponse: statsResponseManyFormats })
  await openStatistics(page)

  for (const theme of ['light', 'dark'] as const) {
    for (const width of [1920, 1440, 1024, 800, 375]) {
      const at = `${width} ${theme}`
      await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
      await page.goto('/statistics')
      await expect(shareCard(page, '格式占比')).toBeVisible()
      if (theme === 'dark') await applySimulatedDark(page)
      await page.waitForTimeout(300)
      const m = await measureShareRow(page)
      console.log(at, JSON.stringify({ row: m.row, cards: m.cards.map((c) => ({ title: c.title, box: c.box, canvas: c.canvasWidth, plot: c.plotWidth })) }))
      expectShareRowLayout(m, at, width >= 1440 ? true : width === 375 ? false : undefined)
      // 这两张卡片没有暗色覆盖（与改动前一致）：白底、图例正文色
      for (const c of m.cards) {
        expect.soft(c.background, `${at} ${c.title} background`).toBe('rgb(255, 255, 255)')
        expect.soft(c.legendColor, `${at} ${c.title} legend color`).toBe('rgb(96, 98, 102)')
      }

      await page.mouse.move(0, 0)
      await page.locator('.stat-share-row').scrollIntoViewIfNeeded()
      await page.waitForTimeout(200)
      await page.screenshot({ path: `${statsShareScreenshotDir}/stats-share-${width}-${theme}.png` })
    }
  }

  // 不刷新页面直接缩放：Chart.js 按新的卡片宽度重绘，两张卡片换行 / 恢复同一行
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/statistics')
  await expect(shareCard(page, '格式占比')).toBeVisible()
  await page.waitForTimeout(300)
  expectShareRowLayout(await measureShareRow(page), 'live 1440', true)
  await page.setViewportSize({ width: 600, height: 900 })
  await page.waitForTimeout(300)
  expectShareRowLayout(await measureShareRow(page), 'live 600', false)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.waitForTimeout(300)
  expectShareRowLayout(await measureShareRow(page), 'live 1440 again', true)

  expect(unknown).toEqual([])
})

test('Statistics 类型占比 and 格式占比 show the legend at the bottom right next to 查看数据', async ({ page }) => {
  test.setTimeout(90_000)
  mkdirSync(statsShareScreenshotDir, { recursive: true })
  const { unknown } = await installApiMock(page, { statsResponse: statsResponseManyFormats })
  await openStatistics(page)

  for (const width of [1440, 1024, 800, 375]) {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
    await page.goto('/statistics')
    await expect(shareCard(page, '格式占比')).toBeVisible()
    await page.waitForTimeout(300)

    for (const title of ['类型占比', '格式占比']) {
      const card = shareCard(page, title)
      // 图例不再画在图下，只在底部一行
      await expect(card.locator('.stat-chart .stat-chart__legend')).toHaveCount(0)
      await expect(card.locator('.stat-card__data.is-footer .stat-card__legend')).toHaveCount(1)
    }
    await expect(shareCard(page, '类型占比').locator('.stat-card__legend li')).toHaveText([
      '照片 812 个（81.2%）', '视频 170 个（17.0%）', '其他 18 个（1.8%）',
    ])
    await expect(shareCard(page, '格式占比').locator('.stat-card__legend li')).toHaveCount(11)
    await expect(shareCard(page, '格式占比').locator('.stat-card__legend li').first()).toHaveText('JPEG 412 个（41.2%）')

    const m = await measureShareRow(page)
    console.log(width, JSON.stringify(m.cards.map((c) => ({ title: c.title, box: c.box, data: c.data, button: c.button, legend: c.legend, items: c.items }))))
    for (const c of m.cards) {
      const at = `${width} ${c.title}`
      const button = c.button!
      const legend = c.legend!
      const buttonMid = button.top + button.height / 2
      // 按钮右侧放得下时同一行（宽屏一定是），否则图例整体换到按钮下面；两种情况都不相交
      const sameLine = legend.left >= button.right
      if (width === 1440) expect.soft(sameLine, `${at} legend on the 查看数据 line`).toBe(true)
      if (sameLine) {
        // 查看数据在左、图例在右；按钮垂直中心落在图例的上下范围内
        expect.soft(legend.left, `${at} legend right of button`).toBeGreaterThanOrEqual(button.right + 15)
        expect.soft(buttonMid, `${at} button centre below legend top`).toBeGreaterThanOrEqual(legend.top)
        expect.soft(buttonMid, `${at} button centre above legend bottom`).toBeLessThanOrEqual(legend.bottom)
      } else {
        expect.soft(legend.top, `${at} legend below button`).toBeGreaterThanOrEqual(button.bottom + 3)
      }
      expect.soft(Math.abs(legend.right - (c.box.right - 16)), `${at} legend at content right edge`).toBeLessThanOrEqual(1)
      // 底部一行贴在卡片底边（内边距 12px）：图例在右下角
      expect.soft(Math.abs(c.data!.bottom - (c.box.bottom - 12)), `${at} footer at card bottom`).toBeLessThanOrEqual(1)
      expect.soft(Math.max(button.bottom, legend.bottom), `${at} footer bottom`).toBeGreaterThanOrEqual(c.box.bottom - 13)
      for (const li of c.items) {
        expect.soft(li.left, `${at} item inside legend (left)`).toBeGreaterThanOrEqual(legend.left - 0.5)
        expect.soft(li.right, `${at} item inside legend (right)`).toBeLessThanOrEqual(legend.right + 0.5)
        expect.soft(li.top, `${at} item inside legend (top)`).toBeGreaterThanOrEqual(legend.top - 0.5)
        expect.soft(li.bottom, `${at} item inside legend (bottom)`).toBeLessThanOrEqual(legend.bottom + 0.5)
        expect.soft(li.right, `${at} item inside card`).toBeLessThanOrEqual(c.box.right + 0.5)
      }
      // 每一行图例右对齐：每行最右一项贴着图例右边
      const lines = new Map<number, number>()
      for (const li of c.items) {
        const key = Math.round(li.top)
        lines.set(key, Math.max(lines.get(key) ?? -Infinity, li.right))
      }
      for (const [top, right] of lines) {
        expect.soft(Math.abs(right - legend.right), `${at} line ${top} right aligned`).toBeLessThanOrEqual(1)
      }
      if (c.title === '类型占比' && width === 1440) {
        expect.soft(lines.size, `${at} single legend line`).toBe(1)
        expect.soft(Math.abs(buttonMid - (legend.top + legend.height / 2)), `${at} vertically centred`).toBeLessThan(2)
      }
      if (c.title === '格式占比') expect.soft(lines.size, `${at} legend wraps`).toBeGreaterThanOrEqual(2)
    }
    await page.mouse.move(0, 0)
    await page.locator('.stat-share-row').scrollIntoViewIfNeeded()
    await page.waitForTimeout(200)
    await page.locator('.stat-share-row').screenshot({ path: `${statsShareScreenshotDir}/stats-share-legend-${width}.png` })
  }

  // 以下在 1440 下检查交互
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/statistics')
  const format = shareCard(page, '格式占比')
  await expect(format).toBeVisible()
  const legendItems = format.locator('.stat-card__data.is-footer .stat-card__legend li')

  // 「数量 / 存储」：图例仍在底部一行，内容换成存储口径
  await format.locator('.stat-card__switch').getByText('存储', { exact: true }).click()
  await expect(legendItems).toHaveCount(11)
  await expect(legendItems.first()).toContainText(formatBytes(412 * 10485760))
  await expect(legendItems.first()).not.toHaveText('JPEG 412 个（41.2%）')
  await format.locator('.stat-card__switch').getByText('数量', { exact: true }).click()
  await expect(legendItems.first()).toHaveText('JPEG 412 个（41.2%）')

  // 键盘：绘图区 → Tab → 「查看数据」（图例不可聚焦）
  const dataButton = format.getByRole('button', { name: '查看数据' })
  await format.locator('.stat-chart__plot').focus()
  await page.keyboard.press('Tab')
  await expect(dataButton).toBeFocused()

  // 对话框里的图例仍在图下
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog', { name: '格式占比数据' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.stat-chart .stat-chart__legend li')).toHaveCount(11)
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(dataButton).toBeFocused()

  // 其他卡片的布局不变
  await expect(page.locator('.stat-card__legend')).toHaveCount(2)
  await expect(page.locator('.stat-card__data.is-footer')).toHaveCount(2)
  const ev = page.locator('.stat-card').filter({ has: page.locator('figcaption', { hasText: '场景亮度（EV100）' }) })
  await expect(ev.locator('.stat-card__data')).toHaveCount(1)
  await expect(ev.locator('.stat-card__data')).not.toHaveClass(/is-footer/)

  expect(unknown).toEqual([])
})
