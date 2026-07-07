# Implementation Plan

## Overview

按 bugfix 方法论组织：先在**未修复**代码上写测试重现缺陷（Property 1 Bug Condition 必须失败）并固化需保留的既有行为（Property 2 Preservation 必须通过），再实施 A+B 组合修复（`observeAll()` 响应式合并 + `ON_RESUME` 兜底），最后重跑同一批测试完成 Fix Checking 与 Preservation Checking。改动集中在 `PhotoStatusDao.kt`、`FolderDetailViewModel.kt`、`FolderDetailScreen.kt` 三个文件。断言对象为 ViewModel 暴露的 `images`（状态由 `FolderImage.status` 纯函数派生）。

## Task Dependency Graph

```
1 (Bug Condition 重现测试, FAIL) ─┐
2 (Preservation 属性测试, PASS) ──┤
                                  ▼
                       3.1 (DAO observeAll)
                                  ▼
                       3.2 (ViewModel combine + reloadStatuses)
                                  ▼
                       3.3 (Screen ON_RESUME)
                                  ▼
                 3.4 (重跑任务1 → PASS)   3.5 (重跑任务2 → PASS)
                                  ▼
                            4 (集成测试)
                                  ▼
                            5 (Checkpoint)
```

- 任务 1、2 无前置依赖，须在修复前完成（观测未修复代码行为）。
- 任务 3.1 → 3.2 → 3.3 为顺序依赖（DAO 查询 → ViewModel 合并 → UI 兜底）。
- 任务 3.4/3.5 依赖 3.1–3.3 全部完成。
- 任务 4 依赖修复完成，任务 5 依赖全部任务。

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1", "2"] },
    { "wave": 2, "tasks": ["3.1"] },
    { "wave": 3, "tasks": ["3.2"] },
    { "wave": 4, "tasks": ["3.3"] },
    { "wave": 5, "tasks": ["3.4", "3.5"] },
    { "wave": 6, "tasks": ["4"] },
    { "wave": 7, "tasks": ["5"] }
  ]
}
```

## Tasks

- [x] 1. 编写 Bug Condition 重现测试（在修复前，必须失败）
  - **Property 1: Bug Condition** - 展示状态收敛到 photo_status 表
  - **CRITICAL**: 此测试必须在**未修复**代码上运行并 FAIL —— 失败即证明缺陷存在
  - **DO NOT** 在测试失败时去修改测试或业务代码；失败是预期结果
  - **NOTE**: 此测试同时编码了期望行为（`isBugCondition` → 收敛到表状态），修复完成后它将转为通过来验证修复
  - **GOAL**: 通过反例证明「`photo_status` 变化后 `images` 不刷新」这一根因
  - **Scoped PBT Approach**: bug 为确定性缺陷，先用具体失败用例锁定可复现性（trashed→active、purged→active），再用 kotest-property 的 `checkAll` 覆盖「任意初始非 active 状态 + markActive」的输入域
  - 用 `Room.inMemoryDatabaseBuilder` 构造 `PhotoStatusDao`，注入可控的 MediaStore 结果，实例化 `FolderDetailViewModel`；遵循既有约定（Android 单测中 `android.os.Build.MODEL` 为 null）
  - 断言对象为 ViewModel 暴露的 `images`（角标/透明度/分组均由 `FolderImage.status` 纯函数派生，可脱离 UI 直接断言）
  - 测试用例（来自 design.md「Exploratory Bug Condition Checking」）：
    - Trashed→Active 不刷新：初始一条 `trashed` 记录并 `loadImages`，随后 `dao.markActive(uri)`，断言对应 `FolderImage.isBackedUp == true`
    - Purged→Active 不刷新：初始 `purged`，`markActive` 后断言 `isBackedUp == true`
    - ON_RESUME 兜底缺失：加载后表发生变更，调用 `reloadStatuses()`，断言状态对齐到表
  - 在**未修复**代码上运行本测试
  - **EXPECTED OUTCOME**: 测试 FAIL（`images` 中对应项仍为旧 `status`、`isBackedUp == false`；未修复代码无 `reloadStatuses()`）
  - 记录反例（例如 "markActive(uri) 后 images 中该项 status 仍为 trashed，isBackedUp=false"）以理解根因
  - 当测试写好、运行、并记录了失败后即可标记本任务完成
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 2. 编写 Preservation 属性测试（在修复前，必须通过）
  - **Property 2: Preservation** - 一致状态下行为不变
  - **IMPORTANT**: 遵循 observation-first 方法论 —— 先在**未修复**代码上观察一致状态下的展示，再写测试固化该行为
  - 观察未修复代码在非 bug 输入（展示状态本已与表一致）下的输出：
    - 首次 `loadImages` 后按 `photo_status` 表产出的四态（未备份/已备份/回收站/已删除）
    - 无 `photo_status` 记录的照片显示为「未备份」（`status == null`、`isBackedUp/isTrashed/isPurged` 均为 false）
    - 给定 `images` 与筛选项时的过滤结果（全部/已备份/回收站/已删除）
  - 用 kotest-property（`Arb` + `checkAll`）编写属性测试，覆盖来自 design.md「Preservation Requirements」的行为：
    - 首次加载四态一致：随机生成一批含各种 `photo_status` 的照片，断言修复前后 `images` 首次加载结果一致（每张 `status` 与表关联正确）
    - 无记录即未备份：对无 `photo_status` 记录的任意照片，断言显示为「未备份」
    - 筛选逻辑不变：对任意 `images` 与筛选项，断言按状态过滤结果与修复前相同
    - 仅 trashed/purged 可触发对话框：对 `active`/未备份照片，断言长按不满足弹窗条件（`isTrashed || isPurged == false`）
  - 用 `Room.inMemoryDatabaseBuilder` + 可注入 MediaStore 结果，实例化 `FolderDetailViewModel` 断言
  - 在**未修复**代码上运行本测试
  - **EXPECTED OUTCOME**: 测试 PASS（确立需要保留的基线行为）
  - 当测试写好、运行、并在未修复代码上通过后即可标记本任务完成
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. 实现修复（A+B 组合方案）

  - [x] 3.1 在 PhotoStatusDao 新增观察式查询
    - 新增 `@Query("SELECT * FROM photo_status") fun observeAll(): Flow<List<PhotoStatus>>`
    - 保留既有 `suspend fun getAll(): List<PhotoStatus>`（供 `reloadStatuses()` 一次性读取复用），不改动其他既有方法，避免破坏现有调用方
    - 补充单元测试：插入/更新/`markActive` 后 `observeAll()` Flow 发射包含最新状态的列表（`Room.inMemoryDatabaseBuilder`）
    - _Bug_Condition: isBugCondition(input) —— 屏幕展示中且某照片渲染状态与表不一致（design.md Bug Condition）_
    - _Expected_Behavior: expectedBehavior(result) —— 展示状态收敛到 photo_status 表当前值（design.md Correctness Property 1）_
    - _Preservation: 不改动 getAll 及其他既有 DAO 方法语义（design.md Preservation Requirements）_
    - _Requirements: 2.1, 2.2, 2.3, 2.6_

  - [x] 3.2 改造 FolderDetailViewModel：缓存 MediaStore 结果 + 响应式合并
    - 新增 `private val _rawImages = MutableStateFlow<List<FolderImage>>(emptyList())`
    - `loadImages(folderUri)` 只负责执行较重的 `queryMediaStoreImages` + `MotionPhotoDetector`（不含 `status`）并写入 `_rawImages`；MediaStore 仅在首次加载与显式兜底刷新时扫描
    - 将 `images` 由手动赋值的 `MutableStateFlow` 改为对 `_rawImages` 与 `photoStatusDao.observeAll()` 的 `combine`：每次任一发射做一次 O(n) `associateBy { fileUri }` 关联，产出带最新 `status` 的 `List<FolderImage>`；用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())` 暴露
    - 关联为纯映射，不重跑 `queryMediaStoreImages` 与 `MotionPhotoDetector`
    - 新增 `reloadStatuses()`：一次性读取 `getAll()` 并与缓存的 `_rawImages` 重新关联刷新（`_rawImages` 为空时触发一次 MediaStore 重扫）；供 `ON_RESUME` 调用，覆盖 Flow 订阅在后台被停止期间的表变更
    - `rebackup()` 保持只入队 + 启动前台服务，**不**预写 `active`/调用 `markActive`；`photo_status` 仍只由上传成功后的 `StatusSyncManager.markActive` 更新（保证 2.4/2.5）
    - 补充单元测试：`combine` 在 `photo_status` 变化时重算 `images` 且不重跑 MediaStore 查询（用计数/桩验证 `queryMediaStoreImages` 仅调用一次）
    - _Bug_Condition: isBugCondition(input) —— displayedImages 与 statusTable 不一致（design.md Bug Condition）_
    - _Expected_Behavior: expectedBehavior(result) —— 每张照片角标/透明度/分组收敛到表；未完成/失败不显示为已备份（design.md Correctness Property 1）_
    - _Preservation: 首次加载四态、无记录即未备份、筛选逻辑、仅 trashed/purged 弹窗（design.md Preservation Requirements）_
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 3.3 在 FolderDetailScreen 增加 ON_RESUME 兜底刷新
    - 仿照 `LocalTab`，加入 `LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reloadStatuses() }`，覆盖「切走→上传完成→切回」场景
    - 保留既有 `LaunchedEffect(folderUri) { viewModel.loadImages(folderUri) }` 作为首次加载入口
    - 展示层 `StatusBadge`、alpha、`filteredImages` 仍基于 `FolderImage.status` 派生，无需改动映射逻辑（`images` 随表变化自动更新即收敛）
    - _Bug_Condition: isBugCondition(input) 且 screenVisible 由后台切回前台（design.md Bug Condition / 场景 2.6）_
    - _Expected_Behavior: 页面重新可见时依据当前 photo_status 表兜底刷新（design.md Correctness Property 1）_
    - _Preservation: 展示层派生逻辑不变（design.md Preservation Requirements）_
    - _Requirements: 2.6_

  - [x] 3.4 验证 Bug Condition 重现测试现在通过
    - **Property 1: Expected Behavior** - 展示状态收敛到 photo_status 表
    - **IMPORTANT**: 重新运行任务 1 中的**同一测试** —— 不要新写测试
    - 任务 1 的测试编码了期望行为，其通过即确认展示状态收敛到表当前值
    - 运行任务 1 的 Bug Condition 重现测试（含 trashed→active、purged→active、reloadStatuses 对齐、以及未完成不误报的边界）
    - **EXPECTED OUTCOME**: 测试 PASS（确认缺陷已修复，且未完成/失败上传不误报「已备份」）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 3.5 验证 Preservation 测试仍然通过
    - **Property 2: Preservation** - 一致状态下行为不变
    - **IMPORTANT**: 重新运行任务 2 中的**同一测试** —— 不要新写测试
    - 运行任务 2 的 Preservation 属性测试
    - **EXPECTED OUTCOME**: 测试 PASS（确认无回归：首次加载四态、无记录即未备份、筛选、仅 trashed/purged 弹窗均不变）
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 4. 补充集成测试（端到端与生命周期）
  - 全流程：进入详情页 → 对 `trashed` 照片触发 `rebackup` → 模拟上传成功触发 `markActive` → 断言 `images` 中该项角标/透明度/分组刷新为「已备份」（无需离开页面）
  - 生命周期：加载后模拟表变更 → 触发 `reloadStatuses()`（ON_RESUME 路径）→ 断言 UI 兜底对齐
  - 交互保持：对 `active`/未备份照片长按不满足弹「重新备份」对话框的条件（3.5）；筛选切换过滤正确（3.3）
  - 用 `Room.inMemoryDatabaseBuilder` + 可注入 MediaStore 结果，遵循既有测试约定
  - _Requirements: 2.1, 2.2, 2.3, 2.6, 3.3, 3.5_

