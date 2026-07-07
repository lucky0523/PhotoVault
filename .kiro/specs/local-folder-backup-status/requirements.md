# Requirements Document

## Introduction

本功能调整 Android 客户端中「本地 Tab」与「云端 Tab」两个界面的备份状态展示，并配套一处必要的服务端改动，使云端目录的分状态计数可被展示。

设计目标是让 **CloudTab 的视觉与交互风格与 LocalTab 对齐**：云端目录不再以 Card 卡片样式呈现，也不再在顶部展示整个服务器的汇总统计条（CloudStatsBar 移除）；取而代之，云端每个目录以 LocalTab 中 `FolderRow` 那样的紧凑列表行样式展示，并在每一行上复用共享的 `StatusChip` 组件展示该目录的分状态计数。

展示职责按数据归属拆分到两个 Tab：

- **本地 Tab**（`LocalTab.kt`）：每个文件夹行展示本地文件对应在云端的四个分状态计数——已备份（绿）、未备份（蓝）、回收站（橙）、已删除（红），使用与云端一致的 chip 样式；「未备份」蓝色与「回收站」橙色明确区分。本地侧数据仍复用现有实体列，无需新增本地数据层工作。
- **云端 Tab**（`CloudTab.kt`）：每个云端目录行展示与该目录相关的三个分状态计数——已备份（绿）、回收站（橙）、已删除（红），风格与 LocalTab 的 chip 一致。不再展示全局服务器汇总统计条。

本地 Tab 的数据复用现有的 `BackupFolder` Room 实体列（`totalImages`、`backedUpImages`、`trashedImages`、`purgedImages`），无需新增本地数据层工作。云端 Tab 的分状态计数**需要服务端改动**：当前 `GET /api/v1/files/browse` 每个目录只返回 `file_count`/`size`/`latest_file_time`，且其 SQL 使用 `deleted_at IS NULL AND purged_at IS NULL` 过滤掉了回收站/已删除文件；需要在目录聚合中按状态分桶并新增返回字段（详见需求 3 与术语表中的 **Browse_Directory_Aggregation**）。因此本功能采用**全栈改动**方案，范围包含必要的服务端 browse 聚合改动 + 客户端展示改动（详见需求 5）。

## Glossary

