# 设计文档

## Overview

本功能采用**全栈改动**方案，调整 Android 客户端「本地 Tab」（`LocalTab.kt`）与「云端 Tab」（`CloudTab.kt`）的备份状态展示，并配套一处必要的服务端改动，使云端目录的分状态计数可被逐目录展示。

- **本地 Tab（不变）**：`FolderRow` 只展示与本地相关的两项数量——已备份（`LocalBackedUp_Count`，绿色 `Color(0xFF34C759)`）与未备份（`LocalPending_Count`，琥珀色 `Color(0xFFFF9F0A)`）。数据复用现有 `BackupFolder` 的 `totalImages` 与 `backedUpImages` 列，无数据层改动。两项数量通过纯函数 `deriveLocalCounts` 派生，便于属性测试。本地侧行为保持不变。
- **服务端（新增聚合字段）**：`GET /api/v1/files/browse` 的目录聚合当前使用 `deleted_at IS NULL AND purged_at IS NULL` 过滤掉了回收站/已删除文件，且每个子目录只返回 `file_count`/`size`/`latest_file_time`。本设计改为**按状态分桶聚合**：为每个子目录分别统计 `backed_up`/`trashed`/`purged` 三类计数，并在 `DirectoryInfo` / `DirectoryInfoResponse` 新增 `backed_up_count`/`trashed_count`/`purged_count` 字段。状态派生逻辑与同文件中现有的 `get_device_stats` 保持一致（`purged_at → purged`，`elif deleted_at → trashed`，`else backed_up`）。
- **云端 Tab（列表行重设计）**：**移除顶部的 `CloudStatsBar` 全局汇总统计条**，不再做整个服务器的汇总展示。云端每个目录改以与 LocalTab `FolderRow` 对齐的紧凑列表行样式 `CloudDirectoryRow`（非 Card 样式）呈现，并在每一行复用共享的 `StatusChip` 展示该目录的三个分状态计数——已备份（绿）、回收站（橙）、已删除（红）。计数直接取自 browse 响应的新增字段。保留面包屑导航、目录列表与文件列表。

设计目标是把云端 Tab 的视觉与交互风格与本地 Tab 对齐，并把展示职责按数据归属拆分：本地 Tab 展示本地两项数量，云端 Tab 逐目录展示三项云端状态计数。

### 数据来源变更说明（Browse_Directory_Aggregation）

原设计曾尝试用 `GET /api/v1/files/devices`（设备级汇总）或 `GET /api/v1/files/status-sync` 为顶部统计条提供全局汇总数据。现需求改为**逐目录**展示分状态计数，全局统计条被移除，因此这两条来源不再用于云端展示：

- 云端目录的分状态计数改为在 `browse` 的目录聚合中直接按状态分桶产出，天然与用户当前浏览的目录层级对应，无需二次请求或客户端汇总。
- 由此，客户端原先用于统计条的 `CloudStatsBar`、`CloudStatsUiState`、`loadCloudStats()`、`cloudStats` 字段，以及纯聚合函数 `aggregateFromDevices` / `aggregateFromStatusSync` 均**不再被使用**。本设计据此进行死代码清理（详见「组件与接口 → 云端 Tab 侧 → 清理项」），避免遗留无用逻辑。服务端 `get_device_stats` 及 `DeviceStatsResponse` 因仍服务于其他用途予以保留。

## Architecture