- [x] 5. Checkpoint - 确保所有测试通过
  - 运行完整测试套件（单元测试、属性测试、集成测试），确保全部通过
  - 确认 Property 1（Bug Condition/Expected Behavior）与 Property 2（Preservation）均通过
  - 如有疑问或非预期失败，向用户确认后再继续

## Notes

- **测试顺序约束**：任务 1 的测试必须先在未修复代码上运行并 FAIL（证明缺陷），任务 2 的测试必须先 PASS（固化基线）。不要在此阶段修改代码去让任务 1 通过。
- **PBT 工具**：使用 `io.kotest:kotest-property:5.9.1`（仓库已有依赖），配合 `checkAll` / `Arb`；异步流断言配合 `kotlinx-coroutines-test`。
- **Room 测试约定**：使用 `Room.inMemoryDatabaseBuilder` 构造 `PhotoStatusDao`；注意 Android 单测中 `android.os.Build.MODEL` 为 null，涉及设备型号的分支需相应处理。
- **状态语义不变**：`rebackup()` 不预写 `active`，`photo_status` 仍只由上传成功后的 `StatusSyncManager.markActive` 更新，保证 2.4/2.5（未完成/失败不误报「已备份」）。
- **性能约束**：`photo_status` 变化时只做 O(n) `associateBy` 关联，绝不重跑 `queryMediaStoreImages` 与 `MotionPhotoDetector`（单测用计数/桩验证 MediaStore 查询仅调用一次）。
