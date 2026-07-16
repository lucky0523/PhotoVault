# 需求文档：手机图片备份系统

## 简介

本系统是一套手机图片备份解决方案，包含部署在 x86 Linux NAS 上的 Python 服务端，以及 Android 和 iOS 手机客户端。系统支持公网和局域网访问，能够在手机后台自动检测新增图片并在满足条件时进行备份，支持多用户、断点续传、重复检测，并提供灵活的存储目录结构配置。

## 术语表

- **Backup_Server**: 部署在 x86 Linux NAS 上的 Python 服务端程序，负责接收、存储和管理备份图片
- **Mobile_Client**: 运行在 Android 或 iOS 设备上的手机客户端应用程序
- **Backup_Task**: 一次从 Mobile_Client 向 Backup_Server 传输图片文件的操作
- **Backup_Condition**: Mobile_Client 发起备份所需满足的前置条件集合（电量大于50%且处于WiFi网络）
- **File_Hash**: 用于唯一标识文件内容的 SHA-256 哈希值，用于检测重复备份
- **Chunk**: 断点续传中将文件分割后的数据块（固定大小 2MB）
- **Storage_Root**: Backup_Server 上存储备份文件的根目录（如 /data）
- **Device_Name**: 手机设备的型号名称（如 Pixel9Pro）
- **Source_Folder**: 手机上被选定进行备份的文件夹路径
- **Storage_Policy**: 针对每个 Source_Folder 配置的存储策略，包含两个正交选项：是否手动指定目录、是否按年月分层
- **Media_File**: 待备份的媒体文件，包含图片（Image）和视频（Video）两大类
- **Motion_Photo**: Android 动态照片，一个 JPEG 图片文件尾部内嵌了一段 MP4 视频，通过图片 XMP 元数据（`GCamera:MotionPhoto` / `MicroVideoOffset` 或 Container 中语义为 `MotionPhoto` 的 Item）描述内嵌视频，遵循 [Android Motion Photo 规范](https://developer.android.com/media/platform/motion-photo-format)
- **Ultra_HDR**: 遵循 [Android Ultra HDR 规范](https://developer.android.com/media/platform/hdr-image-format) 的 JPEG 照片，内含 SDR 基础图 + 增益图（gain map），通过图片 XMP 中的 `hdr-gain-map`（`hdrgm`）命名空间或语义为 `GainMap` 的 Container Item 标识
- **Media_Type**: 文件的媒体类型标记，取值为 `image` 或 `video`，由服务端根据 MIME 类型 / 扩展名推断并持久化
- **Backup_Queue**: 内存中的待备份文件队列（`BackupQueue`），进程被杀后丢失
- **Auto_Backup_Switch**: 设置中的"自动备份"开关，默认开启，其状态持久化保存（对应 R-3.8）
- **Upload_Record**: Room `upload_records` 表中的一条断点续传记录（`UploadRecord` 实体），记录某文件已上传的分块进度、会话标识、文件名、文件大小、修改时间、所属文件夹等；进程被杀或备份中断后仍持久保留，是断点续传的依据
- **In_Flight_File**: 关闭 Auto_Backup_Switch 时，正在上传（已开始传输、已产生持久化 Upload_Record）的那个文件
- **Queued_Not_Started_File**: 在 Backup_Queue 中尚未开始上传、因而没有 Upload_Record 的文件
- **Paused_Task**: 因关闭 Auto_Backup_Switch 而被保留、并在"备份任务"Tab 展示为"已暂停"的未完成上传条目；数据来源为持久化的 Upload_Record，而非内存 Backup_Queue
- **Pause_Source**: 一个 Paused_Task 或当前备份暂停的来源，取值为 `USER`（用户手动暂停）、`CONDITION`（条件暂停：电量不足/WiFi 断开）、`AUTO_OFF`（因关闭自动备份而暂停）
- **Tasks_Tab**: "备份任务"Tab 页
- **Session_Expiry**: Upload_Record 的有效期，自 `created_at` 起 7 天；超过 7 天视为过期

## 需求

### 需求 1：网络连接

**用户故事：** 作为用户，我希望手机客户端能够通过公网和局域网连接到服务端，以便在不同网络环境下都能进行图片备份。

#### 验收标准

1. WHEN Mobile_Client 发起连接且 Backup_Server 在局域网内于 10 秒内响应连接请求时，THE Mobile_Client SHALL 优先通过局域网地址建立与 Backup_Server 的连接
2. WHEN Mobile_Client 发起连接且 Backup_Server 在局域网内未于 10 秒内响应，但通过公网地址于 15 秒内响应连接请求时，THE Mobile_Client SHALL 通过公网地址建立与 Backup_Server 的连接
3. IF Mobile_Client 在 15 秒内无法通过局域网地址和公网地址连接到 Backup_Server，THEN THE Mobile_Client SHALL 显示包含失败原因（网络不可用或服务端无响应）的错误信息，并在下一次满足 Backup_Condition 时自动重试连接
4. WHEN Mobile_Client 与 Backup_Server 成功建立连接时，THE Mobile_Client SHALL 显示连接成功状态指示，包含当前连接方式（局域网或公网）

### 需求 2：原图备份

**用户故事：** 作为用户，我希望备份的是手机中的图片原图，以便保留图片的完整质量和元数据。

#### 验收标准

1. WHEN Backup_Task 执行时，THE Mobile_Client SHALL 读取并传输图片文件的原始数据（包括 EXIF 等元数据），不进行任何压缩或格式转换
2. WHEN Backup_Server 接收到图片文件时，THE Backup_Server SHALL 以原始文件格式和原始文件大小存储该文件，并保留文件的全部元数据
3. WHEN Backup_Task 完成后，THE Backup_Server SHALL 通过比较已存储文件与 Mobile_Client 报告的原始文件的哈希值来验证文件完整性，确认两者完全一致
4. IF 文件完整性验证失败（哈希值不一致），THEN THE Backup_Server SHALL 将该文件标记为备份失败，通知 Mobile_Client 重新传输该文件，并丢弃已存储的损坏副本

### 需求 3：后台自动备份

**用户故事：** 作为用户，我希望手机客户端能在后台低功耗常驻，并在发现新增图片且满足条件时自动备份，以便无需手动操作即可完成备份。

#### 验收标准

1. THE Mobile_Client SHALL 在后台以低功耗模式常驻运行，CPU 占用不超过 5%，内存占用不超过 50MB
2. WHILE Mobile_Client 在后台运行时，THE Mobile_Client SHALL 每 15 分钟扫描一次 Source_Folder，识别自上次扫描以来的新增图片文件
3. WHEN Mobile_Client 检测到新增图片文件且当前满足 Backup_Condition 时，THE Mobile_Client SHALL 按文件创建时间从早到晚的顺序自动发起 Backup_Task
4. WHILE Backup_Condition 满足（设备电量大于 50% 且网络处于 WiFi 连接）时，THE Mobile_Client SHALL 逐个上传待备份图片，每个文件上传完成后确认服务端已接收再处理下一个
5. IF 设备电量降至 50% 或以下，THEN THE Mobile_Client SHALL 暂停当前 Backup_Task，记录已完成的进度，并在电量恢复至 55% 以上时从中断点继续上传
6. IF 网络从 WiFi 切换到蜂窝网络或网络完全断开，THEN THE Mobile_Client SHALL 暂停当前 Backup_Task，记录已完成的进度，并在 WiFi 重新连接后从中断点继续上传
7. IF 单个文件上传失败，THEN THE Mobile_Client SHALL 最多重试 3 次（每次间隔 30 秒），若仍失败则跳过该文件继续处理队列中的下一个文件，并将失败文件标记为待重试
8. THE Mobile_Client SHALL 在"设置 → 备份条件"中提供一个"自动备份"开关，默认开启，其状态持久化保存
9. WHILE "自动备份"开关处于开启状态时，THE Mobile_Client SHALL 允许所有自动触发方式（周期扫描、媒体库变化监听、开机重调度、升级后全量回扫、条件恢复续传、进程被杀后的队列重建）在满足 Backup_Condition 时发起 Backup_Task
10. WHILE "自动备份"开关处于关闭状态时，THE Mobile_Client SHALL 仅在用户点击本地 Tab 的"立即备份"FAB 时发起 Backup_Task；所有自动触发方式 SHALL NOT 入队新文件或启动备份服务（但仍可扫描以刷新各文件夹的状态/计数）
11. WHILE "自动备份"开关处于关闭状态时，THE Mobile_Client SHALL 在自动扫描时冻结各文件夹的 lastScanTime，以便开关重新开启后增量扫描仍能发现关闭期间新增的文件
12. WHEN 用户点击"立即备份"FAB 时，THE Mobile_Client SHALL 无视"自动备份"开关状态执行一次全量扫描与备份（仍需满足网络/电量/服务端连通等前置条件）
13. WHEN 备份前台服务启动一次 Backup_Task 时，THE Mobile_Client SHALL 记录该次任务的来源类型（手动或自动），作为后续判定"关闭自动备份开关时是否中止该任务"的依据
14. WHILE 一次**自动**发起的 Backup_Task 正在进行（上传中或排队中）时，IF 用户关闭"自动备份"开关，THEN THE Mobile_Client SHALL 立即停止该 Backup_Task、清空 Backup_Queue 中尚未上传的排队文件、并停止备份前台服务（正在上传中的当前文件保留其断点续传进度以便再次开启后续传）
15. WHILE 一次**手动**发起的 Backup_Task（"立即备份"FAB、单张"重新备份"、任务页"重试"）正在进行时，IF 用户关闭"自动备份"开关，THEN THE Mobile_Client SHALL 继续该手动任务直至完成，SHALL NOT 清空其队列或停止服务

### 需求 4：多用户支持

**用户故事：** 作为 NAS 管理员，我希望服务端支持多用户登录，以便家庭中多个成员可以各自备份图片到独立的存储空间。

#### 验收标准

1. THE Backup_Server SHALL 支持创建、删除和修改密码操作来管理用户账户，最多支持 20 个用户账户
2. WHEN 用户通过 Mobile_Client 提交用户名和密码登录时，THE Backup_Server SHALL 验证用户凭证并返回有效期为 24 小时的认证令牌
3. IF 用户提交的用户名不存在或密码不匹配，THEN THE Backup_Server SHALL 拒绝登录请求并返回认证失败的错误提示，不透露具体失败原因
4. WHILE 用户已认证时，THE Backup_Server SHALL 将该用户的备份文件存储在以用户名命名的独立目录中
5. THE Backup_Server SHALL 确保每个用户仅能访问自己的备份文件，不能访问其他用户的文件
6. IF 请求携带的认证令牌已过期或无效，THEN THE Backup_Server SHALL 拒绝该请求并返回令牌无效的错误提示，要求用户重新登录

### 需求 5：断点续传

**用户故事：** 作为用户，我希望在网络中断或备份暂停后能够从断点继续传输，以便避免重新传输已上传的数据。

#### 验收标准

1. WHEN Mobile_Client 开始传输一个文件时，THE Mobile_Client SHALL 将文件分割为固定大小的 Chunk（每个 Chunk 大小为 2MB，最后一个 Chunk 允许小于 2MB）进行顺序传输，并为每个 Chunk 生成校验值
2. WHEN Backup_Task 因网络中断或备份前置条件不满足而暂停时，THE Mobile_Client SHALL 将当前已成功传输的 Chunk 序号及文件标识持久化存储至本地
3. WHEN Backup_Task 恢复且距上次中断不超过 7 天时，THE Mobile_Client SHALL 从上次中断的 Chunk 位置继续传输，不重新传输已成功确认的 Chunk
4. WHEN Backup_Server 接收到续传请求时，THE Backup_Server SHALL 返回该文件已成功接收的 Chunk 序号列表
5. WHEN 文件所有 Chunk 传输完成后，THE Backup_Server SHALL 将所有 Chunk 合并为完整文件，并通过比对客户端提供的文件哈希值与合并后文件的哈希值来验证文件完整性
6. IF 文件完整性验证失败，THEN THE Backup_Server SHALL 丢弃已合并的文件，向 Mobile_Client 返回校验失败的错误指示，并由 Mobile_Client 重新传输该文件的所有 Chunk
7. IF Backup_Task 恢复时源文件的修改时间或文件大小与中断前记录不一致，THEN THE Mobile_Client SHALL 废弃之前的续传记录并重新从第一个 Chunk 开始传输

### 需求 6：重复检测

**用户故事：** 作为用户，我希望系统能检测哪些图片已经被备份过，以便避免重复备份浪费带宽和存储空间。

#### 验收标准

1. WHEN Mobile_Client 准备备份一张图片时，THE Mobile_Client SHALL 使用 SHA-256 算法计算该图片文件的 File_Hash
2. WHEN Mobile_Client 向 Backup_Server 发起备份请求时，THE Mobile_Client SHALL 发送该图片的 File_Hash 和文件路径信息
3. WHEN Backup_Server 收到备份请求时，IF 相同 File_Hash 的文件已存在于该用户的备份记录中，THEN THE Backup_Server SHALL 返回"已备份"状态并跳过该文件的传输
4. WHEN Backup_Server 返回"已备份"状态时，THE Mobile_Client SHALL 将该图片标记为已备份并跳过传输
5. IF Mobile_Client 在发送重复检测请求后 30 秒内未收到 Backup_Server 的响应，THEN THE Mobile_Client SHALL 将该图片视为未备份并尝试正常上传流程
6. WHEN Backup_Server 收到备份请求时，IF 相同 File_Hash 的文件已存在于该用户的备份记录中但文件路径不同，THEN THE Backup_Server SHALL 在目标路径创建对已有文件的引用而不重复存储文件内容，并返回"已备份"状态

### 需求 7：存储目录结构

**用户故事：** 作为用户，我希望备份文件按照"用户名/设备型号"的结构存储，以便在 NAS 上清晰地组织不同用户和设备的备份文件。

#### 验收标准

1. THE Backup_Server SHALL 以 `{Storage_Root}/{用户名}/{Device_Name}/` 作为每个设备的备份基础路径
2. WHEN 用户首次从某设备备份时，THE Backup_Server SHALL 根据 Mobile_Client 报告的设备型号自动创建对应的用户名目录和 Device_Name 目录
3. THE Backup_Server SHALL 对 Device_Name 进行字符验证，仅允许字母、数字、下划线和连字符，将不合法字符替换为下划线
4. IF 目录创建失败（如磁盘空间不足或权限不足），THEN THE Backup_Server SHALL 拒绝该备份任务并向 Mobile_Client 返回包含失败原因的错误提示

### 需求 8：文件夹存储策略配置

**用户故事：** 作为用户，我希望能够针对不同的备份文件夹选择不同的存储策略，以便灵活地组织备份文件的目录结构。

#### 验收标准

1. THE Mobile_Client SHALL 允许用户为每个 Source_Folder 独立配置 Storage_Policy
2. THE Storage_Policy SHALL 包含两个正交选项："是否手动指定存储目录"（默认关闭）和"是否按年月分层"（默认关闭）
3. WHEN 用户配置 Storage_Policy 时，THE Mobile_Client SHALL 允许用户独立设置这两个选项的任意组合
4. WHEN 用户保存 Storage_Policy 配置后，THE Mobile_Client SHALL 将配置持久化存储，确保应用重启后配置不丢失
5. WHEN 用户修改已有 Source_Folder 的 Storage_Policy 时，THE Mobile_Client SHALL 仅对后续新增的备份文件应用新策略，已备份的文件不受影响

### 需求 9：存储路径模式 — 保留原始路径

**用户故事：** 作为用户，当我选择"保留原始存储路径"时，我希望 NAS 端完整复制手机中的文件目录结构，以便在 NAS 上还原手机中的文件组织方式。

#### 验收标准

1. IF Storage_Policy 设置为"不手动指定存储目录"，THEN THE Backup_Server SHALL 将文件存储在 `{Storage_Root}/{用户名}/{Device_Name}/{Source_Folder的完整路径}/` 下，逐级复制 Source_Folder 中从根目录起的每一层文件夹，目标路径中的文件夹层级、名称与源路径完全一致
2. IF Storage_Policy 设置为"不手动指定存储目录"且 Source_Folder 包含子文件夹，THEN THE Backup_Server SHALL 在目标路径中递归创建所有子文件夹结构（最大支持 20 层嵌套深度），仅创建包含待备份文件的子文件夹路径
3. IF Source_Folder 路径中包含目标文件系统不支持的字符，THEN THE Backup_Server SHALL 拒绝该备份任务并向用户返回错误提示，指明包含不兼容字符的文件夹名称
4. IF 目标路径中单个文件夹名称超过 255 字符或完整路径超过 4096 字符，THEN THE Backup_Server SHALL 拒绝该备份任务并向用户返回错误提示，指明超出长度限制的路径

**示例：** 用户名 alice，设备 Pixel9Pro，Source_Folder 为 /DCIM/Camera/
- NAS 存储路径：`/data/alice/Pixel9Pro/DCIM/Camera/`
- 子文件夹 /DCIM/Camera/burst/ 中的文件存储在：`/data/alice/Pixel9Pro/DCIM/Camera/burst/`

### 需求 10：存储路径模式 — 手动指定目录

**用户故事：** 作为用户，当我选择"手动指定存储目录"时，我希望将备份文件存储到我指定的 NAS 路径中，同时保留手机原始目录结构。

#### 验收标准

1. WHEN Storage_Policy 设置为"手动指定存储目录"时，THE Mobile_Client SHALL 允许用户输入一个 NAS 端的目标路径，路径长度不超过 255 个字符，且仅包含字母、数字、斜杠（/）、下划线（_）、连字符（-）和点（.）
2. WHEN Storage_Policy 设置为"手动指定存储目录"时，THE Backup_Server SHALL 将文件存储在 `{用户指定的目标路径}/{Source_Folder的完整路径}/` 下，保留手机中的完整目录结构
3. IF 用户输入的目标路径在 NAS 上不存在，THEN THE Backup_Server SHALL 自动创建该目录层级，并继续执行备份操作
4. IF 用户输入的目标路径无写入权限或路径格式不合法，THEN THE Mobile_Client SHALL 在备份开始前显示错误提示，指明路径不可用的原因，并阻止备份任务启动

**示例：** 用户名 alice，手动指定目标路径 /data/alice/travel，Source_Folder 为 /DCIM/Camera/
- NAS 存储路径：`/data/alice/travel/DCIM/Camera/`

### 需求 11：存储路径模式 — 年月分层

**用户故事：** 作为用户，当我选择"按年月分层"时，我希望备份的图片按照拍摄时间的年份和月份进行归档，以便按时间维度浏览和管理图片。

#### 验收标准

1. WHEN Storage_Policy 设置为"按年月分层"时，THE Backup_Server SHALL 按以下优先级提取时间：首先使用 EXIF 拍摄时间，若不存在则使用文件创建时间，并以4位年份和2位零填充月份（如 `2026/03`）作为目录名
2. WHEN Storage_Policy 设置为"按年月分层"且"不手动指定存储目录"时，THE Backup_Server SHALL 将文件存储在 `{Storage_Root}/{用户名}/{Device_Name}/{Source_Folder路径}/{年}/{月}/` 下
3. WHEN Storage_Policy 设置为"按年月分层"且 Source_Folder 包含子文件夹时，THE Backup_Server SHALL 在每个子文件夹路径下分别进行年月分层，即 `{基础路径}/{子文件夹路径}/{年}/{月}/`
4. WHEN Storage_Policy 设置为"按年月分层"且"手动指定存储目录"时，THE Backup_Server SHALL 将文件存储在 `{用户指定的目标路径}/{Source_Folder路径}/{年}/{月}/` 下
5. IF 图片文件既无 EXIF 拍摄时间也无可用的文件创建时间，THEN THE Backup_Server SHALL 将该文件存储在 `{基础路径}/unknown_date/` 目录下，并在备份结果中标记该文件为"时间未知"
6. IF 目标年月目录下已存在同名文件，THEN THE Backup_Server SHALL 比较文件内容，若内容相同则跳过该文件，若内容不同则在文件名后追加数字后缀（如 `xxx_1.jpg`）后存储

**示例：** 用户名 alice，设备 Pixel9Pro，Source_Folder 为 /DCIM/Camera/，图片拍摄于2026年3月
- 不手动指定 + 按年月分层：`/data/alice/Pixel9Pro/DCIM/Camera/2026/03/xxx.jpg`
- 子文件夹 burst 中的文件：`/data/alice/Pixel9Pro/DCIM/Camera/burst/2026/03/xxx.jpg`
- 手动指定 /data/alice/travel + 按年月分层：`/data/alice/travel/DCIM/Camera/2026/03/xxx.jpg`

### 需求 12：正交选项组合

**用户故事：** 作为用户，我希望"是否按年月分层"和"是否手动指定目录"是两个完全独立的选项，以便自由组合使用。

#### 验收标准

1. THE Mobile_Client SHALL 将"是否手动指定存储目录"和"是否按年月分层"作为两个独立的配置项呈现给用户，两个选项各自默认为关闭状态
2. THE Mobile_Client SHALL 支持以下四种组合，并按对应规则生成存储路径：
   - 不手动指定目录 + 不按年月分层：文件存储到 `{Storage_Root}/{用户名}/{Device_Name}/{Source_Folder路径}/` 下
   - 不手动指定目录 + 按年月分层：文件存储到 `{Storage_Root}/{用户名}/{Device_Name}/{Source_Folder路径}/{年}/{月}/` 下
   - 手动指定目录 + 不按年月分层：文件存储到 `{用户指定的目标路径}/{Source_Folder路径}/` 下
   - 手动指定目录 + 按年月分层：文件存储到 `{用户指定的目标路径}/{Source_Folder路径}/{年}/{月}/` 下
3. WHEN 用户修改其中一个选项时，THE Mobile_Client SHALL 保持另一个选项的值不变
4. WHEN 用户设置两个选项后退出并重新打开配置页面时，THE Mobile_Client SHALL 显示用户上次保存的选项值

### 需求 13：登录界面

**用户故事：** 作为用户，我希望通过登录界面输入服务器地址和凭证来连接 NAS 服务端，以便安全地访问我的备份服务。

#### 验收标准

1. THE Mobile_Client SHALL 在启动时显示登录界面，用户必须成功登录后才能进入功能操作界面
2. THE 登录界面 SHALL 包含三个输入框：服务器地址、用户名、密码
3. THE 登录界面 SHALL 提供"测试连接"按钮，WHEN 用户点击该按钮时，THE Mobile_Client SHALL 使用当前输入的服务器地址尝试连接 Backup_Server，并在 10 秒内显示连接成功或失败的结果
4. THE 登录界面 SHALL 包含"记住密码"复选框，WHEN 用户勾选该复选框并成功登录后，THE Mobile_Client SHALL 将用户名和密码加密存储在本地，下次启动时自动填充
5. IF 用户未勾选"记住密码"，THEN THE Mobile_Client SHALL 仅保存服务器地址和用户名，不保存密码
6. WHEN 用户点击登录按钮时，THE Mobile_Client SHALL 验证三个输入框均不为空，若有空字段则提示用户填写
7. IF 登录失败（凭证错误或服务器不可达），THEN THE Mobile_Client SHALL 在登录界面显示具体的错误原因，不跳转到功能界面

### 需求 14：功能界面布局

**用户故事：** 作为用户，我希望功能界面通过底部 Tab 页进行导航，以便快速切换不同功能模块。

#### 验收标准

1. THE Mobile_Client 功能界面底部 SHALL 包含四个 Tab 页：本地、云端、备份任务、设置
2. WHEN 用户点击"本地"Tab 时，THE Mobile_Client SHALL 显示手机本地的图片文件夹列表及其备份状态
3. WHEN 用户点击"云端"Tab 时，THE Mobile_Client SHALL 显示已备份到 Backup_Server 上的文件和目录结构
4. WHEN 用户点击"备份任务"Tab 时，THE Mobile_Client SHALL 显示当前正在进行的备份任务、排队中的任务以及历史备份记录
5. WHEN 用户点击"设置"Tab 时，THE Mobile_Client SHALL 显示应用设置选项，包括备份条件配置、存储策略管理、账户信息和退出登录
6. THE Mobile_Client SHALL 在 Tab 切换时保持各页面的状态，不丢失用户的浏览位置或操作进度
7. THE Mobile_Client SHALL 默认显示"本地"Tab 页作为登录后的首页

### 需求 15：HTTPS 与反向代理

**用户故事：** 作为 NAS 管理员，我希望通过 Caddy 反向代理提供 HTTPS 访问，以便在公网环境下安全传输数据。

#### 验收标准

1. THE Backup_Server SHALL 支持通过 Caddy 反向代理提供 HTTPS 服务，Caddy 负责 TLS 证书的自动获取和续期
2. WHEN 部署在公网环境时，THE 系统 SHALL 通过 Caddy 自动从 Let's Encrypt 获取并管理 TLS 证书
3. WHEN 部署在纯局域网环境时，THE 系统 SHALL 支持 Caddy 使用自签证书或允许通过 HTTP 直接访问
4. THE FastAPI 服务端 SHALL 监听本地端口（如 127.0.0.1:8000），由 Caddy 将外部请求反向代理到该端口
5. THE 系统 SHALL 提供 Caddy 配置文件模板（Caddyfile），用户仅需修改域名即可完成部署

### 需求 16：存储根目录可配置

**用户故事：** 作为 NAS 管理员，我希望能够自定义服务端的存储根目录路径，以便将备份文件存储到指定的磁盘或分区。

#### 验收标准

1. THE Backup_Server SHALL 提供配置文件（如 `config.yaml`），允许管理员设置 Storage_Root 路径
2. WHEN Storage_Root 配置项未设置时，THE Backup_Server SHALL 使用默认路径 `/data/photovault`
3. WHEN Backup_Server 启动时，THE Backup_Server SHALL 验证 Storage_Root 路径存在且具有读写权限，若不满足则输出错误日志并拒绝启动
4. THE Backup_Server SHALL 支持在运行时通过管理员 API 查看当前 Storage_Root 路径及磁盘使用情况

### 需求 17：Web 端上传功能

**用户故事：** 作为用户，我希望能够通过 Web 浏览器上传图片到 NAS，以便从电脑端也能备份图片。

#### 验收标准

1. THE Web 前端 SHALL 提供文件上传功能，支持拖拽上传和点击选择文件上传
2. THE Web 前端 SHALL 支持批量上传多个文件，并显示每个文件的上传进度
3. WHEN 用户通过 Web 端上传文件时，THE Web 前端 SHALL 使用与移动端相同的分块上传和重复检测机制
4. THE Web 前端 SHALL 允许用户在上传时选择目标存储路径和存储策略（是否按年月分层）
5. WHEN 上传的文件已存在（File_Hash 相同）时，THE Web 前端 SHALL 显示"文件已存在"提示并跳过该文件

### 需求 18：支持的文件格式

**用户故事：** 作为用户，我希望系统支持尽可能丰富的图片格式，包括手机和专业相机的格式，以便备份所有类型的照片。

#### 验收标准

1. THE 系统 SHALL 支持以下常见图片格式的备份和缩略图生成：JPEG、PNG、WebP、GIF、BMP、TIFF
2. THE 系统 SHALL 支持以下手机特有格式：HEIC/HEIF（iPhone）、AVIF
3. THE 系统 SHALL 支持以下专业相机 RAW 格式的备份：DNG、CR2、CR3（Canon）、NEF（Nikon）、ARW（Sony）、ORF（Olympus）、RAF（Fujifilm）、RW2（Panasonic）
4. THE 系统 SHALL 支持以下视频格式的备份：MP4、MOV、MKV、WebM、3GP、AVI、MPEG、WMV、FLV、M4V、TS
5. THE 系统 SHALL 支持 iPhone Live Photo 的备份，将 HEIC/JPEG 图片文件和对应的 MOV 视频文件作为一组关联文件进行备份和展示
6. THE 系统 SHALL 支持 Android 动态照片（Motion_Photo）的备份，识别并保留嵌入在图片文件中的视频数据
7. WHEN 生成缩略图时，IF 文件格式为 RAW，THEN THE Backup_Server SHALL 提取 RAW 文件中嵌入的预览图作为缩略图；若无嵌入预览则使用 rawpy 库解码生成
8. WHEN 为视频文件生成缩略图时，THE Backup_Server SHALL 使用 ffmpeg 抽取视频的一帧作为封面缩略图；IF 服务端未安装 ffmpeg，THEN THE Backup_Server SHALL 返回缩略图生成失败，由客户端以占位图 + 播放角标展示
9. WHEN 展示 Live Photo / 动态照片时，THE Web 前端和 Mobile_Client SHALL 支持播放关联的视频片段

### 需求 19：首次部署引导

**用户故事：** 作为 NAS 管理员，我希望首次部署时通过 Web 引导页面创建管理员账户，以便快速完成初始化配置。

#### 验收标准

1. WHEN Backup_Server 首次启动且数据库中无任何用户时，THE 系统 SHALL 进入初始化引导模式
2. WHILE 系统处于初始化引导模式时，THE Web 前端 SHALL 显示引导页面，要求设置管理员用户名和密码
3. WHEN 管理员通过引导页面提交用户名和密码时，THE Backup_Server SHALL 创建该管理员账户并退出引导模式
4. WHILE 系统处于初始化引导模式时，THE Backup_Server SHALL 拒绝所有非引导相关的 API 请求
5. WHEN 引导完成后，THE 系统 SHALL 不再显示引导页面，后续访问直接进入登录页面
6. THE 引导页面 SHALL 要求管理员密码长度不少于 8 个字符

### 需求 20：视频文件备份（Android）

**用户故事：** 作为用户，我希望 Android 客户端不仅备份照片，也能自动备份相册中的视频，以便照片和视频统一归档到 NAS。

#### 验收标准

1. WHEN Mobile_Client 扫描 Source_Folder 时，THE Mobile_Client SHALL 同时查询系统媒体库中的图片集合（MediaStore.Images）和视频集合（MediaStore.Video），将两类 Media_File 合并后纳入备份队列
2. THE Mobile_Client SHALL 在清单中声明并在运行时申请视频读取权限（Android 13+ 的 `READ_MEDIA_VIDEO`；更低版本使用 `READ_EXTERNAL_STORAGE`）
3. WHEN Mobile_Client 计算 Media_File 的 File_Hash 与用于分块上传的文件大小时，THE Mobile_Client SHALL 先将文件快照到应用私有缓存，并从同一份快照计算哈希和读取分块，确保上传的字节与哈希对应的字节完全一致（避免刚录制的视频在媒体库尺寸未刷新时导致完整性校验失败）
4. WHEN Mobile_Client 升级到首次支持视频/动态照片扫描的版本后，THE Mobile_Client SHALL 自动执行一次全量回扫（忽略各文件夹的上次扫描时间），以补备此前未被支持的历史视频文件，且该回扫每个能力版本仅执行一次
5. WHEN Backup_Server 完成一次上传（合并后哈希校验通过并成功注册文件记录）时，THE Backup_Server SHALL 在完成响应中返回 `success=true`；IF 合并后哈希校验失败，THEN THE Backup_Server SHALL 丢弃该文件并返回 HTTP 422 错误（不返回完成响应体）；THE Mobile_Client SHALL 依据 HTTP 状态码与 `success` 字段判定备份是否成功

### 需求 21：动态照片（Motion Photo）识别与预览

**用户故事：** 作为用户，我希望系统能识别动态照片并在 Web 端播放其动态片段，以便像在手机上一样查看动态照片。

#### 验收标准

1. WHEN Backup_Server 完成图片文件备份时，THE Backup_Server SHALL 解析该 JPEG 的 XMP 元数据，识别其是否为 Motion_Photo（`GCamera:MotionPhoto` / `MicroVideoOffset`，或 Container 中语义为 `MotionPhoto`、MIME 为 `video/mp4` 的 Item），并在无有效 XMP 时回退扫描文件尾部的 MP4（`ftyp`）标记
2. WHEN 识别为 Motion_Photo 时，THE Backup_Server SHALL 持久化 `is_motion_photo=true` 及内嵌视频的起始字节偏移 `motion_video_offset`
3. THE Backup_Server SHALL 提供接口 `GET /api/v1/files/motion/{file_id}`，仅流式返回文件尾部的内嵌 MP4 视频字节，MIME 为 `video/mp4`，并支持 HTTP Range 请求以便浏览器播放与拖动进度
4. WHEN Web 前端展示 Motion_Photo 时，THE Web 前端 SHALL 在缩略图右上角显示类 iOS Live Photo 图标，并在灯箱中提供"LIVE"播放按钮，点击后在静态图之上叠加播放内嵌视频
5. THE Backup_Server SHALL 提供对历史图片记录批量补测 Motion_Photo 的能力，使升级前已备份的图片也能被标记

### 需求 22：Ultra HDR 识别与标识

**用户故事：** 作为用户，我希望系统能识别 Ultra HDR 照片并在界面上给出标识，以便区分普通照片与 HDR 照片。

#### 验收标准

1. WHEN Backup_Server 完成图片文件备份时，THE Backup_Server SHALL 解析该 JPEG 的 XMP 元数据，通过 `hdr-gain-map`（`hdrgm`）命名空间或语义为 `GainMap` 的 Container Item 判定其是否为 Ultra_HDR，并持久化 `is_ultra_hdr` 标记
2. THE Backup_Server SHALL 在文件列表 / 详情接口中返回 `is_ultra_hdr` 字段
3. WHEN Web 前端展示 Ultra_HDR 照片时，THE Web 前端 SHALL 在照片右下角显示紧凑的"HDR"角标（完整名称"Ultra HDR"以悬浮提示展示）
4. THE Backup_Server SHALL 提供对历史图片记录批量补测 Ultra_HDR 的能力，使升级前已备份的图片也能被标记

### 需求 23：时间线日期筛选

**用户故事：** 作为用户，我希望时间线页面的日期筛选能直观区分"有照片""无照片""未来"的日期，同时不限制我的选择自由。

#### 验收标准

1. THE Web 前端时间线页面 SHALL 提供按设备、文件格式、焦段、日期范围的多维筛选
2. WHEN 展示日期范围选择器时，THE Web 前端 SHALL 将未来日期以及没有任何已备份照片/视频的日期置灰显示
3. WHILE 日期被置灰时，THE Web 前端 SHALL 仍允许用户点击选择这些日期（不禁用），仅作视觉弱化
4. THE Web 前端 SHALL 在存在任意生效筛选条件时，于筛选组件左侧显示"清除筛选"按钮，点击后清空全部筛选条件

### 需求 24：备份任务的手动开始/暂停控制

**用户故事：** 作为用户，我希望在"备份任务"Tab 上手动暂停和恢复当前的备份任务，以便在需要时主动控制备份进度，而不必依赖"自动备份"开关或等待系统条件变化。

#### 验收标准

1. THE Mobile_Client SHALL 在"备份任务"Tab 提供一个针对当前备份任务的"开始/暂停"按钮，其显示随任务状态切换（备份进行中显示"暂停"，已暂停或空闲时显示"开始"）
2. WHEN 用户在备份进行中点击"暂停"按钮时，THE Mobile_Client SHALL 暂停当前 Backup_Task、保留断点续传进度，并将该暂停标记为"用户暂停"
3. WHEN 用户在已暂停或存在排队文件时点击"开始"按钮时，THE Mobile_Client SHALL 在满足 Backup_Condition 时从断点继续上传 Backup_Queue 中的文件
4. WHILE Backup_Queue 为空且无正在进行的任务时，THE Mobile_Client SHALL 将"开始/暂停"按钮置为不可用状态
5. THE Mobile_Client SHALL 区分"用户暂停"与"条件暂停"（电量不足/WiFi 断开）：仅"条件暂停"在条件恢复后自动续传；"用户暂停"须由用户再次点击"开始"才恢复，条件恢复不得自动覆盖用户暂停
6. WHEN 用户手动发起的任务被"用户暂停"后，用户关闭"自动备份"开关，THE Mobile_Client SHALL 保持该手动任务的暂停状态与队列不变（不套用需求 3.14 的自动任务清空逻辑）

### 需求 25：关闭自动备份时区分保留正在上传文件与清空未开始文件

**用户故事：** 作为用户，我希望在关闭自动备份时，正在上传（已传了一半）的文件被保留下来而不是消失，同时那些还没开始上传的排队文件按现状被清理掉，以便我能继续处理那个真正传了一半的文件。

> 说明：本组需求（需求 25 至需求 33）是对 R-3.14"关闭自动备份停止自动任务、保留正在上传文件断点"行为的 UI 层扩展（让该断点文件作为可见的"已暂停"任务并支持继续/清除），不改变 R-3.14 的核心行为。

#### 验收标准

1. WHILE 一次**自动**发起的 Backup_Task 正在进行时，IF 用户关闭 Auto_Backup_Switch，THEN THE Mobile_Client SHALL 在关闭开关后 5 秒内停止备份前台服务、停止 In_Flight_File 当前 Chunk 的上传、清空 Backup_Queue 中的全部 Queued_Not_Started_File，并保留 In_Flight_File 的 Upload_Record 及其已确认的 Chunk 续传进度（不删除该断点续传记录）；由于逐个上传，同一时刻至多存在一个 In_Flight_File
2. WHEN 用户关闭 Auto_Backup_Switch 导致自动 Backup_Task 停止时，THE Mobile_Client SHALL 将拥有持久化 Upload_Record 的 In_Flight_File 标记为 Pause_Source 为 `AUTO_OFF` 的 Paused_Task，并在 Tasks_Tab 中将其展示为"已暂停"条目
3. WHEN 用户关闭 Auto_Backup_Switch 导致自动 Backup_Task 停止时，THE Mobile_Client SHALL 不为任何 Queued_Not_Started_File 创建 Paused_Task（这些文件没有 Upload_Record），且被清空的 Queued_Not_Started_File 不出现在 Tasks_Tab 的任务列表中
4. WHILE Auto_Backup_Switch 处于关闭状态时，THE Mobile_Client SHALL 不将 `AUTO_OFF` 来源的 Paused_Task 重新入队 Backup_Queue，也不因该 Paused_Task 自动启动备份前台服务
5. WHILE 一次**自动**发起的 Backup_Task 正在进行时，IF 用户关闭 Auto_Backup_Switch 且此刻不存在任何 In_Flight_File（无文件正在上传，或队列中文件均无 Upload_Record），THEN THE Mobile_Client SHALL 在 5 秒内停止备份前台服务、清空 Backup_Queue，并不创建任何 Paused_Task

### 需求 26：在备份任务 Tab 展示已暂停任务清单

**用户故事：** 作为用户，我希望在"备份任务"Tab 上看到因关闭自动备份而被暂停的、传了一半的文件，以便我知道它还没备份完并能对它进行操作。

#### 验收标准

1. WHEN 用户打开 Tasks_Tab 的"当前任务"区时，THE Mobile_Client SHALL 从持久化的 Upload_Record 读取并展示 `AUTO_OFF` 来源的 Paused_Task 清单，而不仅依赖内存 Backup_Queue，且 SHALL 在 3 秒内完成加载并按暂停时间由近到远排序展示
2. WHEN Tasks_Tab 展示一个 Paused_Task 条目时，THE Mobile_Client SHALL 显示该文件的文件名与已上传进度，进度为基于 Upload_Record 的已上传分块数除以总分块数、再向下取整所得的 0 至 100 的整数百分比
3. IF Upload_Record 的总分块数为 0 或不可用，THEN THE Mobile_Client SHALL 将该 Paused_Task 的已上传进度显示为 0%
4. IF Mobile_Client 从持久化的 Upload_Record 读取 Paused_Task 清单失败，THEN THE Mobile_Client SHALL 向用户显示指示读取失败的错误提示、提供重试入口，且不删除或修改任何 Upload_Record
5. WHEN Tasks_Tab 展示一个 `AUTO_OFF` 来源的 Paused_Task 条目时，THE Mobile_Client SHALL 显示"已暂停"状态标识，并附带区别于 `USER`（已手动暂停）与 `CONDITION`（条件暂停）的说明文案，指明该任务因关闭自动备份而暂停、需用户手动点击"继续"才会续传
6. WHEN Tasks_Tab 展示一个 `AUTO_OFF` 来源的 Paused_Task 条目时，THE Mobile_Client SHALL 为该条目提供"继续"按钮
7. WHILE 存在一个或多个 `AUTO_OFF` 来源的 Paused_Task 时，THE Mobile_Client SHALL 在 Tasks_Tab 的"当前任务"区持续展示这些条目，直至它们被续传完成或被用户清除
8. WHILE 不存在任何 `AUTO_OFF` 来源的 Paused_Task 时，THE Mobile_Client SHALL 在 Tasks_Tab 对应区域展示空状态提示

### 需求 27：单文件"继续"续传

**用户故事：** 作为用户，我希望点击"继续"就能单独把这个传了一半的文件续传完，而不用重新打开自动备份开关。

#### 验收标准

1. WHEN 用户点击某个 Paused_Task 的"继续"按钮时，THE Mobile_Client SHALL 依据该文件的 Upload_Record 重建其文件信息，并将其作为一次**手动**任务（`manual=true`）发起备份前台服务，从断点续传
2. WHEN 用户点击 Paused_Task 的"继续"按钮时，THE Mobile_Client SHALL 不改变 Auto_Backup_Switch 的状态（不重新开启自动备份）
3. WHEN 用户点击"继续"发起的手动续传时，IF 当前满足 Backup_Condition（设备电量大于 50% 且网络处于 WiFi 连接），THEN THE Mobile_Client SHALL 从该文件 Upload_Record 记录的已上传分块位置继续上传，不重新传输已确认的分块
4. WHEN 用户点击"继续"发起的手动续传时，IF 当前不满足 Backup_Condition（设备电量小于等于 50% 或网络未连接 WiFi），THEN THE Mobile_Client SHALL 将该任务标记为 Pause_Source 为 `CONDITION` 的条件暂停并如此显示，并在设备电量回升至大于 55% 且网络处于 WiFi 连接时自动续传该手动任务
5. WHEN 用户点击"继续"发起的手动续传时，IF 该文件的 Upload_Record 无效（源文件修改时间或文件大小与记录不一致，或该记录自创建起已超过 7 天），THEN THE Mobile_Client SHALL 废弃该 Upload_Record 并从第一个 Chunk 重新上传该文件（沿用 R-5.7 与 R-5.3）
6. WHEN 用户点击"继续"发起的手动续传时，IF 该文件的源文件已不存在或不可读，THEN THE Mobile_Client SHALL 取消该续传、向用户显示指示无法续传的错误提示、删除对应的 Upload_Record，并将对应的 Paused_Task 从 Tasks_Tab 的清单中移除
7. IF 用户点击"继续"发起的手动续传上传失败，THEN THE Mobile_Client SHALL 最多重试 3 次、每次间隔 30 秒；若仍失败，THE Mobile_Client SHALL 保留该文件的 Upload_Record 与对应的 Paused_Task 并将其标记为待重试（沿用 R-3.7）
8. WHEN 用户点击"继续"发起的手动续传收到服务端返回 `success=true` 而成功完成该文件的上传时，THE Mobile_Client SHALL 删除该文件的 Upload_Record，并将对应的 Paused_Task 从 Tasks_Tab 的清单中移除

### 需求 28：长按清除已暂停任务

**用户故事：** 作为用户，我希望长按一个已暂停的任务条目就能选择清除它，彻底放弃这个文件的续传。

#### 验收标准

1. WHEN 用户长按（持续时间 ≥ 500 毫秒）某个 Paused_Task 条目时，THE Mobile_Client SHALL 弹出包含"清除"选项的选项框
2. WHEN 用户在选项框中选择"清除"时，THE Mobile_Client SHALL 通过 `deleteByFileUri` 删除该文件对应的 Upload_Record
3. IF 通过 `deleteByFileUri` 删除 Upload_Record 失败，THEN THE Mobile_Client SHALL 保留该 Paused_Task 条目于 Tasks_Tab 清单中且不改变其 Upload_Record，并向用户显示指示清除失败的错误提示
4. WHEN 某个 Paused_Task 的 Upload_Record 被成功清除后，THE Mobile_Client SHALL 在 1 秒内将该 Paused_Task 条目从 Tasks_Tab 的清单中移除
5. IF 用户取消或关闭选项框而未选择"清除"，THEN THE Mobile_Client SHALL 保留该 Paused_Task 条目及其 Upload_Record 不变
6. WHEN 某个 Paused_Task 被成功清除后，THE Mobile_Client SHALL 不再在后续扫描、续传或进程重建中重新入队或续传该文件（作为一个新文件被下一次全量扫描重新发现的情形除外）

### 需求 29：与既有自动备份关闭与手动任务逻辑保持一致

**用户故事：** 作为用户，我希望这个新行为不破坏已有的规则——手动任务在关闭自动备份时仍不受影响，而已暂停任务的清单来源是持久化记录。

#### 验收标准

1. WHILE 一次**手动**发起的 Backup_Task（"立即备份"FAB、单张"重新备份"、任务页"重试"、以及本功能的"继续"续传）正在进行时，IF 用户关闭 Auto_Backup_Switch，THEN THE Mobile_Client SHALL 继续该手动任务直至完成，SHALL NOT 清空其队列或停止服务（沿用 R-3.15）
2. WHEN 用户重新开启 Auto_Backup_Switch 时，THE Mobile_Client SHALL 允许既有的自动触发方式在满足 Backup_Condition 时续传仍存在 Upload_Record 的文件（沿用既有进程重建/条件恢复续传逻辑）
3. THE Mobile_Client SHALL 使用 `AUTO_OFF` 作为独立于 `USER` 与 `CONDITION` 的第三种 Pause_Source，用于表示因关闭自动备份而产生的 Paused_Task

### 需求 30：暂停状态文案区分与不自动续传

**用户故事：** 作为用户，我希望能在界面和通知上区分"因关闭自动备份而暂停"和其他暂停原因，并且这种暂停不会在电量/WiFi 恢复时被系统自动续传。

#### 验收标准

1. WHEN Mobile_Client 在界面上展示一个 `AUTO_OFF` 来源的 Paused_Task 时，THE Mobile_Client SHALL 使用在文字内容上区别于 `USER`（"已手动暂停，点击开始继续"）与 `CONDITION`（"电量不足/WiFi 未连接，条件恢复后自动续传"）的文案，且该文案 SHALL 明确表明该任务因"自动备份已关闭"而暂停
2. WHEN Mobile_Client 在通知栏展示因关闭自动备份而暂停的状态时，THE Mobile_Client SHALL 使用在文字内容上区别于 `USER` 与 `CONDITION` 的通知文案，且该文案 SHALL 明确表明该状态因"自动备份已关闭"而产生
3. IF Backup_Condition 在存在 `AUTO_OFF` 来源 Paused_Task 期间恢复（设备电量回升至大于 50% 或网络重新连接 WiFi），THEN THE Mobile_Client SHALL 不自动续传任何该来源的 Paused_Task
4. THE Mobile_Client SHALL 仅在用户点击该 Paused_Task 的"继续"按钮后才发起该文件的续传
5. WHEN 用户点击某个 `AUTO_OFF` 来源 Paused_Task 的"继续"按钮时，THE Mobile_Client SHALL 不改变其他未被点击的 `AUTO_OFF` 来源 Paused_Task 的暂停状态

### 需求 31：应用重启后从持久化记录恢复展示

**用户故事：** 作为用户，我希望关掉 App 再打开后，那些已暂停的任务还在清单里，以便我下次打开时仍能继续或清除它们。

#### 验收标准

1. WHEN Mobile_Client 在存在有效 Upload_Record 且 Auto_Backup_Switch 处于关闭状态时被重新启动，THE Mobile_Client SHALL 从持久化的 Upload_Record 重建并在 Tasks_Tab 展示对应的 Paused_Task
2. WHILE Auto_Backup_Switch 处于关闭状态时，THE Mobile_Client SHALL 在应用重启后不自动续传这些 Paused_Task
3. WHEN Mobile_Client 重启后展示重建的 Paused_Task 时，THE Mobile_Client SHALL 支持对其执行需求 27（继续）与需求 28（长按清除）中定义的操作

### 需求 32：边界与异常处理

**用户故事：** 作为用户，我希望在断点记录过期、源文件被改动或删除、备份文件夹被移除等异常情况下，暂停任务的展示与操作有明确、合理的行为，不会卡在无法处理的状态。

#### 验收标准

1. WHEN Mobile_Client 加载或刷新暂停清单且检测到某个 Paused_Task 对应的 Upload_Record 已超过 Session_Expiry（自创建起超过 7 天），THE Mobile_Client SHALL 不将该已过期记录作为可续传的 Paused_Task 展示、将该条目从 Tasks_Tab 清单中移除，其后续处理沿用既有过期记录逻辑（由下一次全量扫描作为新文件重新发现并重新上传），且 SHALL 不影响任何仍在有效期内的其他 Upload_Record
2. IF 用户对某个 Paused_Task 点击"继续"时，其源文件的修改时间或文件大小与 Upload_Record 记录不一致，THEN THE Mobile_Client SHALL 废弃该续传记录、将该条目从清单中移除、将该任务置为上传中，并从第一个 Chunk 重新上传该文件而不修改源文件（沿用 R-5.7）
3. IF 用户对某个 Paused_Task 点击"继续"时，其源文件已不存在（已被删除），THEN THE Mobile_Client SHALL 向用户显示指示该文件已不存在、无法续传的提示，删除对应的 Upload_Record 使该 Paused_Task 从清单中移除，且 SHALL 不触发任何上传请求
4. WHEN Mobile_Client 检测到某个 Paused_Task 所属的备份文件夹已被移除，THE Mobile_Client SHALL 不对该文件发起续传或重传，并将其对应的 Upload_Record 及 Paused_Task 从本地存储与 Tasks_Tab 清单中一并清除（沿用移除文件夹时删除续传记录的既有逻辑）

### 需求 33：iOS 平台对齐（可选）

**用户故事：** 作为使用 iOS 客户端的用户，我希望未来也能获得同样的"关闭自动备份后保留已暂停任务"体验，以便跨平台行为一致。

#### 验收标准

1. WHERE 目标平台为 iOS，THE Mobile_Client SHALL 在后续迭代中对齐需求 25 至需求 32 定义的行为（本项为可选，不在本次 Android 必做范围内）
