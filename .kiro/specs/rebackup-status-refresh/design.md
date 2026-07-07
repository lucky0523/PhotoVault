# rebackup-status-refresh Bugfix Design

## Overview

文件夹详情页（`FolderDetailScreen`）在对回收站（`trashed`）/已删除（`purged`）照片触发「重新备份」并上传成功后，不会刷新照片的状态角标、置灰透明度和筛选分组。根因是 `FolderDetailViewModel.images` 只是一份一次性快照——它仅在 `loadImages(folderUri)` 时读一次 `photoStatusDao.getAll()`，之后既不响应式观察 `photo_status` 表，也没有 `ON_RESUME` 兜底刷新；而 `rebackup()` 是异步的（入队 + 前台服务上传，成功后才由 `ChunkUploader` → `StatusSyncManager.markActive` 更新表）。

修复策略是让展示状态收敛到 `photo_status` 表的真实值，采用 **A+B 组合方案**：

- **方案 A（响应式合并）**：给 `PhotoStatusDao` 增加返回 `Flow<List<PhotoStatus>>` 的观察式查询，`FolderDetailViewModel` 缓存较重的 MediaStore 扫描结果，用 `combine`/`flatMapLatest` 把缓存的 MediaStore 列表与 `photo_status` Flow 合并；`photo_status` 变化时只做一次 O(n) 的状态关联重算，不重跑 `queryMediaStoreImages` 与 `MotionPhotoDetector`。这覆盖前台上传完成、进程仍存活时的实时刷新。
- **方案 B（生命周期兜底）**：在 `FolderDetailScreen` 加 `LifecycleEventEffect(ON_RESUME)` 调 `viewModel.reloadStatuses()`，覆盖上传发生在用户切走期间、Room Flow 观察被取消（订阅随 `viewModelScope`/`WhileSubscribed` 停止）而返回时需要重新对齐表数据的兜底场景（对应 2.6）。

修复严格遵循「UI 只反映 `photo_status` 表真实值」：`rebackup()` 不预写 `active`（不提前标记已备份，2.4），上传失败时表不变故显示保持原状（2.5）。首次加载、四态展示、筛选、无记录=未备份等既有行为保持不变（防回归 3.1–3.5）。

## Glossary

- **Bug_Condition (C)**: 触发缺陷的条件——`FolderDetailScreen` 处于展示状态时，某张已展示照片的渲染状态与权威的 `photo_status` 表当前值不一致（典型为重新备份完成后由 `trashed`/`purged` 变为 `active`，但 UI 未刷新）。
- **Property (P)**: 期望行为——UI 展示的角标、透明度、筛选分组最终收敛到 `photo_status` 表的当前值，且未完成/失败的上传不提前显示为「已备份」。
- **Preservation**: 修复必须保持不变的既有行为——首次加载的四态展示、无 `photo_status` 记录即「未备份」、筛选逻辑、以及仅对 `trashed`/`purged` 弹出重新备份对话框。
- **`FolderDetailViewModel`**: `android/.../ui/main/FolderDetailViewModel.kt` 中的 ViewModel，负责查询 MediaStore、关联 `photo_status`、并向 UI 暴露 `images: StateFlow<List<FolderImage>>`。
- **`photo_status` 表 / `PhotoStatus`**: Room 实体，以 `file_uri` 为主键记录每个文件的服务端状态（`active` / `trashed` / `purged`）；无记录视为「未备份」。这是照片状态的**唯一权威来源（single source of truth）**。
- **`PhotoStatusDao`**: `android/.../data/local/dao/PhotoStatusDao.kt`，`photo_status` 表的访问接口。
- **`FolderImage`**: UI 数据模型，携带 `uri`、元数据、`isMotionPhoto` 以及关联的 `status: PhotoStatus?`；派生出 `isBackedUp`/`isTrashed`/`isPurged`。
- **`StatusSyncManager.markActive`**: 上传成功后把 `photo_status` 记录置为 `active` 的写入点，是「状态真正变更」的时刻。
- **MediaStore 缓存**: `queryMediaStoreImages` + `MotionPhotoDetector` 得到的原始 `FolderImage` 列表（不含 `status`），扫描代价较重，缓存后在 `photo_status` 变化时复用。

## Bug Details

### Bug Condition

