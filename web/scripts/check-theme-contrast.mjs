import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = readFileSync(resolve(root, 'src/styles/theme.css'), 'utf8')

function declarations(body) {
  const values = new Map()
  for (const match of body.matchAll(/([\w-]+)\s*:\s*([^;]+);/g)) {
    if (values.has(match[1])) throw new Error(`duplicate declaration: ${match[1]}`)
    values.set(match[1], match[2].trim())
  }
  return values
}

const rootMatch = source.match(/:root\s*\{([\s\S]*?)\n\}/)
if (!rootMatch) throw new Error('missing :root theme block')
const tokens = declarations(rootMatch[1])

const expectedTokens = {
  '--el-color-primary': '#D92D4F',
  '--el-color-primary-rgb': '217, 45, 79',
  '--el-color-primary-light-3': '#E46C84',
  '--el-color-primary-light-5': '#EC96A7',
  '--el-color-primary-light-7': '#F4C0CA',
  '--el-color-primary-light-8': '#F8D5DC',
  '--el-color-primary-light-9': '#FCEAED',
  '--el-color-primary-dark-2': '#B3123A',
  '--pv-brand-primary': '#D92D4F',
  '--pv-brand-logo-bright': '#F0464F',
  '--pv-brand-strong': '#B3123A',
  '--pv-brand-ink': '#B3123A',
  '--pv-brand-container': '#FCEAED',
  '--pv-brand-surface': '#FFF8F7',
  '--pv-brand-warm-bg': '#FFF3E6',
  '--pv-success-ink': '#00391C',
  '--pv-success-active': '#007A3D',
  '--pv-warning-ink': '#422B00',
  '--pv-warning-active': '#A86200',
  '--pv-danger-ink': '#5B101B',
  '--pv-danger-active': '#9F293B',
  // Base semantic tokens stay (tags/alerts/messages); the button-level matrix below carries the button colors.
  '--el-color-success': '#00D066',
  '--el-color-danger': '#FF5C72',
  '--el-color-info': '#58677E',
  '--el-color-info-light-9': '#EFF0F2',
  '--el-color-info-light-7': '#CFD3DA',
}

for (const [name, value] of Object.entries(expectedTokens)) {
  if (tokens.get(name) !== value) {
    throw new Error(`${name}: expected ${value}, got ${tokens.get(name) ?? 'missing'}`)
  }
}

for (const [name, value] of tokens) {
  if (value.startsWith('#') && !/^#[0-9A-F]{6}$/.test(value)) {
    throw new Error(`${name}: colors must use six-digit uppercase hex, got ${value}`)
  }
}

for (const removed of ['--pv-vault-blue', '--pv-mist']) {
  if (source.includes(removed)) throw new Error(`removed token remains: ${removed}`)
}

const cssWithoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '')
const rules = [...cssWithoutComments.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((match) => ({
  selectors: match[1].split(',').map((selector) => selector.trim()),
  values: declarations(match[2]),
}))

function rule(selector) {
  const matches = rules.filter(({ selectors }) => selectors.includes(selector))
  if (matches.length !== 1) throw new Error(`${selector}: expected one rule, found ${matches.length}`)
  return matches[0].values
}

// Every disabled el-button (all types, solid/plain/dashed) uses one neutral grey triplet.
const disabled = ['#58677E', '#EFF0F2', '#CFD3DA']

const solid = {
  primary: {
    default: ['#FFFFFF', '#D92D4F', '#D92D4F'], hover: ['#FFFFFF', '#B3123A', '#B3123A'],
    active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled,
  },
  success: {
    default: ['#00391C', '#6ECF9C', '#6ECF9C'], hover: ['#00391C', '#8CD9B1', '#8CD9B1'],
    active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled,
  },
  warning: {
    default: ['#422B00', '#FFA91F', '#FFA91F'], hover: ['#422B00', '#FFC25C', '#FFC25C'],
    active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled,
  },
  danger: {
    default: ['#FFFFFF', '#D92D4F', '#D92D4F'], hover: ['#FFFFFF', '#B3123A', '#B3123A'],
    active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled,
  },
}

const plain = {
  primary: {
    default: ['#B3123A', '#FCEAED', '#D92D4F'], hover: ['#FFFFFF', '#D92D4F', '#D92D4F'],
    active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled,
  },
  success: {
    default: ['#00391C', '#F1FAF5', '#007A3D'], hover: ['#00391C', '#E2F5EB', '#007A3D'],
    active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled,
  },
  warning: {
    default: ['#422B00', '#FFF6E8', '#A86200'], hover: ['#422B00', '#FFEDD1', '#A86200'],
    active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled,
  },
  danger: {
    default: ['#B3123A', '#FFF2F4', '#D92D4F'], hover: ['#FFFFFF', '#D92D4F', '#D92D4F'],
    active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled,
  },
}

const names = {
  default: ['--el-button-text-color', '--el-button-bg-color', '--el-button-border-color'],
  hover: ['--el-button-hover-text-color', '--el-button-hover-bg-color', '--el-button-hover-border-color'],
  active: ['--el-button-active-text-color', '--el-button-active-bg-color', '--el-button-active-border-color'],
  disabled: ['--el-button-disabled-text-color', '--el-button-disabled-bg-color', '--el-button-disabled-border-color'],
}

function verifyMatrix(matrix, plainMode) {
  for (const [type, states] of Object.entries(matrix)) {
    const selector = `.el-button.el-button--${type}${plainMode ? '.is-plain' : ''}`
    const own = rule(selector)
    const solidValues = plainMode ? rule(`.el-button.el-button--${type}`) : own
    for (const state of ['default', 'hover', 'active']) {
      const expected = states[state]
      for (let index = 0; index < 3; index++) {
        const name = names[state][index]
        const actual = own.get(name) ?? solidValues.get(name)
        if (actual !== expected[index]) throw new Error(`${selector} ${state} ${name}: expected ${expected[index]}, got ${actual}`)
      }
    }
    if (names.disabled.some((name) => own.has(name))) {
      throw new Error(`${selector}: per-type disabled override is not allowed; use the global disabled rule`)
    }
    if ((own.get('--el-button-outline-color') ?? solidValues.get('--el-button-outline-color')) !== states.focus) {
      throw new Error(`${selector}: incomplete focus state`)
    }
  }
}

verifyMatrix(solid, false)
verifyMatrix(plain, true)

// One global neutral disabled rule for every non-link/text el-button.
const disabledSelector = '.el-button.el-button.is-disabled:not(.is-link):not(.is-text)'
const disabledRules = rules.filter(({ selectors }) => selectors.includes(disabledSelector))
if (disabledRules.length !== 1) throw new Error(`${disabledSelector}: expected one rule, found ${disabledRules.length}`)
const disabledRule = disabledRules[0]
const expectedDisabledSelectors = ['', ':hover', ':focus', ':active'].map((suffix) => `${disabledSelector}${suffix}`)
if (JSON.stringify(disabledRule.selectors) !== JSON.stringify(expectedDisabledSelectors)) {
  throw new Error(`${disabledSelector}: selectors must be exactly ${expectedDisabledSelectors.join(', ')}`)
}
const resolveVar = (value) => {
  const match = value?.match(/^var\((--[\w-]+)\)$/)
  return match ? tokens.get(match[1]) : value
}
const disabledTokens = ['var(--el-color-info)', 'var(--el-color-info-light-9)', 'var(--el-color-info-light-7)']
const disabledProperties = ['color', 'background-color', 'border-color']
for (let index = 0; index < 3; index++) {
  for (const name of [names.disabled[index], disabledProperties[index]]) {
    const raw = disabledRule.values.get(name)
    if (raw !== disabledTokens[index]) throw new Error(`${disabledSelector} ${name}: expected ${disabledTokens[index]}, got ${raw}`)
    if (resolveVar(raw) !== disabled[index]) throw new Error(`${disabledSelector} ${name}: expected ${disabled[index]}, got ${resolveVar(raw)}`)
  }
}
// Guard against pastel regressions: no other rule may repaint a disabled button's fill or border.
for (const candidate of rules) {
  if (candidate === disabledRule) continue
  if (candidate.values.has('--el-button-disabled-bg-color') || candidate.values.has('--el-button-disabled-border-color')) {
    throw new Error(`${candidate.selectors.join(', ')}: disabled fill/border must come from the global disabled rule`)
  }
  if (candidate.selectors.some((selector) => selector.includes('is-disabled')) && candidate.values.has('background-color')) {
    throw new Error(`${candidate.selectors.join(', ')}: disabled background must come from the global disabled rule`)
  }
}

// [ink, hover, active]; focus outline = active.
const linkStates = {
  primary: ['#B3123A', '#B3123A', '#B3123A'],
  success: ['#007A3D', '#006633', '#00592D'],
  warning: ['#422B00', '#422B00', '#A86200'],
  danger: ['#D92D4F', '#B3123A', '#9F293B'],
}
for (const [type, [ink, hover, active]] of Object.entries(linkStates)) {
  const elementLink = rule(`.el-link.el-link--${type}`)
  const linkButton = rule(`.el-button.el-button--${type}.is-link`)
  if (rule(`.el-button.el-button--${type}.is-text`) !== linkButton) {
    throw new Error(`${type} link and text buttons must share one rule`)
  }
  const checks = [
    ['default', elementLink.get('--el-link-text-color'), ink],
    ['default', linkButton.get('--el-button-text-color'), ink],
    ['hover', elementLink.get('--el-link-hover-text-color'), hover],
    ['hover', linkButton.get('--el-button-hover-link-text-color'), hover],
    ['hover', linkButton.get('--el-button-hover-text-color'), hover],
    ['active', elementLink.get('--pv-link-active-color'), active],
    ['active', linkButton.get('--el-button-active-color'), active],
    ['focus', elementLink.get('--pv-link-focus-color'), active],
    ['focus', linkButton.get('--el-button-outline-color'), active],
    ['disabled', elementLink.get('--el-link-disabled-text-color'), '#58677E'],
    ['disabled', linkButton.get('--el-button-disabled-text-color'), '#58677E'],
  ]
  for (const [state, actual, expected] of checks) {
    if (actual !== expected) throw new Error(`${type} link ${state}: expected ${expected}, got ${actual}`)
  }
}

// Danger links inside a hovered table row (#F5F7FA) rest at the hover ink; #D92D4F is only 4.41:1 there.
const rowHoverInk = '#B3123A'
const rowHoverSelectors = [
  '.el-table--enable-row-hover .el-table__body tr:hover > td.el-table__cell .el-button.el-button--danger.is-link',
  '.el-table--enable-row-hover .el-table__body tr:hover > td.el-table__cell .el-button.el-button--danger.is-text',
  '.el-table--enable-row-hover .el-table__body tr:hover > td.el-table__cell .el-link.el-link--danger',
  '.el-table__body tr.hover-row > td.el-table__cell .el-button.el-button--danger.is-link',
  '.el-table__body tr.hover-row > td.el-table__cell .el-button.el-button--danger.is-text',
  '.el-table__body tr.hover-row > td.el-table__cell .el-link.el-link--danger',
]
const rowHoverRules = rules.filter(({ selectors }) => JSON.stringify(selectors) === JSON.stringify(rowHoverSelectors))
if (rowHoverRules.length !== 1) throw new Error(`danger row-hover link rule: expected one rule, found ${rowHoverRules.length}`)
const rowHoverValues = rowHoverRules[0].values
if (
  rowHoverValues.size !== 2
  || rowHoverValues.get('--el-button-text-color') !== rowHoverInk
  || rowHoverValues.get('--el-link-text-color') !== rowHoverInk
) {
  throw new Error(`danger row-hover link rule must only set --el-button-text-color and --el-link-text-color to ${rowHoverInk}`)
}

function channels(hex) {
  return [1, 3, 5].map((index) => Number.parseInt(hex.slice(index, index + 2), 16))
}
function luminance(hex) {
  const [r, g, b] = channels(hex).map((value) => {
    const channel = value / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
function contrast(a, b) {
  const values = [luminance(a), luminance(b)].sort((left, right) => right - left)
  return (values[0] + 0.05) / (values[1] + 0.05)
}
function requireRatio(label, foreground, background, threshold) {
  const ratio = contrast(foreground, background)
  console.log(`${label}: ${ratio}:1 (${foreground} / ${background})`)
  if (ratio < threshold) throw new Error(`${label}: ${ratio}:1 is below ${threshold}:1`)
}

for (const [mode, matrix] of [['solid', solid], ['plain', plain]]) {
  for (const [type, states] of Object.entries(matrix)) {
    for (const state of ['default', 'hover', 'active', 'disabled']) {
      requireRatio(`${mode}.${type}.${state}.text`, states[state][0], states[state][1], 4.5)
    }
  }
}
for (const [type, states] of Object.entries(plain)) {
  requireRatio(`plain.${type}.default.border`, states.default[2], states.default[1], 3)
}
for (const [type, states] of Object.entries(solid)) {
  requireRatio(`${type}.focus`, states.focus, '#FFFFFF', 3)
}
// Link ink on white, the #FAFAFA table stripe, and the #F5F7FA table row-hover fill.
for (const [type, [ink, hover, active]] of Object.entries(linkStates)) {
  for (const [state, color] of [['default', ink], ['hover', hover], ['active', active]]) {
    requireRatio(`${type}.link.${state}`, color, '#FFFFFF', 4.5)
    requireRatio(`${type}.link.${state}.stripe`, color, '#FAFAFA', 4.5)
  }
  requireRatio(`${type}.link.row-hover`, type === 'danger' ? rowHoverInk : ink, '#F5F7FA', 4.5)
  // Row actions (restore/delete) are success/danger; warning's #A86200 active ink is unchanged here.
  if (type === 'success' || type === 'danger') {
    requireRatio(`${type}.link.hover.row-hover`, hover, '#F5F7FA', 4.5)
    requireRatio(`${type}.link.active.row-hover`, active, '#F5F7FA', 4.5)
  }
  requireRatio(`${type}.link.focus`, active, '#FFFFFF', 3)
}
requireRatio('selected.brand.ink', '#B3123A', '#FCEAED', 4.5)
requireRatio('input.focus', '#D92D4F', '#FFFFFF', 3)

const focusRule = rule('.el-button:focus-visible')
if (focusRule.get('outline') !== '2px solid var(--el-button-outline-color, var(--pv-link-focus-color))' || focusRule.get('outline-offset') !== '2px') {
  throw new Error('focus-visible must use a 2px semantic outline and 2px offset')
}
if (source.includes('!important')) throw new Error('theme.css must not use !important')

console.log('Yingji Web theme token, state-matrix, and WCAG checks passed.')