```
服务端（Python / FastAPI / aiosqlite）
 files.py  GET /api/v1/files/browse → browse_directory
   └─ FileBrowseService.list_directory(user_id, path, ...)
       ├─ 查询目录内文件（不再预过滤 trashed/purged）
       ├─ 逐子目录按状态分桶：derive_file_status(deleted_at, purged_at)  ← 纯函数（可测）
       │    → backed_up_count / trashed_count / purged_count
       └─ 直接位于该目录下的文件列表仍只列 backed_up（展示语义不变）
   └─ DirectoryInfoResponse 新增 backed_up_count / trashed_count / purged_count

客户端（Android / Kotlin / Jetpack Compose）
 FileModels.kt  DirectoryInfo（新增 backedUpCount / trashedCount / purgedCount）

 LocalTab.kt（不变）
  └─ FolderRow(folder: BackupFolder)
      └─ deriveLocalCounts(folder): LocalBackupCounts   ← 纯函数（可测）
          └─ StatusChip("已备份", backedUp, 绿色 0xFF34C759)
          └─ StatusChip("未备份", pending,  琥珀色 0xFFFF9F0A)

 CloudTab.kt（重设计）
  ├─ (移除) CloudStatsBar 及其在 Column 顶部的调用
  └─ CloudDirectoryRow(directory: DirectoryInfo)          ← 对齐 FolderRow 的紧凑列表行
      ├─ 圆角着色方框内的文件夹图标
      ├─ 名称 + 紧凑副标题
      └─ Row { StatusChip("已备份", backedUpCount, 绿 0xFF34C759)
               StatusChip("回收站", trashedCount, CloudStatusColors.Trashed)
               StatusChip("已删除", purgedCount, CloudStatusColors.Purged) }
  └─ (保留) BreadcrumbNavigation / 目录列表 / 文件列表(FileItem)

 CloudTabViewModel（清理）
  ├─ CloudTabUiState 移除 cloudStats 字段
  ├─ 移除 CloudStatsUiState、loadCloudStats()
  └─ init{} 只触发 loadDirectory("/")
```

关键分层原则：状态派生逻辑集中在**纯函数**中——客户端的 `deriveLocalCounts`，服务端的 `derive_file_status`（新增，供逐目录分桶复用，与 `get_device_stats` 的判定一致）。Composable、ViewModel、服务层的 SQL 装配只负责调用与渲染/装配，纯逻辑可被属性测试完整覆盖。

## Components and Interfaces

### 服务端侧（Browse_Directory_Aggregation）

#### `DirectoryInfo` dataclass 新增字段

`server/app/services/file_browse_service.py`：

```python
@dataclass
class DirectoryInfo:
    """Information about a subdirectory."""

    name: str
    path: str
    file_count: int = 0
    size: int = 0
    latest_file_time: Optional[str] = None
    # 新增：按状态分桶的逐目录计数
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0
```

`file_count` 保持原语义（目录内文件总数，用于既有展示/兼容），新增三项为分状态计数。

#### 纯状态派生函数 `derive_file_status`

抽取 `get_device_stats` 中内联的判定逻辑为模块级纯函数，供目录聚合复用并便于属性测试：

```python
def derive_file_status(deleted_at: Optional[str], purged_at: Optional[str]) -> str:
    """Derive a file's status bucket from its lifecycle columns.

    Mirrors get_device_stats:
    - purged_at IS NOT NULL                      → "purged"
    - deleted_at IS NOT NULL (and not purged)    → "trashed"
    - otherwise                                  → "backed_up"
    """
    if purged_at is not None:
        return "purged"
    if deleted_at is not None:
        return "trashed"
    return "backed_up"
```

#### `list_directory` 目录聚合改动

改动要点：

1. 目录聚合所用的查询**不再** pre-filter `deleted_at IS NULL AND purged_at IS NULL`，改为一并读取 `deleted_at`、`purged_at` 列，使回收站与已删除文件也参与聚合。
2. 对每个子目录，按 `derive_file_status(...)` 的结果累加到 `backed_up_count` / `trashed_count` / `purged_count`；`file_count` 仍为该目录文件总数（三桶之和），`size` 与 `latest_file_time` 的既有语义保持不变。
3. **直接位于当前目录下的文件列表**（`files`）与其分页 `total_files` 的展示语义保持不变——仍只列出 `backed_up`（`deleted_at IS NULL AND purged_at IS NULL`）的文件，因为本次需求聚焦于「逐目录的分状态计数」，而非把回收站/已删除文件混入文件列表。

聚合骨架（示意）：