当 `FolderDetailScreen` 正在展示、且某张已渲染照片的**渲染状态**与 `photo_status` 表中该 `file_uri` 的**当前状态**发生偏离时，缺陷显现。最典型的场景：用户对 `trashed`/`purged` 照片触发重新备份，前台服务上传成功后 `StatusSyncManager.markActive` 已把记录改为 `active`，但 `FolderDetailViewModel.images` 是一次性快照，既没有观察表变化，也没有 `ON_RESUME` 兜底重载，导致 UI 停留在旧状态。

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input = (displayedImages, statusTable, screenVisible)
    displayedImages : list of FolderImage currently rendered by FolderDetailScreen
    statusTable     : current contents of the photo_status table
    screenVisible   : whether the screen is currently displayed to the user
  OUTPUT: boolean

  // 屏幕正在展示，且存在某张已展示照片的渲染状态与表中权威状态不一致。
  RETURN screenVisible AND EXISTS p IN displayedImages SUCH THAT
           renderedStatus(p) <> statusTable.statusFor(p.uri)
END FUNCTION
```

其中 `statusFor(uri)` 在表中无记录时返回「未备份」，`renderedStatus(p)` 为当前 UI 依据 `p.status` 计算出的四态展示。

### Examples

- 用户在某文件夹详情页对一张「回收站」照片点「重新备份」，上传成功、`photo_status` 变为 `active`。**期望**：角标变绿色对勾、缩略图恢复全不透明、筛选归入「已备份」。**实际**：仍显示橙色「回收站」角标 + alpha 0.4 + 归类「回收站」。
- 用户对一张「已删除」（purged）照片点「重新备份」并上传成功。**期望**：显示为「已备份」。**实际**：仍显示红色「已删除」角标。
- 用户点了「重新备份」后立即切到别的 App，上传在后台前台服务中完成，用户切回详情页。**期望**：回到前台后 UI 兜底刷新为「已备份」。**实际**：仍显示旧状态，除非手动退出再进入该页。
- 边界：用户点了「重新备份」但上传**尚未完成**（仍在排队/上传中）。**期望**：UI 保持原「回收站/已删除」状态，不提前显示「已备份」。（此为正确行为，修复后必须维持。）

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- 首次进入文件夹详情页，`loadImages` 仍按 `photo_status` 表正确展示每张照片的四态（未备份/已备份/回收站/已删除）。
- 没有 `photo_status` 记录的照片仍显示为「未备份」（无角标、alpha 1.0）。
- 状态筛选（全部/已备份/回收站/已删除）仍按所选状态正确过滤列表。
- 仅对 `trashed`/`purged` 照片长按弹出「重新备份」对话框；`active`/未备份照片长按不弹窗。
- 未发生状态变化的照片，角标与透明度与之前一致（不因刷新机制引入抖动或误改）。

**Scope:**
所有**不涉及 `photo_status` 变化**、且展示状态本已与表一致的输入应完全不受本次修复影响，包括：
- 首次加载后未发生任何状态变更的静态展示。
- 用户在页面内切换筛选项。
- 对 `active`/未备份照片的长按交互。

**Note:** 期望的正确刷新行为在下方 Correctness Properties 的 Property 1 中定义；本节聚焦于「什么必须保持不变」。

## Hypothesized Root Cause

根据缺陷描述，最可能的成因如下：

1. **一次性快照，无响应式观察**：`FolderDetailViewModel.loadImages` 只在被调用时读一次 `photoStatusDao.getAll()` 并写入 `_images`；`PhotoStatusDao` 没有返回 `Flow` 的查询，ViewModel 无从感知表变化。这是刷新缺失的主因。

2. **重新备份异步且写表点在别处**：`rebackup()` 只做 `backupQueue.enqueue(...)` + `BackupForegroundService.start(...)`；真正把 `photo_status` 改为 `active` 的是上传成功后的 `StatusSyncManager.markActive`。UI 与该写入点之间没有任何通知链路。

3. **缺少 `ON_RESUME` 兜底**：不同于 `LocalTab`（`LifecycleEventEffect(ON_RESUME)` → `syncStatusOnResume`），`FolderDetailScreen` 没有在重新可见时对齐表数据，无法覆盖「切走→上传完成→切回」的场景。

4. **重算代价顾虑导致的取舍**：`queryMediaStoreImages` 与 `MotionPhotoDetector` 较重，若每次表变化都整段重跑会有性能问题——因此需要「缓存 MediaStore 结果，仅重做 O(n) 状态关联」的合并设计，而非简单地每次全量 `loadImages`。

## Correctness Properties

Property 1: Bug Condition - 展示状态收敛到 photo_status 表

_For any_ 触发 bug 条件的输入（`isBugCondition` 返回 true，即屏幕展示中且某张照片的渲染状态与表不一致），修复后的 `FolderDetailScreen`/`FolderDetailViewModel` SHALL 在无需用户手动离开并重新进入页面的情况下，使每张已展示照片的角标、缩略图透明度、以及筛选分组收敛到 `photo_status` 表当前状态所对应的取值（`active`→绿色对勾 + alpha 1.0 + 归入「已备份」；`trashed`/`purged`→对应角标 + alpha 0.4；无记录→无角标 + alpha 1.0）；并且当某张照片的重新备份上传尚未完成或已失败（表中仍非 `active`）时，SHALL NOT 将其显示为「已备份」。

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6**

Property 2: Preservation - 一致状态下行为不变

_For any_ 不触发 bug 条件的输入（`isBugCondition` 返回 false，即展示状态已与 `photo_status` 表一致），修复后的实现 SHALL 产生与修复前完全相同的结果，保留首次加载的四态展示、无记录即「未备份」、筛选过滤逻辑、以及仅对 `trashed`/`purged` 弹出重新备份对话框等既有行为。

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

## Fix Implementation

### Changes Required

假设根因分析成立，改动集中在三个文件，且不改变 `photo_status` 的写入语义（`rebackup()` 不预写 `active`）。

**File**: `android/app/src/main/java/com/photovault/data/local/dao/PhotoStatusDao.kt`

**Function**: 新增观察式查询

**Specific Changes**:
1. **新增 `observeAll()`**：增加返回 `Flow<List<PhotoStatus>>` 的响应式查询，供 ViewModel 观察整表变化。
   - `@Query("SELECT * FROM photo_status") fun observeAll(): Flow<List<PhotoStatus>>`
   - 保留既有 `suspend fun getAll(): List<PhotoStatus>` 供 `reloadStatuses()` 的一次性读取（兜底路径）复用，避免破坏其他调用方。

**File**: `android/app/src/main/java/com/photovault/ui/main/FolderDetailViewModel.kt`

**Function**: `loadImages`（改造）、新增 `reloadStatuses`、`images` 暴露方式

**Specific Changes**:
2. **缓存 MediaStore 结果**：新增 `private val _rawImages = MutableStateFlow<List<FolderImage>>(emptyList())`，`loadImages(folderUri)` 只负责执行较重的 `queryMediaStoreImages` + `MotionPhotoDetector`（不含 `status`）并写入 `_rawImages`；MediaStore 只在首次加载和显式兜底刷新时扫描。
3. **响应式合并**：将 `images` 由手动赋值的 `MutableStateFlow` 改为对 `_rawImages` 与 `photoStatusDao.observeAll()` 的 `combine`：每当任一发射，做一次 O(n) 的 `associateBy { fileUri }` 关联，产出带最新 `status` 的 `List<FolderImage>`。用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())` 暴露为 `StateFlow`。关联为纯映射，不重跑 MediaStore 查询与 motion photo 检测。
4. **`reloadStatuses()` 兜底**：新增无参方法，一次性读取当前 `photo_status`（`getAll()`）并与缓存的 `_rawImages` 重新关联刷新（或在采用 A 的前提下作为强制重新对齐；若 `_rawImages` 为空则触发一次 MediaStore 重扫）。供 `ON_RESUME` 调用，覆盖 Flow 订阅在后台被停止期间发生的表变更。
5. **不改写状态语义**：`rebackup()` 保持只入队 + 启动前台服务，**不**调用任何 `markActive`/预写；`photo_status` 仍只由上传成功后的 `StatusSyncManager.markActive` 更新（保证 2.4/2.5）。

