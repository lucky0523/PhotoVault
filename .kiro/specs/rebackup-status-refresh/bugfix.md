# Bugfix Requirements Document

## Introduction

在 Android 客户端的文件夹详情页（`FolderDetailScreen`），对回收站中（trashed）或已删除（purged）的照片长按选择「重新备份」后，即使重新备份实际成功、本地 `photo_status` 表已被更新为 `active`，当前页面上的照片状态标记（回收站/已删除/已备份角标、置灰透明度、以及按状态分组的筛选）仍然停留在旧状态，不会刷新。

根因方向：`FolderDetailViewModel` 的 `images` 是一个 `MutableStateFlow`，只在 `loadImages(folderUri)` 调用时一次性读取 `photoStatusDao.getAll()` 快照，之后不再监听 `photo_status` 表的变化。而「重新备份」是异步操作——`rebackup()` 只是把文件入队并启动 `BackupForegroundService`，真正的上传在前台服务里由 `ChunkUploader.uploadFile` 执行，上传成功后才通过 `StatusSyncManager.markActive(uri, hash)` 把 `photo_status` 更新为 `active`。由于 UI 层没有对该表做响应式观察，也没有在上传完成时重新加载，导致状态角标不刷新。此外 `FolderDetailScreen` 目前不像 `LocalTab` 那样在 `ON_RESUME` 时做兜底刷新。

本次修复需要在重新备份实际完成、`photo_status` 更新为 `active` 之后，把新状态刷新到 `FolderDetailScreen` 的 UI，同时不能在上传尚未完成或上传失败时误报为「已备份」。

## Bug Analysis

### Current Behavior (Defect)

页面当前显示的照片状态在重新备份成功后不会更新，与本地 `photo_status` 表的实际状态不一致。

1.1 WHEN 用户在文件夹详情页对某张 trashed/purged 照片触发「重新备份」且该重新备份上传成功（`photo_status` 已被更新为 `active`）THEN the system 仍在该照片上显示旧的状态角标（回收站/已删除），不显示「已备份」角标
1.2 WHEN 该重新备份上传成功 THEN the system 仍将该照片缩略图以置灰的降低透明度（alpha 0.4）渲染，如同仍在回收站/已删除状态
1.3 WHEN 该重新备份上传成功 THEN the system 在按状态筛选/分组时仍将该照片归类为「回收站」或「已删除」，而不是「已备份」
1.4 WHEN 页面因失去焦点后重新回到前台变为可见 THEN the system 不会对照片状态做兜底刷新，页面继续显示与 `photo_status` 表不一致的旧状态

### Expected Behavior (Correct)

页面显示的照片状态应在重新备份实际完成、`photo_status` 更新后收敛到最新状态；在上传未完成或失败时不得误报。

2.1 WHEN 用户触发「重新备份」且上传成功导致 `photo_status` 变为 `active` THEN the system SHALL 在无需用户手动离开并重新进入页面的情况下，将该照片的角标更新为「已备份」（绿色对勾）
2.2 WHEN 该重新备份上传成功 THEN the system SHALL 将该照片缩略图恢复为全不透明（alpha 1.0）渲染
2.3 WHEN 该重新备份上传成功 THEN the system SHALL 在按状态筛选/分组时把该照片重新归类为「已备份」
2.4 WHEN 重新备份的上传尚未完成（仍在排队/上传中）THEN the system SHALL NOT 提前将该照片标记为「已备份」，仅在 `photo_status` 真正更新为 `active` 之后才反映该状态
2.5 WHEN 重新备份的上传失败（`photo_status` 未变为 `active`）THEN the system SHALL 保留该照片原有的回收站/已删除状态，不得误报为「已备份」
2.6 WHEN 页面重新回到前台变为可见 THEN the system SHALL 依据当前 `photo_status` 表对照片状态做兜底刷新，使显示状态与表数据保持一致

### Unchanged Behavior (Regression Prevention)

未触发该 bug 的既有行为必须保持不变。

3.1 WHEN 文件夹详情页首次加载 THEN the system SHALL CONTINUE TO 根据 `photo_status` 表显示每张照片正确的四态状态（未备份/已备份/回收站/已删除）
3.2 WHEN 某张照片没有对应的 `photo_status` 记录 THEN the system SHALL CONTINUE TO 将其显示为「未备份」（无角标、全不透明）
3.3 WHEN 用户选中某个状态筛选项 THEN the system SHALL CONTINUE TO 按所选状态正确过滤照片列表
3.4 WHEN 某张照片的状态没有发生变化（无重新备份、无服务端变更）THEN the system SHALL CONTINUE TO 显示与之前相同的角标与透明度
3.5 WHEN 用户长按一张 active 或未备份的照片 THEN the system SHALL CONTINUE TO 不弹出「重新备份」确认对话框（仅 trashed/purged 可触发）

## Bug Condition

### Bug Condition Function

标识会触发该 bug 的输入：文件夹详情页处于展示状态时，某张已展示照片的 `photo_status` 发生了变化（典型场景为重新备份完成后由 trashed/purged 变为 active），而页面并未重新调用 `loadImages`，导致展示状态与表数据不一致。

```pascal
FUNCTION isBugCondition(X)
  INPUT: X = (displayedImages, statusTable, screenVisible)
    displayedImages : list of photos currently rendered by FolderDetailScreen
    statusTable     : current photo_status table contents
    screenVisible   : whether the screen is currently displayed to the user
  OUTPUT: boolean

  // The bug manifests whenever a currently-displayed photo's rendered status
  // diverges from the authoritative photo_status table (e.g. a rebackup has
  // completed and flipped the record to active) while the screen is showing.
  RETURN screenVisible AND EXISTS p IN displayedImages SUCH THAT
           renderedStatus(p) <> statusTable.statusFor(p.uri)
END FUNCTION
```

### Property Specification (Fix Checking)

修复后，对所有触发 bug 的输入，页面展示状态最终应收敛为 `photo_status` 表中的当前状态。

```pascal
// Property: Fix Checking - displayed status converges to the status table
FOR ALL X WHERE isBugCondition(X) DO
  render ← FolderDetailScreen'(X)   // UI after the fix reacts to the change
  FOR ALL p IN render.displayedImages DO
    ASSERT render.badge(p)   = badgeFor(statusTable.statusFor(p.uri))
    ASSERT render.opacity(p) = opacityFor(statusTable.statusFor(p.uri))
    ASSERT render.filterGroup(p) = groupFor(statusTable.statusFor(p.uri))
  END FOR
  // A rebackup that has NOT completed (still queued/uploading) must not be
  // shown as backed up: the status only reflects what the table actually holds.
  ASSERT NOT prematurelyMarkedBackedUp(render)
END FOR
```

### Preservation Goal (Preservation Checking)

对所有不触发 bug 的输入（展示状态已与表一致），修复后行为与修复前完全一致。

```pascal
// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT FolderDetailScreen(X) = FolderDetailScreen'(X)
END FOR
```

**Key Definitions:**
- **F** (`FolderDetailScreen` / `FolderDetailViewModel`): 修复前的实现——`images` 仅在 `loadImages` 调用时读取 `photo_status` 快照，之后不再观察表变化，也无 `ON_RESUME` 兜底刷新。
- **F'**: 修复后的实现——在重新备份完成、`photo_status` 更新为 `active` 后，UI 展示状态收敛到表中的当前状态；上传未完成或失败时不误报「已备份」；页面重新可见时做兜底刷新。
