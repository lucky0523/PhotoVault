import { createApp, defineComponent, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, {
  ElButton,
  ElCheckbox,
  ElDialog,
  ElInput,
  ElLink,
  ElLoading,
  ElPagination,
  ElProgress,
  ElRadio,
  ElSwitch,
} from 'element-plus'
import 'element-plus/dist/index.css'
import '@/styles/theme.css'
import '@/styles/layout.css'
import TimelineView from '@/views/TimelineView.vue'
import TrashView from '@/views/TrashView.vue'
import { buildTrendSeries } from '@/components/stats/chartModels'
import { STAT_COLORS, STAT_TEXT_COLOR } from '@/components/stats/chartSetup'

const types = ['primary', 'success', 'warning', 'danger'] as const

const ThemeFixture = defineComponent({
  setup() {
    const checked = ref(false)
    const radio = ref('')
    const switched = ref(false)
    const input = ref('')
    const dialogVisible = ref(false)
    const currentPage = ref(2)
    const view = ref<'controls' | 'timeline' | 'trash'>('controls')
    let loading: ReturnType<typeof ElLoading.service> | undefined

    onMounted(() => {
      loading = ElLoading.service({ target: '#loading-target', text: '加载中' })
    })
    onBeforeUnmount(() => loading?.close())

    const matrix = () => h('section', { class: 'matrix', 'data-testid': 'state-matrix' }, types.flatMap((type) => [
      h(ElButton, { type, 'data-testid': `solid-${type}` }, () => `${type} solid`),
      h(ElButton, { type, plain: true, 'data-testid': `plain-${type}` }, () => `${type} plain`),
      h(ElButton, { type, link: true, 'data-testid': `link-button-${type}` }, () => `${type} link button`),
      h(ElLink, { type, tabindex: 0, 'data-testid': `element-link-${type}` }, () => `${type} element link`),
      h(ElButton, { type, disabled: true, 'data-testid': `solid-${type}-disabled` }, () => `${type} disabled`),
      h(ElButton, { type, plain: true, disabled: true, 'data-testid': `plain-${type}-disabled` }, () => `${type} plain disabled`),
      h(ElButton, { type, link: true, disabled: true, 'data-testid': `link-button-${type}-disabled` }, () => `${type} link disabled`),
      h(ElLink, { type, disabled: true, 'data-testid': `element-link-${type}-disabled` }, () => `${type} element disabled`),
    ]))

    // Disabled variants outside the per-type matrix; a loading button must keep its brand color.
    const disabledVariants = () => h('section', { class: 'matrix', 'data-testid': 'disabled-variants' }, [
      h(ElButton, { disabled: true, 'data-testid': 'solid-default-disabled' }, () => 'default disabled'),
      h(ElButton, { plain: true, disabled: true, 'data-testid': 'plain-default-disabled' }, () => 'default plain disabled'),
      h(ElButton, { type: 'info', disabled: true, 'data-testid': 'solid-info-disabled' }, () => 'info disabled'),
      h(ElButton, { type: 'info', plain: true, disabled: true, 'data-testid': 'plain-info-disabled' }, () => 'info plain disabled'),
      h(ElButton, { type: 'danger', dashed: true, disabled: true, 'data-testid': 'dashed-danger-disabled' }, () => 'danger dashed disabled'),
      h(ElButton, { type: 'primary', loading: true, 'data-testid': 'solid-primary-loading' }, () => 'primary loading'),
    ])

    const controls = () => h('section', { class: 'controls', 'data-testid': 'derived-controls' }, [
      h(ElCheckbox, { modelValue: checked.value, 'onUpdate:modelValue': (value: boolean) => { checked.value = value }, 'data-testid': 'checkbox' }, () => '复选框'),
      h(ElRadio, { modelValue: radio.value, value: 'selected', 'onUpdate:modelValue': (value: string) => { radio.value = value }, 'data-testid': 'radio' }, () => '单选框'),
      h(ElSwitch, { modelValue: switched.value, 'onUpdate:modelValue': (value: boolean) => { switched.value = value }, 'data-testid': 'switch' }),
      h(ElInput, { modelValue: input.value, 'onUpdate:modelValue': (value: string) => { input.value = value }, placeholder: '聚焦输入框', 'data-testid': 'input' }),
      h(ElPagination, {
        currentPage: currentPage.value,
        'onUpdate:currentPage': (value: number) => { currentPage.value = value },
        pageSize: 10,
        total: 40,
        layout: 'prev, pager, next',
        background: true,
        'data-testid': 'pagination',
      }),
      h(ElProgress, { percentage: 65, 'data-testid': 'progress' }),
      h('div', { id: 'loading-target', style: 'position: relative; width: 240px; height: 96px; margin: 16px 0;', 'data-testid': 'loading-target' }, 'teleported loading target'),
      h(ElButton, { type: 'primary', 'data-testid': 'open-dialog', onClick: () => { dialogVisible.value = true } }, () => '打开对话框'),
      h(ElDialog, {
        modelValue: dialogVisible.value,
        'onUpdate:modelValue': (value: boolean) => { dialogVisible.value = value },
        title: 'Teleport 对话框',
      }, {
        default: () => '对话框继承根主题变量',
        footer: () => h(ElButton, { type: 'primary', 'data-testid': 'dialog-primary' }, () => '确认'),
      }),
    ])

    const chartDistribution = {
      kind: 'series',
      buckets: [{ key: '2026-10', label: '2026-10', count: 1, share: 100 }],
      unknown: 0,
      unknown_share: 0,
      total: 1,
      excludedUnknown: 0,
    }
    const statisticsRegression = JSON.stringify({
      colors: STAT_COLORS,
      text: STAT_TEXT_COLOR,
      series: buildTrendSeries(chartDistribution, chartDistribution).map(({ color, dashed, pointStyle }) => ({ color, dashed, pointStyle })),
    })

    return () => h('main', { class: 'fixture-shell' }, [
      h('output', { 'data-testid': 'statistics-colors', hidden: true }, statisticsRegression),
      h('nav', { class: 'fixture-nav' }, [
        h(ElButton, { onClick: () => { view.value = 'controls' }, 'data-testid': 'show-controls' }, () => '控件'),
        h(ElButton, { onClick: () => { view.value = 'timeline' }, 'data-testid': 'show-timeline' }, () => '时间线'),
        h(ElButton, { onClick: () => { view.value = 'trash' }, 'data-testid': 'show-trash' }, () => '回收站'),
      ]),
      view.value === 'controls' ? h('div', [matrix(), disabledVariants(), controls()]) : null,
      view.value === 'timeline' ? h('div', { class: 'real-view', 'data-testid': 'real-timeline' }, [h(TimelineView)]) : null,
      view.value === 'trash' ? h('div', { class: 'real-view', 'data-testid': 'real-trash' }, [h(TrashView)]) : null,
    ])
  },
})

const app = createApp(ThemeFixture)
app.use(createPinia())
app.use(ElementPlus)
app.mount('#app')