```python
cursor = await self._db.execute(
    """SELECT file_path, file_size, exif_time, created_at, deleted_at, purged_at
       FROM file_records
       WHERE user_id = ? AND file_path LIKE ?""",
    (user_id, f"{search_prefix}%"),
)
all_files = await cursor.fetchall()

for row in all_files:
    relative = row["file_path"][len(search_prefix):]
    parts = relative.split("/")
    if len(parts) > 1:
        subdir_name = parts[0]
        if not subdir_name:
            continue
        dir_info = directories.setdefault(
            subdir_name,
            DirectoryInfo(
                name=subdir_name,
                path=f"{path}/{subdir_name}" if path else subdir_name,
            ),
        )
        dir_info.file_count += 1
        dir_info.size += row["file_size"]
        status = derive_file_status(row["deleted_at"], row["purged_at"])
        if status == "purged":
            dir_info.purged_count += 1
        elif status == "trashed":
            dir_info.trashed_count += 1
        else:
            dir_info.backed_up_count += 1
        # latest_file_time 更新逻辑保持不变
```

> 注意：`file_path` 在文件被移入回收站后会变为 `.trash/...` 路径。若目录聚合按 `file_path` 前缀归属子目录，则回收站文件的归属目录可能随之改变。本设计以「按当前 `file_path` 前缀归属」为准（与现有 browse 的目录构建方式一致），`trashed`/`purged` 计数反映的是当前落在该 `file_path` 层级下的文件；这一取舍在实现与测试中需保持一致。

#### `DirectoryInfoResponse` 与 `browse_directory` 端点

`server/app/api/files.py`：

```python
class DirectoryInfoResponse(BaseModel):
    """Directory info in browse response."""

    name: str
    path: str
    file_count: int = 0
    size: int = 0
    latest_file_time: Optional[str] = None
    # 新增：逐目录分状态计数
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0
```

`browse_directory`（以及共用 `list_directory` 的 `list_files`）在装配 `DirectoryInfoResponse` 时透传新增字段：

```python
DirectoryInfoResponse(
    name=d.name,
    path=d.path,
    file_count=d.file_count,
    size=d.size,
    latest_file_time=d.latest_file_time,
    backed_up_count=d.backed_up_count,
    trashed_count=d.trashed_count,
    purged_count=d.purged_count,
)
```

### 本地 Tab 侧（不变）

#### 纯派生函数 `deriveLocalCounts`

保持现状（`LocalTab.kt` / 同包工具文件）：

```kotlin
data class LocalBackupCounts(
    val backedUp: Int,   // LocalBackedUp_Count
    val pending: Int     // LocalPending_Count
)

fun deriveLocalCounts(totalImages: Int, backedUpImages: Int): LocalBackupCounts {
    val backedUp = backedUpImages.coerceAtLeast(0)
    val pending = (totalImages - backedUpImages).coerceAtLeast(0)
    return LocalBackupCounts(backedUp = backedUp, pending = pending)
}

fun deriveLocalCounts(folder: BackupFolder): LocalBackupCounts =
    deriveLocalCounts(folder.totalImages, folder.backedUpImages)
```

#### `FolderRow` 集成（不变）

`FolderRow` 使用 `StatusChip` 展示「已备份」（绿 `0xFF34C759`）与「未备份」（琥珀 `0xFFFF9F0A`）两枚 chip，不展示云端状态。现有进度条/百分比逻辑保持不变。

### 云端 Tab 侧

#### 共享组件复用

复用 `com.photovault.ui.main.components` 下的 `StatusChip` 与 `CloudStatusColors`（已存在）：

```kotlin
object CloudStatusColors {
    val BackedUp = Color(0xFF34C759) // 绿色
    val Trashed  = Color(0xFFFF9500) // 橙色
    val Purged   = Color(0xFFFF3B30) // 红色
}
```

「已备份」chip 使用绿色 `Color(0xFF34C759)`（可直接用字面值或 `CloudStatusColors.BackedUp`），「回收站」用 `CloudStatusColors.Trashed`，「已删除」用 `CloudStatusColors.Purged`。

#### 列表行 Composable `CloudDirectoryRow`

以 `DirectoryItem`（Card 样式）替换为 `CloudDirectoryRow`（对齐 `FolderRow` 的紧凑列表行样式）：

