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

const solid = {
  primary: {
    default: ['#FFFFFF', '#D92D4F', '#D92D4F'], hover: ['#FFFFFF', '#B3123A', '#B3123A'],
    active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled: ['#65001F', '#EC96A7', '#EC96A7'],
  },
  success: {
    default: ['#00391C', '#00D066', '#00D066'], hover: ['#00391C', '#4DDB8F', '#4DDB8F'],
    active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled: ['#00391C', '#80E8AF', '#80E8AF'],
  },
  warning: {
    default: ['#422B00', '#FFA91F', '#FFA91F'], hover: ['#422B00', '#FFC25C', '#FFC25C'],
    active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled: ['#422B00', '#FFD48C', '#FFD48C'],
  },
  danger: {
    default: ['#5B101B', '#FF5C72', '#FF5C72'], hover: ['#5B101B', '#FF8D9C', '#FF8D9C'],
    active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled: ['#5B101B', '#FFB3BD', '#FFB3BD'],
  },
}

const plain = {
  primary: {
    default: ['#B3123A', '#FCEAED', '#D92D4F'], hover: ['#FFFFFF', '#D92D4F', '#D92D4F'],
    active: ['#FFFFFF', '#B3123A', '#B3123A'], focus: '#B3123A', disabled: solid.primary.disabled,
  },
  success: {
    default: ['#00391C', '#E6FBEF', '#007A3D'], hover: ['#00391C', '#CCF7DF', '#007A3D'],
    active: ['#FFFFFF', '#007A3D', '#007A3D'], focus: '#007A3D', disabled: solid.success.disabled,
  },
  warning: {
    default: ['#422B00', '#FFF6E8', '#A86200'], hover: ['#422B00', '#FFEDD1', '#A86200'],
    active: ['#FFFFFF', '#A86200', '#A86200'], focus: '#A86200', disabled: solid.warning.disabled,
  },
  danger: {
    default: ['#5B101B', '#FFF2F4', '#9F293B'], hover: ['#5B101B', '#FFE6EA', '#9F293B'],
    active: ['#FFFFFF', '#9F293B', '#9F293B'], focus: '#9F293B', disabled: solid.danger.disabled,
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
    for (const state of ['default', 'hover', 'active', 'disabled']) {
      const expected = states[state]
      for (let index = 0; index < 3; index++) {
        const name = names[state][index]
        const actual = own.get(name) ?? solidValues.get(name)
        if (actual !== expected[index]) throw new Error(`${selector} ${state} ${name}: expected ${expected[index]}, got ${actual}`)
      }
    }
    if ((own.get('--el-button-outline-color') ?? solidValues.get('--el-button-outline-color')) !== states.focus) {
      throw new Error(`${selector}: incomplete focus state`)
    }
  }
}

verifyMatrix(solid, false)
verifyMatrix(plain, true)

const linkStates = {
  primary: ['#B3123A', '#B3123A'],
  success: ['#00391C', '#007A3D'],
  warning: ['#422B00', '#A86200'],
  danger: ['#5B101B', '#9F293B'],
}
for (const [type, [ink, active]] of Object.entries(linkStates)) {
  const elementLink = rule(`.el-link.el-link--${type}`)
  const linkButton = rule(`.el-button.el-button--${type}.is-link`)
  for (const [values, prefix] of [[elementLink, '--el-link'], [linkButton, '--el-button']]) {
    const textName = `${prefix}-text-color`
    const hoverName = prefix === '--el-link' ? '--el-link-hover-text-color' : '--el-button-hover-link-text-color'
    if (values.get(textName) !== ink || values.get(hoverName) !== ink) throw new Error(`${type} link default/hover matrix is incomplete`)
  }
  if (elementLink.get('--pv-link-active-color') !== active || linkButton.get('--el-button-active-color') !== active) {
    throw new Error(`${type} link active matrix is incomplete`)
  }
  if (elementLink.get('--el-link-disabled-text-color') !== '#58677E' || linkButton.get('--el-button-disabled-text-color') !== '#58677E') {
    throw new Error(`${type} link disabled matrix is incomplete`)
  }
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
for (const [type, [ink]] of Object.entries(linkStates)) {
  requireRatio(`${type}.link`, ink, '#FFFFFF', 4.5)
}
requireRatio('selected.brand.ink', '#B3123A', '#FCEAED', 4.5)
requireRatio('input.focus', '#D92D4F', '#FFFFFF', 3)

const focusRule = rule('.el-button:focus-visible')
if (focusRule.get('outline') !== '2px solid var(--el-button-outline-color, var(--pv-link-focus-color))' || focusRule.get('outline-offset') !== '2px') {
  throw new Error('focus-visible must use a 2px semantic outline and 2px offset')
}
if (source.includes('!important')) throw new Error('theme.css must not use !important')

console.log('Yingji Web theme token, state-matrix, and WCAG checks passed.')
