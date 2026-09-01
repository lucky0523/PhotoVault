/**
 * @trimjs/web-app 的类型声明里 import 了 `@fn/micro-app-postmate`，但那个包并未
 * 发布到 npm（它在 SDK 仓库里是 workspace 依赖，构建时被打进产物）。
 *
 * 结果是安装 @trimjs/web-app 后，`node_modules/@trimjs/web-app/dist/*.d.ts` 里
 * 有一条无法解析的 import。当前 tsconfig 开了 skipLibCheck，vue-tsc 不会报错，
 * 但所有来自该模块的类型都会退化成 any——包括我们实际要用的 FilePickerParams。
 *
 * 这里补一份最小声明，把用到的类型补齐：
 *   1. 目录选择器参数在编辑器里有真实补全和校验，而不是 any；
 *   2. 万一以后关掉 skipLibCheck，也不会因为这条 import 而构建失败。
 *
 * 字段依据飞牛官方文档「应用共享授权路径 / 用户个人授权路径」的 JS SDK 类型定义：
 * https://developer.fnnas.com/api/authorization/shared-access/
 */
declare module '@fn/micro-app-postmate' {
  export type SidebarGroup =
    | 'myFiles'
    | 'otherShare'
    | 'external'
    | 'remote'
    | 'favorites'
    | 'team'

  export interface FilePickerParams {
    /** 是否允许多选文件 */
    multiple?: boolean
    /** 是否选择目录 */
    directory?: boolean
    /** 支持的文件扩展名，例如 '.png' */
    accept?: string[]
    /** 控制选择器左侧可见分组和展示顺序 */
    sidebarGroup?: SidebarGroup[]
    /** 选择器标题 */
    title?: string
    /** 确认按钮文案 */
    okText?: string
    /** 宿主支持时，是否允许在选择器中创建文件夹 */
    creatable?: boolean
    /** 不允许选择的路径 */
    disabledPaths?: string[]
  }

  export type AppBridgeResponse<T> = {
    /** 业务码，0 表示成功 */
    code: number
    /** 错误消息或状态消息 */
    msg: string
    data: T
  }

  /** 以下三个类型本项目没有直接使用，仅为补全 SDK d.ts 的 import 列表。 */
  export type ResponseData<T> = AppBridgeResponse<T>
  export interface OpenAppParams {
    [key: string]: unknown
  }
  export interface QueryConfig {
    [key: string]: unknown
  }
}