**File**: `android/app/src/main/java/com/photovault/ui/main/FolderDetailScreen.kt`

**Function**: `FolderDetailScreen`（Composable）

**Specific Changes**:
6. **ON_RESUME 兜底刷新**：仿照 `LocalTab`，加入 `LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reloadStatuses() }`，覆盖「切走→上传完成→切回」场景（2.6）。保留既有 `LaunchedEffect(folderUri) { viewModel.loadImages(folderUri) }` 作为首次加载入口。
7. **展示层无需改动映射逻辑**：`StatusBadge`、alpha、`filteredImages` 仍基于 `FolderImage.status` 派生；由于 `images` 现在会随表变化自动更新，这些既有逻辑无改动即可收敛（保持防回归）。

## Testing Strategy

### Validation Approach

先在**未修复**代码上写测试重现缺陷（surface counterexamples），确认根因；再验证修复满足 Fix Checking 且不破坏 Preservation。核心断言对象是 ViewModel 暴露的 `images`（角标/透明度/分组均由 `FolderImage.status` 纯函数派生，可脱离 UI 直接断言状态）。

### Exploratory Bug Condition Checking

**Goal**: 在修复前重现缺陷，确认「`photo_status` 变化后 `images` 不更新」这一根因；若重现失败则需重新假设根因。

