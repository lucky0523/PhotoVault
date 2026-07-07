# Implementation Plan: 本地/云端备份状态展示（local-folder-backup-status）

## Overview

本计划将设计拆解为一系列增量式编码任务。本地侧改动（共享 `StatusChip` 组件、`deriveLocalCounts` 纯派生函数、`FolderRow` 集成）已完成并验证；本地 Tab 现展示本地文件对应云端的**四个分状态计数**（已备份 / 未备份 / 回收站 / 已删除），全部从 `BackupFolder` 现有列（`totalImages`/`backedUpImages`/`trashedImages`/`purgedImages`）派生，无新增本地数据层改动。

新的工作为**云端 Tab 全栈重设计**：先落地服务端 `GET /api/v1/files/browse` 的目录分状态聚合（新增纯函数 `derive_file_status`、`DirectoryInfo` 分桶计数、响应字段透传），并配套服务端属性测试；再改动客户端——`DirectoryInfo` 模型新增字段、以 `CloudDirectoryRow` 紧凑列表行替换 Card 样式并复用 `StatusChip` 展示三枚分状态 chip、移除 `CloudStatsBar` 及相关死代码与其过时测试、补充组件测试；最后统一跑通服务端与 Android 测试。状态派生逻辑集中在纯函数（客户端 `deriveLocalCounts`、服务端 `derive_file_status`）中，便于属性测试覆盖；每一步均建立在前序步骤之上，不留孤立、未接入的代码。

## Tasks

- [x] 1. 建立共享 UI 组件与颜色约定
  - [x] 1.1 创建 StatusChip 与 CloudStatusColors
    - 新建 `android/app/src/main/java/com/photovault/ui/main/components/StatusChip.kt`
    - 实现可复用的 `StatusChip(label, count, color, modifier)` Composable：圆点 + 「标签 数量」文本，背景为 `color.copy(alpha = 0.15f)`，供本地/云端两个 Tab 共用
    - 定义 `CloudStatusColors` 对象：`BackedUp = Color(0xFF34C759)`（绿）、`Trashed = Color(0xFFFF9500)`（橙）、`Purged = Color(0xFFFF3B30)`（红）
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 2. 本地 Tab 展示本地文件对应的云端状态（四分状态）
  - [x] 2.1 实现 deriveLocalCounts 纯派生函数（四分状态）
    - `LocalBackupCounts(backedUp, pending, trashed, purged)` 数据类与 `deriveLocalCounts(totalImages, backedUpImages, trashedImages, purgedImages)` / `deriveLocalCounts(folder)` 纯函数，各计数均做 `coerceAtLeast(0)`；`pending = max(0, totalImages − backedUpImages − trashedImages − purgedImages)`
    - _Requirements: 1.2, 1.3, 1.5, 1.6, 5.3_

  - [x]* 2.2 编写 deriveLocalCounts 属性测试
    - **Property 1: 本地数量派生正确且非负**（覆盖四分状态：backedUp/trashed/purged 取 `max(0, ·)`，pending 为四列差值且 `coerceAtLeast(0)`，四项互斥非负）
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.5, 1.6**

  - [x] 2.3 在 FolderRow 中集成本地四项 chip
    - `FolderRow` 调用 `deriveLocalCounts(folder)`，复用 `StatusChip` 展示「已备份」(绿 `CloudStatusColors.BackedUp` `0xFF34C759`)、「未备份」(蓝 `0xFF007AFF`)、「回收站」(`CloudStatusColors.Trashed`)、「已删除」(`CloudStatusColors.Purged`) 四枚 chip，分两行排布避免挤压
    - _Requirements: 1.1, 1.4, 4.1, 4.2, 4.3, 5.3_

  - [x]* 2.4 编写 FolderRow 组件测试
    - 断言渲染「已备份」「未备份」「回收站」「已删除」四类 chip 且计数取自对应列派生；`totalImages == 0` 时四项均为 0
    - _Requirements: 1.1, 1.4, 1.6_

- [x] 3. 服务端目录分状态聚合（Browse_Directory_Aggregation）
  - [x] 3.1 在 file_browse_service 中新增分状态计数与纯派生函数
    - 修改 `server/app/services/file_browse_service.py`
    - 在 `DirectoryInfo` dataclass 新增 `backed_up_count: int = 0`、`trashed_count: int = 0`、`purged_count: int = 0` 三个字段（`file_count` 保持既有语义，为三桶之和）
    - 新增模块级纯函数 `derive_file_status(deleted_at, purged_at) -> str`，判定与现有 `get_device_stats` 一致：`purged_at` 非空 → `"purged"`，否则 `deleted_at` 非空 → `"trashed"`，否则 → `"backed_up"`
    - 改动 `list_directory` 的子目录聚合：查询不再 pre-filter `deleted_at IS NULL AND purged_at IS NULL`，改为一并读取 `deleted_at`/`purged_at` 列；对每个子目录按 `derive_file_status(...)` 结果累加到 `backed_up_count`/`trashed_count`/`purged_count`；`size` 与 `latest_file_time` 语义保持不变
    - 保持「直接位于当前目录下的文件列表」及 `total_files` 分页仍只列 `backed_up`（`deleted_at IS NULL AND purged_at IS NULL`），展示语义不变
    - _Requirements: 3.1, 3.2, 5.1_

  - [x] 3.2 在 files API 响应中透传分状态计数
    - 修改 `server/app/api/files.py`
    - 在 `DirectoryInfoResponse` 新增 `backed_up_count: int = 0`、`trashed_count: int = 0`、`purged_count: int = 0` 字段
    - `browse_directory`（及共用 `list_directory` 的装配处，如 `list_files`）在构建 `DirectoryInfoResponse` 时透传 `d.backed_up_count`/`d.trashed_count`/`d.purged_count`
    - _Requirements: 3.1, 5.1_

  - [x]* 3.3 编写服务端分状态分桶属性测试（pytest + Hypothesis）
    - **Property 2: 云端目录按状态分桶正确、互斥且守恒**
    - **Validates: Requirements 3.1, 3.2, 2.3**
    - 使用 Hypothesis 随机生成文件行列表（每行含可空 `deleted_at`/`purged_at`，覆盖全空、部分空、两者皆非空等边界），针对 `derive_file_status` 与对文件行列表的纯分桶聚合，断言：每个文件恰好计入一个桶、三项计数均非负、三桶之和等于目录文件总数（`file_count`）
    - 测试标签格式：`Feature: local-folder-backup-status, Property 2: ...`