- **LocalTab**：Android Jetpack Compose 界面，文件路径为 `android/app/src/main/java/com/photovault/ui/main/tabs/LocalTab.kt`，以列表形式展示用户的本地备份文件夹。
- **FolderRow**：LocalTab 内渲染单个本地备份文件夹信息的 Composable，采用紧凑列表行样式。
- **CloudTab**：Android Jetpack Compose 界面，文件路径为 `android/app/src/main/java/com/photovault/ui/main/tabs/CloudTab.kt`，展示已备份到服务器上的云端目录与文件。
- **CloudDirectoryRow**：CloudTab 内渲染单个云端目录的列表行 Composable，采用与 FolderRow 对齐的紧凑列表行样式（非 Card 样式）。
- **StatusChip**：共享的状态计数 chip 组件，文件路径为 `android/app/src/main/java/com/photovault/ui/main/components/StatusChip.kt`，同时提供 `CloudStatusColors` 颜色定义；LocalTab 与 CloudTab 复用同一组件展示分状态计数。
- **CloudStatsBar**：CloudTab 顶部展示整个服务器汇总状态的统计条组件。本功能将其**移除**，不再做全局服务器汇总展示。
- **BackupFolder**：现有 Room 实体，提供 `totalImages`（文件夹内图片总数）、`backedUpImages`（本地与服务器端匹配的已备份数量）、`trashedImages`（本地文件在云端处于回收站的数量）、`purgedImages`（本地文件在云端已删除的数量）等列。
- **LocalBackedUp_Count**：本地已备份数量，取值为 `backedUpImages`（本地与服务器端匹配）。在 FolderRow 中以「已备份」标签展示。
- **LocalPending_Count**：本地未备份（待备份）数量，计算方式为 `totalImages − backedUpImages − trashedImages − purgedImages`，并向下限制为最小值 0（coerce ≥ 0）。在 FolderRow 中以「未备份」标签展示。
- **LocalTrashed_Count**：本地文件在云端处于回收站的数量，取值为 `trashedImages`，并向下限制为最小值 0（coerce ≥ 0）。在 FolderRow 中以「回收站」标签展示。
- **LocalPurged_Count**：本地文件在云端已删除的数量，取值为 `purgedImages`，并向下限制为最小值 0（coerce ≥ 0）。在 FolderRow 中以「已删除」标签展示。
- **DirBackedUp_Count**：单个云端目录的已备份数量，对应服务端目录聚合中状态为 `backed_up` 的文件计数（返回字段 `backed_up_count`）。在 CloudDirectoryRow 中以「已备份」chip 展示。
- **DirTrashed_Count**：单个云端目录的回收站数量，对应服务端目录聚合中状态为 `trashed` 的文件计数（返回字段 `trashed_count`）。在 CloudDirectoryRow 中以「回收站」chip 展示。
- **DirPurged_Count**：单个云端目录的已删除数量，对应服务端目录聚合中状态为 `purged` 的文件计数（返回字段 `purged_count`）。在 CloudDirectoryRow 中以「已删除」chip 展示。
- **Browse_Directory_Aggregation**：云端目录分状态计数的数据来源。指服务端 `GET /api/v1/files/browse`（`server/app/api/files.py` 的 `browse_directory`，服务层 `server/app/services/file_browse_service.py` 的 `list_directory`）在目录聚合中新增的按状态（`backed_up`/`trashed`/`purged`）分桶计数，并在 `DirectoryInfo` / `DirectoryInfoResponse` 新增 `backed_up_count`/`trashed_count`/`purged_count` 字段；Android 侧 `DirectoryInfo` 模型（`android/app/src/main/java/com/photovault/data/api/model/FileModels.kt`）需新增对应字段。
- **Status_Color**：各状态标签使用的颜色约定——已备份为绿色 `Color(0xFF34C759)`，未备份/待备份为蓝色 `Color(0xFF007AFF)`，回收站为橙色 `Color(0xFFFF9500)`，已删除为红色 `Color(0xFFFF3B30)`；未备份的蓝色须与回收站的橙色区分。

## Requirements

### 需求 1：本地 Tab 展示本地文件对应的云端状态

**用户故事：** 作为查看本地文件夹的用户，我希望在每个文件夹行看到该文件夹内本地文件对应在云端的状态分布（已备份 / 未备份 / 回收站 / 已删除），以便快速了解本地文件在云端的备份与生命周期状态。

#### 验收标准

1. WHERE 某个本地文件夹在 LocalTab 中被渲染，THE FolderRow SHALL 复用 StatusChip 组件展示 LocalBackedUp_Count（标签「已备份」）、LocalPending_Count（标签「未备份」）、LocalTrashed_Count（标签「回收站」）与 LocalPurged_Count（标签「已删除」）四个分状态计数。
2. THE FolderRow SHALL 将 LocalBackedUp_Count 取值为 BackupFolder 的 `backedUpImages`，LocalTrashed_Count 取值为 `trashedImages`，LocalPurged_Count 取值为 `purgedImages`。
3. THE FolderRow SHALL 将 LocalPending_Count 计算为 `totalImages − backedUpImages − trashedImages − purgedImages`，并向下限制为最小值 0。
4. THE FolderRow SHALL 使这四个分状态计数互斥，且不新增本地数据层改动，全部从 BackupFolder 的现有列派生。
5. THE FolderRow SHALL 将每个数量渲染为非负整数。
6. WHERE 某文件夹的 `totalImages` 为 0，THE FolderRow SHALL 将四个分状态计数均展示为 0。

### 需求 2：云端 Tab 每个目录行展示分状态计数

**用户故事：** 作为查看云端内容的用户，我希望每个云端目录行直接展示该目录的已备份、回收站、已删除数量，且风格与本地 Tab 一致，以便按目录快速了解云端文件的状态分布。

#### 验收标准

