# Requirements Document

## Introduction

备份链路存在一个数据损坏缺陷：当相机（如 Google 动态照片 MVIMG）仍在写入 / finalize 一个媒体文件时，后台扫描可能把它选中并上传。由于此时通过 `ContentResolver.openInputStream` 读到的是「一段已写入的真实数据 + 预分配区域的零填充」，而 `MediaStore.SIZE` 报的是预分配后的最终大小，客户端会把这坨零填充数据当成完整文件上传。更糟的是，快照哈希是基于这坨损坏数据本身计算的，因此服务器的 SHA-256 完整性校验也会通过，损坏文件被当作正常文件永久存储。

实测案例：`MVIMG_20260707_134509.jpg` 最终真实大小 6.82MB（含 Ultra HDR 增益图 + 内嵌 MP4），但云端存成了 26.00MiB（恰好 13×2MB 分片），其中仅前 ~641KB 是有效数据，其余 ~26MB 全为 `0x00`，且丢失了全部动态照片结构。

本 spec 覆盖三项防护（对应用户已确认的 1/2/3），不包含自动重新备份（4）：

1. 扫描时跳过「最近刚修改、可能仍在写入」的文件，留待后续扫描处理已 finalize 的版本。
2. 查询 MediaStore 时过滤掉 `IS_PENDING` 的媒体项。
3. 上传前对快照做合法性校验（尾部零填充 / 大小一致性 / 图片文件结构），不合法则中止上传并留待重试。

平台约束：改动集中在 Android 端 `BackgroundScanWorker`（扫描 / 查询）与 `ChunkUploader`（快照 / 校验）。服务器端逐字节存储与哈希校验行为不变。

## Requirements

### Requirement 1: 跳过最近修改（疑似仍在写入）的媒体文件

**User Story:** 作为用户，我希望备份不要抓取相机还没写完的照片，这样云端就不会出现残缺 / 零填充的损坏文件。

#### Acceptance Criteria

1. WHEN 扫描枚举到一个媒体项 AND 其 `DATE_MODIFIED` 距当前时间小于配置的「静默期」(quiet period, 默认 120 秒) THEN 系统 SHALL 跳过该文件，不将其加入本次备份队列。
2. WHEN 一个文件因处于静默期被跳过 THEN 系统 SHALL NOT 更新该文件夹的 `lastScanTime` 到会导致该文件被永久漏扫的值（即被跳过的新文件必须能在后续扫描中被重新发现）。
3. WHEN 静默期已过（文件不再是「最近修改」）AND 该文件仍未备份 THEN 后续扫描 SHALL 正常将其纳入备份队列。
4. WHERE 触发方式是手动「立即备份」(forceFullScan) OR 周期性扫描 THE 静默期跳过逻辑 SHALL 一致生效。
5. IF `DATE_MODIFIED` 不可用或为 0 THEN 系统 SHALL 不因该规则跳过该文件（避免因缺失时间戳导致文件永远无法备份）。
6. WHEN 一个文件因静默期被跳过 THEN 系统 SHALL 记录一条可诊断日志（文件名 + 修改时间距今秒数）。

### Requirement 2: 查询 MediaStore 时排除 pending 项

**User Story:** 作为用户，我希望备份忽略系统标记为「尚未就绪(pending)」的媒体，这样正在被写入的文件不会进入备份流程。

#### Acceptance Criteria

1. WHERE 运行在 Android 10 (API 29) 及以上 WHEN 查询 `MediaStore.Images` / `MediaStore.Video` 集合用于备份扫描 THEN 系统 SHALL 在查询条件中加入 `IS_PENDING = 0`，仅返回已就绪的媒体项。
2. WHERE 运行在 Android 10 以下（无 `IS_PENDING` 概念）THEN 系统 SHALL 保持既有查询行为，不因缺少该列而查询失败。
3. WHEN pending 过滤应用于备份扫描查询 (`queryMediaStore`) THEN 计数查询 (`countInCollection`) SHALL 采用一致口径，使「总数 / 已备份 / 待备份」统计不因过滤规则不一致而错位。
4. WHEN `IS_PENDING = 0` 过滤生效 THEN 已 finalize 的正常媒体项 SHALL NOT 被误排除。

### Requirement 3: 上传前的快照合法性校验

**User Story:** 作为用户，我希望即使一个未写完的文件绕过了扫描过滤，客户端也能在上传前发现它是残缺的，从而拒绝上传损坏数据。

#### Acceptance Criteria

1. WHEN `createSnapshot` 生成快照后 AND 计算出快照实际大小 THEN 系统 SHALL 将快照实际大小与 `MediaStore.SIZE` 进行比较，AND IF 两者不严格相等（不允许任何字节容差）THEN 判定为「未就绪」，中止本次上传并返回可重试结果 (shouldRetry = true)，NOT 上传该快照。
2. WHEN 快照文件末尾存在异常大的连续零字节区域（尾部零填充，指示预分配未写满）THEN 系统 SHALL 判定为「未就绪」，中止上传并返回可重试结果。
3. WHERE 文件为图片类型 WHEN 校验快照结构 THEN 系统 SHALL 尽可能按其格式校验完整性标记，覆盖尽量多的常见格式，IF 校验判定不完整 THEN 判定为「未就绪」并中止上传。至少应覆盖：
   - JPEG (jpg/jpeg)：以 `FF D9` (EOI) 结尾；
   - PNG：以 `IEND` 块 + CRC (`49 45 4E 44 AE 42 60 82`) 结尾；
   - GIF：以 trailer `0x3B` 结尾，且头部为 `GIF87a`/`GIF89a`；
   - WebP (RIFF)：头部 `RIFF....WEBP`，且 RIFF 声明的 chunk 长度与文件实际大小一致；
   - HEIC/HEIF / AVIF (ISO-BMFF)：头部含 `ftyp` box，且顶层 box 长度之和与文件大小一致（结构完整、无截断）。
