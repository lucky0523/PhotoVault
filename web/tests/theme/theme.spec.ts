import { expect, test, type Locator, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'

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

async function installApiMock(page: Page, options: { trashItems?: unknown[]; clientVersions?: unknown[] } = {}) {
  const trashItems = options.trashItems ?? [trashItem]
  const unknown: string[] = []
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const key = `${request.method()} ${url.pathname}`
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

    if (key === 'GET /api/v1/setup/status') return json({ initialized: true, needs_work_dir: false })
    if (key === 'GET /api/v1/auth/registration-status') return json({ allow_registration: true })
    if (key === 'GET /api/v1/files/config') return json({ trash_retention_days: 30 })
    if (key === 'GET /api/v1/files/trash') return json({ items: trashItems, total: trashItems.length, page: 1, page_size: Number(url.searchParams.get('page_size') ?? 50) })
    if (key === 'GET /api/v1/admin/client-versions') return json(options.clientVersions ?? [])
    if (key === 'GET /api/v1/files/all') return json(listResponse)
    if (key === 'GET /api/v1/files/list') return json(listResponse)
    if (key === 'GET /api/v1/files/browse') return json({ ...listResponse, files: [] })
    if (key === 'GET /api/v1/stats/media') return json(statsResponse)
    if (key.startsWith('GET /api/v1/files/thumbnail/')) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: transparentPng })
    }

    unknown.push(`${key}${url.search}`)
    await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ detail: `Unknown Playwright API fixture: ${key}` }) })
  })
  return unknown
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
  const unknown = await installApiMock(page)
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
  const unknown = await installApiMock(page)
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
  const unknown = await installApiMock(page)
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
  const unknown = await installApiMock(page, { trashItems: [] })
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
  const unknown = await installApiMock(page, { clientVersions: [] })
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
  const unknown = await installApiMock(page)

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
  const unknown = await installApiMock(page)
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
        extra: [...document.querySelectorAll('.timeline-view .pv-page-header__extra > *')].map(rect),
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

      // Wide layouts must stay as they were: a single 48px row at 1920/1440,
      // and the title still beside the filters at 1280.
      if (!filtered && (width === 1920 || width === 1440)) {
        expect.soft(m.card.height, `${label} card height`).toBeCloseTo(48, 0)
      }
      if (!filtered && width >= 1280) {
        expect.soft(m.left.right, `${label} title beside filters`).toBeLessThan(m.extra[0].left)
      }
    }
  }
  expect(unknown).toEqual([])
})
