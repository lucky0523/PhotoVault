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
4. THE 系统 SHALL 支持 iPhone Live Photo 的备份，将 HEIC/JPEG 图片文件和对应的 MOV 视频文件作为一组关联文件进行备份和展示
5. THE 系统 SHALL 支持 Android 动态照片（Motion Photo）的备份，识别并保留嵌入在图片文件中的视频数据
6. WHEN 生成缩略图时，IF 文件格式为 RAW，THEN THE Backup_Server SHALL 提取 RAW 文件中嵌入的预览图作为缩略图；若无嵌入预览则使用 rawpy 库解码生成
7. WHEN 展示 Live Photo / 动态照片时，THE Web 前端和 Mobile_Client SHALL 支持播放关联的视频片段

### 需求 19：首次部署引导

**用户故事：** 作为 NAS 管理员，我希望首次部署时通过 Web 引导页面创建管理员账户，以便快速完成初始化配置。

#### 验收标准

1. WHEN Backup_Server 首次启动且数据库中无任何用户时，THE 系统 SHALL 进入初始化引导模式
2. WHILE 系统处于初始化引导模式时，THE Web 前端 SHALL 显示引导页面，要求设置管理员用户名和密码
3. WHEN 管理员通过引导页面提交用户名和密码时，THE Backup_Server SHALL 创建该管理员账户并退出引导模式
4. WHILE 系统处于初始化引导模式时，THE Backup_Server SHALL 拒绝所有非引导相关的 API 请求
5. WHEN 引导完成后，THE 系统 SHALL 不再显示引导页面，后续访问直接进入登录页面
6. THE 引导页面 SHALL 要求管理员密码长度不少于 8 个字符