4. WHEN 快照因上述任一校验失败而被中止 THEN 系统 SHALL 记录可诊断日志（文件名、mediaStoreSize、snapshotSize、失败原因），AND 保留本地上传记录以便下次重试。
5. WHEN 校验失败导致中止 THEN 系统 SHALL 清理该次已创建的临时快照文件，不残留缓存。
6. WHEN 快照通过全部校验（大小一致、无异常尾部零填充、结构完整）THEN 系统 SHALL 照常继续既有的分片上传与完成流程，不改变正常文件的上传行为。
7. WHERE 文件为视频类型或本 spec 未覆盖结构校验的格式 THE 系统 SHALL 至少执行大小一致性与尾部零填充校验（不因缺少格式专属结构校验而放行明显损坏的文件）。

### Requirement 4: 可配置与安全默认值

**User Story:** 作为维护者，我希望这些防护有合理的默认阈值且不会把正常文件误判为损坏，避免因过度保护导致文件永远无法备份。

#### Acceptance Criteria

1. THE 静默期阈值 SHALL 有一个明确的默认值（默认 120 秒）并集中定义为具名常量（NOT 散落的魔法数字），便于后续调整。
5. THE 快照大小一致性校验 SHALL 采用严格相等（零容差），不引入任何字节级容差。
2. THE 尾部零填充判定 SHALL 使用明确阈值（例如尾部连续零字节超过某比例 / 绝对字节数）以避免把正常以少量零结尾的文件误判为损坏。
3. IF 任一校验规则的判断依据不可用（如无法读取 SIZE、无法判定类型）THEN 系统 SHALL 采取「不因该单条规则误杀」的保守策略，优先保证正常文件可被备份。
4. THE 所有跳过 / 中止行为 SHALL 产生结构化日志，便于事后定位为何某文件未被备份。

### Requirement 5: 备份字节与设备原图完全一致

**User Story:** 作为用户，我希望备份到云端的文件与手机上的原始文件逐字节完全相同（包括位置等 EXIF 元数据），这样云端就是真正的原图，不丢失任何信息。

#### Acceptance Criteria

1. WHERE 运行在 Android 10 (API 29) 及以上 WHEN 应用为备份读取媒体字节（快照 / 预检哈希 / EXIF 提取所用的输入流）THEN 系统 SHALL 在打开输入流前对该媒体 URI 调用 `MediaStore.setRequireOriginal`，以获取未经位置脱敏的原始字节。
2. THE 应用清单 (AndroidManifest.xml) SHALL 声明 `ACCESS_MEDIA_LOCATION` 权限，AND 应用 SHALL 在运行时按需申请该权限。
3. WHEN 已授予 `ACCESS_MEDIA_LOCATION` AND 使用 `setRequireOriginal` 读取一个含位置 EXIF 的媒体 THEN 上传得到的字节 SHALL 与设备上该文件的原始字节逐字节一致（相同 SHA-256）。
4. IF `ACCESS_MEDIA_LOCATION` 未被授予 OR `setRequireOriginal` 在当前设备/URI 上不适用或抛出异常 THEN 系统 SHALL 回退到普通读取方式完成备份（不因无法取得原始字节而使备份失败），AND 记录一条可诊断日志说明发生了回退。
5. WHERE 读取使用了 `setRequireOriginal` THE 需求 3 的大小一致性校验 SHALL 以「原始读取所得字节大小」为准进行比较，避免脱敏读取与 `MediaStore.SIZE` 之间的差异被误判为损坏。
6. WHERE 运行在 Android 10 以下 THE 系统 SHALL 保持既有读取行为（该版本 `openInputStream` 已返回完整原始文件）。

## Glossary

- **静默期 (Quiet Period):** 从文件 `DATE_MODIFIED` 到当前时间之间的最小间隔。小于该间隔的文件被视为「可能仍在写入」，本轮扫描跳过。默认 120 秒，定义为具名常量。
- **Pending 项:** Android 10+ 中 `MediaStore.MediaColumns.IS_PENDING = 1` 的媒体，表示创建方（相机 / 下载器）尚未 finalize，内容可能不完整。
- **快照 (Snapshot):** `ChunkUploader.createSnapshot` 通过 `openInputStream` 复制到应用缓存的临时文件，作为哈希与分片的稳定字节来源。
- **尾部零填充 (Trailing Zero Padding):** 文件末尾的连续 `0x00` 区域，是相机预分配文件后尚未写满时的典型特征，用于识别残缺 / 未完成文件。
- **finalize:** 创建方完成文件写入并将其标记为就绪、更新最终大小/元数据的过程。
- **forceFullScan:** 用户手动触发「立即备份」时的扫描模式，忽略 `lastScanTime` 重新枚举全部媒体。
- **MediaStore.SIZE:** MediaStore 报告的文件字节大小，在文件被预分配但未写完时可能等于最终分配大小而非当前有效内容大小。