**Test Plan**: 用内存版 Room（`Room.inMemoryDatabaseBuilder`）构造 `PhotoStatusDao`，配合可注入的 MediaStore 结果，实例化 `FolderDetailViewModel`，先 `loadImages`，再对某 `file_uri` 执行 `markActive`，观察 `images` 是否反映新状态。在**未修复**代码上运行以观察失败。

**Test Cases**:
1. **Trashed→Active 不刷新**：初始一条 `trashed` 记录并加载，随后 `dao.markActive(uri)`，断言对应 `FolderImage.isBackedUp == true`（未修复代码将失败）。
2. **Purged→Active 不刷新**：同上，初始 `purged`（未修复代码将失败）。
3. **ON_RESUME 兜底缺失**：模拟表在加载后发生变更、调用 `reloadStatuses()`，断言状态对齐（未修复代码无此方法/不刷新，将失败）。
4. **边界 - 未完成不误报**：`rebackup()` 入队但未 `markActive` 时，断言照片仍为 `trashed`/`purged`（此为期望行为，修复后仍须通过）。

**Expected Counterexamples**:
- `markActive` 后 `images` 中对应项 `status` 仍为旧值，`isBackedUp` 为 false。
- 可能成因：`images` 为一次性快照、`PhotoStatusDao` 无 `Flow` 查询、无 `ON_RESUME` 兜底。

### Fix Checking

**Goal**: 验证对所有触发 bug 条件的输入，修复后展示状态收敛到 `photo_status` 表当前值。

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  render := FolderDetailViewModel_fixed.imagesAfterConverge(input)
  FOR ALL p IN render DO
    ASSERT badge(p)       = badgeFor(statusTable.statusFor(p.uri))
    ASSERT opacity(p)     = opacityFor(statusTable.statusFor(p.uri))
    ASSERT filterGroup(p) = groupFor(statusTable.statusFor(p.uri))
  END FOR
  ASSERT NOT prematurelyMarkedBackedUp(render)  // 未完成/失败不显示为已备份
END FOR
```

### Preservation Checking

**Goal**: 验证对所有不触发 bug 条件（展示状态已与表一致）的输入，修复后结果与修复前完全一致。

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT FolderDetailViewModel_original.images(input)
       = FolderDetailViewModel_fixed.images(input)
END FOR
```

**Testing Approach**: 推荐用属性测试（property-based testing）做 preservation 检查，因为：
- 能在输入域内自动生成大量用例；
- 能覆盖手写单测遗漏的边界；
- 能对「非 bug 输入行为不变」给出较强保证。

**Test Plan**: 先在未修复代码上观察一致状态下的展示（首次加载、无记录、筛选），再写属性测试捕获该行为并在修复后验证不变。

**Test Cases**:
1. **首次加载四态一致**：观察未修复代码按表正确产出四态，写测试验证修复后 `images` 首次加载结果不变。
2. **无记录即未备份**：观察无 `photo_status` 记录的照片显示为「未备份」（无角标、alpha 1.0），验证修复后一致。
3. **筛选逻辑不变**：对给定 `images` 与筛选项，验证 `filteredImages` 过滤结果与修复前相同。

### Unit Tests

- `PhotoStatusDao.observeAll()`：插入/更新/`markActive` 后 Flow 发射包含最新状态的列表。
- `FolderDetailViewModel`：`combine` 合并在 `photo_status` 变化时重算 `images` 且不重跑 MediaStore 查询（用计数/桩验证 `queryMediaStoreImages` 仅调用一次）。
- `reloadStatuses()`：一次性读取并对齐缓存的 `_rawImages`。
- `FolderImage` 派生：`isBackedUp/isTrashed/isPurged` 与 `status` 的映射；无 `status` 即未备份。

### Property-Based Tests

- 生成随机 `photo_status` 表变更序列，断言 `images` 最终收敛到表的当前状态（Property 1）。
- 生成随机的一致状态输入（展示与表一致），断言修复前后 `images` 相等（Property 2 preservation）。
- 生成「重新备份入队但未 `markActive`」的场景，断言不出现「已备份」误报（2.4/2.5）。

### Integration Tests

- 全流程：进入详情页 → 对 `trashed` 照片点重新备份 → 模拟上传成功触发 `markActive` → 断言角标/透明度/分组刷新为「已备份」（无需离开页面）。
- 生命周期：加载后模拟表变更 → 触发 `ON_RESUME` → 断言 UI 兜底对齐（2.6）。
- 交互保持：对 `active`/未备份照片长按不弹「重新备份」对话框（3.5）；筛选切换正确（3.3）。