- [ ] 4. 客户端云端 Tab 列表行重设计
  - [x] 4.1 在 DirectoryInfo 模型新增分状态字段
    - 修改 `android/app/src/main/java/com/photovault/data/api/model/FileModels.kt`
    - 在 `DirectoryInfo` 新增 `@SerializedName("backed_up_count") val backedUpCount: Int = 0`、`@SerializedName("trashed_count") val trashedCount: Int = 0`、`@SerializedName("purged_count") val purgedCount: Int = 0`（默认 0，兼容尚未返回该字段的服务端）
    - _Requirements: 3.3, 3.4, 5.2_

  - [x] 4.2 以 CloudDirectoryRow 替换 Card 样式并移除 CloudStatsBar 展示
    - 修改 `android/app/src/main/java/com/photovault/ui/main/tabs/CloudTab.kt`
    - 移除 `CloudStatsBar` Composable 及其在主体 `Column` 顶部的调用
    - 以对齐 `LocalTab` `FolderRow` 的紧凑列表行 `CloudDirectoryRow(directory, onClick)` 替换 Card 样式的 `DirectoryItem`：圆角着色方框内的文件夹图标 + 名称 + 紧凑副标题 + 一行三枚 `StatusChip`——「已备份」(绿 `Color(0xFF34C759)`)、「回收站」(`CloudStatusColors.Trashed`)、「已删除」(`CloudStatusColors.Purged`)，计数取自 `directory.backedUpCount`/`trashedCount`/`purgedCount`
    - 保留面包屑导航、目录列表与文件列表；分状态字段缺失时计数为 0，不阻塞该行其余元素
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 4.3, 4.4, 4.5, 5.2, 5.4_

  - [x] 4.3 清理云端汇总统计相关死代码与过时测试
    - 修改 `android/app/src/main/java/com/photovault/ui/main/tabs/CloudTab.kt`（`CloudTabViewModel` 所在文件）：移除 `CloudStatsUiState` 数据类、`loadCloudStats()` 方法、`CloudTabUiState.cloudStats` 字段、`init{}` 中 `loadCloudStats()` 调用（仅保留 `loadDirectory("/")`），以及纯聚合函数 `aggregateFromDevices` / `aggregateFromStatusSync` 与仅为其服务的 import
    - 删除/调整已失效的测试：`CloudStatsAggregateFromDevicesTest`、`AggregateFromStatusSyncTest`、`CloudStatsBarTest`，以及任何 `AggregateFromDevicesTest`/`CloudStatsAggregationTest`
    - 移除前先核实 `FileApi.getDeviceStats` 与 `DeviceStatsResponse` 是否被别处引用；如无其他用途方可移除，否则保留
    - _Requirements: 2.4, 5.2_

  - [ ]* 4.4 编写 CloudDirectoryRow 组件测试
    - 断言 `CloudDirectoryRow` 从 `DirectoryInfo` 渲染「已备份」「回收站」「已删除」三枚 chip 且计数取自对应字段，颜色符合约定（绿 `0xFF34C759` / `CloudStatusColors.Trashed` / `CloudStatusColors.Purged`）
    - 断言 `CloudTab` 顶部不再渲染 `CloudStatsBar`，面包屑、目录、文件列表仍正常渲染
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 4.3, 4.4, 4.5_

- [x] 5. Checkpoint - 确保全部测试通过
  - 服务端 pytest: 23 passed (test_file_browse_service.py + test_directory_status_buckets.py)
  - Android 编译: BUILD SUCCESSFUL (main + unit test sources)
  - Android 单测: LocalBackupStatusTest 通过

## Notes

- 标注 `*` 的子任务为可选（属性测试、组件测试），可为快速交付 MVP 而跳过
- 第 1、2 节（共享组件与本地 Tab）已完成并验证，保留为 `[x]`
- 每个任务均引用具体需求条目，保证可追溯性
- 本功能为全栈改动：服务端按状态分桶聚合 + 客户端逐目录展示；服务端 `get_device_stats` / `DeviceStatsResponse` 予以保留
- 同一文件的任务已拆分到不同波次以避免写入冲突（`CloudTab.kt` 的 4.2 → 4.3 依次串行）

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["3.1", "4.1"] },
    { "id": 1, "tasks": ["3.2", "3.3", "4.2"] },
    { "id": 2, "tasks": ["4.3"] },
    { "id": 3, "tasks": ["4.4"] }
  ]
}
```