1. WHERE 某个云端目录在 CloudTab 中被渲染，THE CloudDirectoryRow SHALL 以与 LocalTab 的 FolderRow 对齐的紧凑列表行样式（非 Card 样式）展示该目录。
2. WHERE 某个云端目录在 CloudTab 中被渲染，THE CloudDirectoryRow SHALL 复用 StatusChip 组件展示 DirBackedUp_Count（「已备份」）、DirTrashed_Count（「回收站」）与 DirPurged_Count（「已删除」）三个分状态计数。
3. THE CloudDirectoryRow SHALL 将 DirBackedUp_Count、DirTrashed_Count 与 DirPurged_Count 各自渲染为非负整数。
4. THE CloudTab SHALL 不展示整个服务器的汇总统计条（CloudStatsBar 移除），不做全局服务器汇总展示。
5. IF 某个云端目录的 Browse_Directory_Aggregation 分状态字段尚不可用，THEN THE CloudDirectoryRow SHALL 将对应计数展示为 0 或加载中占位，而不阻塞该行其余元素的展示。

### 需求 3：云端目录分状态计数的数据来源

**用户故事：** 作为维护应用的开发者，我希望明确云端目录分状态计数的数据来源，以便在服务端 browse 接口上按状态聚合并新增返回字段。

#### 验收标准

1. THE Browse_Directory_Aggregation SHALL 在服务端 `GET /api/v1/files/browse` 的目录聚合中按状态 `backed_up`、`trashed`、`purged` 分别计数，并在 `DirectoryInfo` / `DirectoryInfoResponse` 新增返回字段 `backed_up_count`、`trashed_count`、`purged_count`。
2. THE Browse_Directory_Aggregation SHALL 使目录聚合覆盖回收站（`trashed`）与已删除（`purged`）状态的文件，而不再仅以 `deleted_at IS NULL AND purged_at IS NULL` 过滤后统计。
3. THE Android 侧 `DirectoryInfo` 模型 SHALL 新增与服务端返回字段对应的 `backed_up_count`、`trashed_count`、`purged_count` 字段。
4. THE CloudDirectoryRow SHALL 从 Browse_Directory_Aggregation 返回的 `backed_up_count`、`trashed_count`、`purged_count` 字段取得 DirBackedUp_Count、DirTrashed_Count 与 DirPurged_Count。

### 需求 4：状态标签颜色约定

**用户故事：** 作为用户，我希望每个状态标签具备可辨识的颜色，以便快速区分不同的备份状态。

#### 验收标准

1. THE FolderRow SHALL 使用绿色 `Color(0xFF34C759)` 渲染「已备份」标签。
2. THE FolderRow SHALL 使用蓝色 `Color(0xFF007AFF)` 渲染「未备份」标签，且该颜色须与「回收站」标签的橙色区分。
3. THE FolderRow SHALL 使用橙色渲染「回收站」标签，使用红色渲染「已删除」标签，与 CloudDirectoryRow 的对应 chip 颜色一致。
4. THE CloudDirectoryRow SHALL 使用绿色 `Color(0xFF34C759)` 渲染「已备份」chip。
5. THE CloudDirectoryRow SHALL 使用橙色渲染「回收站」chip。
6. THE CloudDirectoryRow SHALL 使用红色渲染「已删除」chip。

### 需求 5：全栈改动范围

**用户故事：** 作为维护应用的开发者，我希望明确本功能采用全栈改动方案，以便在受控范围内同时完成必要的服务端聚合改动与客户端展示改动。

#### 验收标准

1. THE 本功能 SHALL 包含服务端 `GET /api/v1/files/browse` 的目录分状态聚合改动（`browse_directory` / `list_directory` 及 `DirectoryInfo` / `DirectoryInfoResponse` 新增字段）。
2. THE 本功能 SHALL 包含客户端改动：Android `DirectoryInfo` 模型新增字段，CloudTab 以 CloudDirectoryRow 列表行样式复用 StatusChip 展示每个目录的分状态计数，并移除 CloudStatsBar。
3. THE 本功能 SHALL 使 LocalTab 的 FolderRow 从现有 BackupFolder 列 `totalImages`、`backedUpImages`、`trashedImages`、`purgedImages` 派生四个分状态计数（需求 1），并复用 StatusChip 展示，不新增本地数据层改动。
4. THE CloudTab SHALL 保留现有的路径面包屑导航、目录列表与文件列表等元素。