```kotlin
@Composable
private fun CloudDirectoryRow(
    directory: DirectoryInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 圆角着色方框内的文件夹图标（对齐 FolderRow 的字形样式）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = directory.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${directory.fileCount} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 三枚分状态 chip：已备份 / 回收站 / 已删除
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("已备份", directory.backedUpCount, Color(0xFF34C759))
                StatusChip("回收站", directory.trashedCount, CloudStatusColors.Trashed)
                StatusChip("已删除", directory.purgedCount, CloudStatusColors.Purged)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = "进入目录",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

`CloudTab` 主体 `Column` 顶部**不再渲染 `CloudStatsBar`**；结构变为：`BreadcrumbNavigation` → `HorizontalDivider` → 内容区（目录列表用 `CloudDirectoryRow`，文件列表用 `FileItem`）。列表项之间可选用与 LocalTab 一致的细分割线（`HorizontalDivider`，`start` 缩进）。`FileItem` 可维持现有呈现或做轻度对齐；本次需求核心在于目录行携带三枚状态 chip。

分状态字段尚不可用时（旧版服务端未返回），`DirectoryInfo` 的三项计数默认为 0（见数据模型的默认值），对应 chip 展示为 0，不阻塞该行其余元素（满足需求 2.5）。

#### 清理项（移除死代码）

由于逐目录计数改由 browse 响应直接提供，以下与全局统计条相关的逻辑不再使用，随本功能一并移除：

- `CloudTab.kt`：移除 `CloudStatsBar` Composable 及其在 `Column` 顶部的调用；移除纯聚合函数 `aggregateFromDevices` 与 `aggregateFromStatusSync`；清理仅为它们服务的 import（`DeviceStatsResponse`、`StatusSyncItem` 等，若无其他引用）。
- `CloudTabViewModel.kt`：移除 `CloudStatsUiState` 数据类、`loadCloudStats()` 方法、`CloudTabUiState.cloudStats` 字段；`init{}` 中移除 `loadCloudStats()` 调用，仅保留 `loadDirectory("/")`。
- 服务端 `get_device_stats` / `DeviceStatsResponse` **予以保留**（服务于其他用途，非本功能引入的死代码）。

### 客户端模型改动 `DirectoryInfo`

`android/app/src/main/java/com/photovault/data/api/model/FileModels.kt`：

```kotlin
data class DirectoryInfo(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("file_count") val fileCount: Int,
    @SerializedName("latest_file_time") val latestFileTime: String?,
    // 新增：逐目录分状态计数，默认 0（兼容尚未返回该字段的服务端）
    @SerializedName("backed_up_count") val backedUpCount: Int = 0,
    @SerializedName("trashed_count") val trashedCount: Int = 0,
    @SerializedName("purged_count") val purgedCount: Int = 0
)
```

## Data Models

### 本地

无新增持久化模型。复用 `BackupFolder`（`totalImages`、`backedUpImages`）。展示派生结果用轻量 `LocalBackupCounts(backedUp, pending)`。

### 服务端目录聚合

`DirectoryInfo`（dataclass）与 `DirectoryInfoResponse`（Pydantic）均新增 `backed_up_count`、`trashed_count`、`purged_count`（默认 0）。三者与既有 `file_count`（目录内文件总数）满足 `backed_up_count + trashed_count + purged_count == file_count`。

### 云端 UI 状态

`CloudTabUiState` **移除** `cloudStats` 字段，`CloudStatsUiState` 数据类整体删除。云端目录的分状态计数不再走 UI 汇总状态，而是随 `DirectoryInfo` 列表项一并携带（`backedUpCount` / `trashedCount` / `purgedCount`）。

## Error Handling

- **本地派生：** `deriveLocalCounts` 对 `pending` 与 `backedUp` 做 `coerceAtLeast(0)`，保证任何输入（含 `backedUpImages > totalImages`、`totalImages == 0`）都产生非负结果，不抛异常。
- **服务端聚合：** 计数从 0 起累加，天然非负；`derive_file_status` 对任意 `(deleted_at, purged_at)` 组合都返回三桶之一，不存在未分类文件。
- **字段缺失兼容：** Android `DirectoryInfo` 的三项计数带默认值 0，旧版服务端未返回时反序列化为 0，`CloudDirectoryRow` 正常渲染 0，不阻塞面包屑/目录/文件列表（满足需求 2.5）。
- **目录/文件列表加载失败：** 沿用现有 `CloudTabViewModel.loadDirectory` 的错误态处理（`error` 展示与重试），不受本次改动影响。

## Testing Strategy

采用单元测试与属性测试互补：

- **属性测试（≥100 次迭代）：**
  - 客户端 `deriveLocalCounts`：使用 Kotlin 属性测试库（kotest-property / jqwik）生成随机 `Int`，覆盖 `total=0`、`backedUp>total` 等边界（见「正确性属性」Property 1）。
  - 服务端逐目录状态分桶：由于该逻辑为 Python，属性测试放在服务端测试套件（pytest + Hypothesis）中，针对纯函数 `derive_file_status` 与一个纯聚合辅助（对文件行列表按状态分桶）生成随机 `(deleted_at, purged_at)` 组合，验证「恰好归入一个桶」「三桶之和等于总数」「各计数非负」（见「正确性属性」Property 2）。
- **示例/组件测试：**
  - 断言 `FolderRow` 只渲染「已备份」「未备份」两类 chip（不含云端状态）。
  - 断言 `CloudDirectoryRow` 渲染「已备份」「回收站」「已删除」三枚 chip，且颜色符合约定（绿 `0xFF34C759` / `CloudStatusColors.Trashed` / `CloudStatusColors.Purged`）。
  - 断言 `CloudTab` 顶部不再渲染统计条，且面包屑、目录、文件列表正常渲染。
  - 服务端：给定包含 backed_up/trashed/purged 三态文件的目录，`browse` 响应中对应子目录返回正确的 `backed_up_count`/`trashed_count`/`purged_count`。
- **集成测试：** 以 mock `FileApi` 验证 `CloudTabViewModel.loadDirectory()` 将 browse 响应中的目录（含分状态计数）正确映射到 `uiState.directories`。

属性测试标签格式：**Feature: local-folder-backup-status, Property {number}: {property_text}**。

## Correctness Properties

*属性是指在系统所有有效执行中都应成立的特征或行为——它是关于系统应当做什么的形式化陈述，是人类可读规范与机器可验证正确性保证之间的桥梁。*

### Property 1: 本地数量派生正确且非负

*对于任意* 整数 `totalImages` 与 `backedUpImages`，`deriveLocalCounts` 返回的 `backedUp` 等于 `max(0, backedUpImages)`，`pending` 等于 `max(0, totalImages − backedUpImages)`，且两个分量均为非负整数；特别地，当 `totalImages == 0` 时两项均为 0。

**Validates: Requirements 1.2, 1.3, 1.5, 1.6**

### Property 2: 云端目录按状态分桶正确、互斥且守恒

*对于任意* 归属于某个云端目录的文件行列表（每行带 `deleted_at`、`purged_at` 两个可空字段），服务端目录聚合应使每个文件恰好被计入 `backed_up`、`trashed`、`purged` 三个桶中的且仅一个——`purged_at` 非空计入 `purged`，否则 `deleted_at` 非空计入 `trashed`，否则计入 `backed_up`；由此得到的 `backed_up_count`、`trashed_count`、`purged_count` 均为非负整数，且三者之和恰好等于该目录的文件总数（`file_count`）。

**Validates: Requirements 3.1, 3.2, 2.3**

> 说明：由于该聚合逻辑位于服务端（Python），其属性测试位于服务端测试套件（pytest + Hypothesis），针对纯函数 `derive_file_status(deleted_at, purged_at)` 及对文件行列表的纯分桶聚合进行，随机生成 `(deleted_at, purged_at)` 组合（含全空、部分空、两者皆非空等边界）验证上述互斥性与守恒性。客户端仅透传服务端返回的非负计数，不含可独立测试的派生逻辑。
