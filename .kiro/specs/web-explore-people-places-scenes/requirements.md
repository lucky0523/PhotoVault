# Requirements Document

## Introduction

本功能为 PhotoVault 的 **Web 端** 新增一个「探索」页面，将用户的照片按 **人物**、**地点**、**场景** 三个维度自动归类展示，交互与视觉参考主流相册应用（人物为圆形头像横向列表、地点为带地图入口的城市卡片横向列表、场景为分类卡片横向列表）。

该能力当前在后端**完全没有数据支撑**：`file_records` 表仅存储文件哈希、路径、设备名、大小、MIME、`exif_time`、`focal_length`、动态照片/HDR 标记与生命周期时间戳；后端依赖仅有 `Pillow`，**没有任何机器学习库**，上传时 EXIF 中的 GPS 信息被直接丢弃。因此本功能是一个**全栈 + 分析流水线**改动，包含：

- **地点（Places）**：从照片 EXIF 提取 GPS 坐标，使用**离线逆地理编码库**（可更新的本地数据文件，不依赖外网、不外泄坐标）将坐标解析为城市/省份/国家，并按城市聚合。
- **场景（Scenes）**：使用**场景分类模型文件**对照片内容进行分类（如「沙滩」「公园」「演唱会」「屏幕截图」等），按场景标签聚合。
- **人物（People）**：使用**人脸检测 + 人脸特征模型文件**提取人脸特征向量并做聚类归并为「人物」，支持用户命名，按人物聚合。

三类分析均通过复用现有的**后台任务机制**异步执行，分析结果落库，Web 端只读聚合结果。人物与场景依赖模型文件、地点依赖离线地理编码库，这些资源文件**不随程序分发**，需要用户自行放置/更新；因此本功能在 **Web 端提供一个管理入口**，让用户可以查看资源状态、手动上传/更新模型文件与地理编码库，并触发对存量照片的重新分析。

为保持后端「轻量部署」的既有风格，三类分析能力均为**可选、可独立开关**：未安装对应资源文件或未启用对应开关时，该维度在 Web 端以友好占位降级展示，不影响其余功能与轻量部署。

## Glossary

- **ExploreView**：Web 端「探索」页面，文件路径 `web/src/views/ExploreView.vue`，以三段横向滚动列表分别展示人物、地点、场景。
- **ExploreManageView**：Web 端「探索 / 分析管理」入口页面（或对话框），文件路径 `web/src/views/ExploreManageView.vue`，用于查看分析资源状态、上传/更新模型文件与地理编码库、触发重新分析。
- **CategoryPhotosView**：点击某个人物/地点/场景后展示该分类下照片网格的视图，复用或参照现有 `web/src/views/PhotosView.vue` 的网格与预览能力。
- **AnalysisResource**：分析所需的外部资源文件的统称，分三类：**FaceModel**（人脸检测 + 人脸特征模型）、**SceneModel**（场景分类模型）、**GeocodingDB**（离线逆地理编码数据库）。
- **FaceModel**：人脸检测与人脸特征提取所用的模型文件（ONNX 格式），存放于服务端模型目录。
- **SceneModel**：场景分类所用的模型文件（ONNX 格式）及其标签映射文件，存放于服务端模型目录。
- **GeocodingDB**：离线逆地理编码数据库文件（城市坐标数据集），用于将经纬度解析为城市/省份/国家，存放于服务端数据目录。
- **AnalysisPipeline**：分析流水线，指服务端对照片依次执行 GPS 提取/逆地理编码、场景分类、人脸检测与聚类的异步后台处理逻辑，落库到新增数据表。
- **PersonCluster**：一个「人物」聚类，对应一组被判定为同一个人的人脸；有可选的用户自定义显示名与一张封面人脸。对应数据表 `face_clusters`。
- **Face**：从单张照片中检测到的一张人脸记录，含边界框、检测分数与特征向量，归属于某个 PersonCluster。对应数据表 `faces`。
- **PlaceGroup**：按城市聚合的「地点」分组，由照片 GPS 逆地理编码得到的城市名归并而成。
- **SceneGroup**：按场景标签聚合的「场景」分组。
- **PhotoGPS**：单张照片的地理位置记录（经纬度 + 逆地理编码得到的城市/省份/国家）。对应数据表 `photo_gps`。
- **PhotoScene**：单张照片的一个场景标签记录（标签 + 置信度），一张照片可有多个。对应数据表 `photo_scenes`。
- **LibraryFilter**：ExploreView 顶部的「图库过滤」下拉（默认「全部图库」），用于按设备/来源过滤探索结果，取值来源为现有设备列表（`GET /api/v1/files/devices`）。
- **ExploreAPI**：本功能新增的服务端接口集合，挂载于 `/api/v1/explore`，由 `server/app/api/explore.py` 提供。
- **AnalysisFeatureFlag**：控制三类分析是否启用的服务端配置项 `enable_face` / `enable_scene` / `enable_place`，位于 `server/app/core/config.py`。
- **ResourceStatus**：某个 AnalysisResource 的安装状态，包含 `installed`（是否就绪）、`name`、`version`、`size`、`updated_at` 等字段，供 Web 端管理入口展示。

## Requirements

### 需求 1：Web 端探索页布局

**用户故事：** 作为 Web 端用户，我希望有一个「探索」页面，把我的照片按人物、地点、场景三个维度归类展示，以便快速按这些维度浏览照片。

#### 验收标准

1. THE Web 端 SHALL 新增一条 `/explore` 路由指向 ExploreView，并在 MainLayout 侧边栏菜单中新增「探索」入口。
2. THE ExploreView SHALL 依次展示三个分区：「人物」、「地点」、「场景」，每个分区内为横向可滚动的条目列表。
3. THE ExploreView SHALL 在「人物」分区将每个 PersonCluster 渲染为圆形封面头像加名称（无自定义名时展示默认名如「人物1」）。
4. THE ExploreView SHALL 在「地点」分区将每个 PlaceGroup 渲染为矩形封面卡片加城市名，并在该分区首位提供一个「地图」聚合入口项。
5. THE ExploreView SHALL 在「场景」分区将每个 SceneGroup 渲染为矩形封面卡片加场景名。
6. THE ExploreView SHALL 在顶部提供 LibraryFilter 下拉，默认选中「全部图库」。
7. WHERE LibraryFilter 选择了某一图库/设备，THE ExploreView SHALL 仅展示该图库/设备范围内的人物、地点、场景聚合结果。

### 需求 2：地点（GPS 提取 + 离线逆地理编码 + 城市聚合）

**用户故事：** 作为用户，我希望按拍摄地点浏览照片，把同一城市的照片归为一组，以便回顾在某地拍摄的照片。

#### 验收标准

1. WHEN 一张照片被分析，THE AnalysisPipeline SHALL 从其 EXIF 的 GPS 信息（GPSInfo IFD）提取经纬度；IF 该照片无 GPS 信息，THEN THE AnalysisPipeline SHALL 跳过该照片的地点分析且不产生 PhotoGPS 记录。
2. WHEN 一张照片提取到经纬度，THE AnalysisPipeline SHALL 使用 GeocodingDB 进行**离线**逆地理编码，得到城市/省份/国家，并写入 PhotoGPS；THE AnalysisPipeline SHALL NOT 为逆地理编码发起任何外网请求。
3. THE ExploreAPI SHALL 提供按城市聚合的 PlaceGroup 列表接口，每个 PlaceGroup 含城市名、照片数量与一张封面照片。
4. THE ExploreAPI SHALL 提供获取某个 PlaceGroup 下全部照片的接口。
5. IF GeocodingDB 未安装，THEN THE AnalysisPipeline SHALL 跳过逆地理编码（可仍保存原始经纬度），且 ExploreView 的「地点」分区 SHALL 以「暂无数据 / 需安装地理编码库」占位降级。
6. THE PhotoGPS 记录 SHALL 通过 `user_id` 与文件归属做用户隔离，不同用户之间的地点数据互不可见。

### 需求 3：场景（模型识别 + 场景聚合）

**用户故事：** 作为用户，我希望按场景（如沙滩、公园、屏幕截图）浏览照片，以便按内容类型快速找到照片。

#### 验收标准

1. WHEN 一张照片被分析且 SceneModel 已安装，THE AnalysisPipeline SHALL 使用 SceneModel 对照片内容分类，产生一个或多个 PhotoScene 记录（含场景标签与置信度）。
2. THE AnalysisPipeline SHALL 仅保留置信度不低于配置阈值的场景标签，低于阈值的标签不写入 PhotoScene。
3. THE ExploreAPI SHALL 提供按场景标签聚合的 SceneGroup 列表接口，每个 SceneGroup 含场景显示名（中文）、照片数量与一张封面照片。
4. THE ExploreAPI SHALL 提供获取某个 SceneGroup 下全部照片的接口。
5. THE 场景标签 SHALL 提供中文显示名映射，Web 端展示中文场景名。
6. IF SceneModel 未安装或 `enable_scene` 为关闭，THEN THE AnalysisPipeline SHALL 跳过场景分析，且 ExploreView 的「场景」分区 SHALL 以占位降级展示。
7. THE PhotoScene 记录 SHALL 通过 `user_id` 做用户隔离。

### 需求 4：人物（人脸检测 + 特征聚类 + 命名）

**用户故事：** 作为用户，我希望应用自动把同一个人的照片归到一起并允许我为其命名，以便按人物浏览照片。

#### 验收标准

1. WHEN 一张照片被分析且 FaceModel 已安装，THE AnalysisPipeline SHALL 检测照片中的人脸，对每张达到检测分数阈值的人脸提取特征向量并写入 Face 记录（含边界框、检测分数、特征向量）。
2. WHEN 一张新的 Face 被提取，THE AnalysisPipeline SHALL 依据特征向量余弦相似度将其归并到最相近的既有 PersonCluster；IF 没有相似度达到阈值的既有聚类，THEN THE AnalysisPipeline SHALL 新建一个 PersonCluster。
3. THE ExploreAPI SHALL 提供 PersonCluster 列表接口，每个条目含聚类 ID、显示名（无自定义名时为默认名）、人脸数量与封面人脸。
4. THE ExploreAPI SHALL 提供获取某个 PersonCluster 下全部照片的接口。
5. THE ExploreAPI SHALL 提供为 PersonCluster 设置/修改显示名的接口。
6. IF FaceModel 未安装或 `enable_face` 为关闭，THEN THE AnalysisPipeline SHALL 跳过人脸分析，且 ExploreView 的「人物」分区 SHALL 以占位降级展示。
7. THE Face 与 PersonCluster 记录 SHALL 通过 `user_id` 做用户隔离。

### 需求 5：分类详情（点击进入照片网格）

**用户故事：** 作为用户，我希望点击某个人物/地点/场景后看到该分类下的所有照片，以便浏览与预览。

#### 验收标准

1. WHEN 用户在 ExploreView 点击某个 PersonCluster / PlaceGroup / SceneGroup，THE Web 端 SHALL 导航到 CategoryPhotosView 并展示该分类下的照片网格。
2. THE CategoryPhotosView SHALL 复用现有缩略图加载与图片预览能力（`getThumbnailUrl` / `ImagePreview`）。
3. WHEN 用户在「地点」分区点击「地图」聚合入口，THE Web 端 SHALL 展示带地理位置的照片入口（地图视图或按城市汇总的地点总览，具体形态在设计阶段确定）。
4. THE CategoryPhotosView SHALL 支持分页加载，避免一次性加载全部照片。

### 需求 6：分析资源管理入口（模型文件与地理编码库的查看与更新）

**用户故事：** 作为部署/管理应用的用户，我希望在 Web 端查看并手动上传/更新人脸模型、场景模型与离线地理编码库，以便在不重新部署的情况下启用或升级这些分析能力。

#### 验收标准

1. THE Web 端 SHALL 提供 ExploreManageView 入口（可从 ExploreView 顶部的「管理」按钮进入），展示三类 AnalysisResource（FaceModel、SceneModel、GeocodingDB）的 ResourceStatus（是否已安装、名称、版本、大小、更新时间）。
2. THE ExploreManageView SHALL 允许用户为每一类 AnalysisResource 手动上传/更新对应的资源文件。
3. WHEN 用户上传某类 AnalysisResource 文件，THE ExploreAPI SHALL 校验并将其保存到服务端对应的资源目录，更新后即时可用（无需重启进程即被下次分析加载）。
4. THE ExploreManageView SHALL 提供触发「对存量照片重新分析」的操作，触发后由 AnalysisPipeline 异步处理。
5. THE ExploreAPI SHALL 提供查询 ResourceStatus 的接口与上传 AnalysisResource 的接口。
6. WHERE 当前用户不是管理员，THE 资源上传与全库重新分析操作 SHALL 被拒绝（仅管理员可执行）；ResourceStatus 的只读查看不受此限制。
7. WHEN 用户上传的文件不符合对应资源类型的校验要求（如扩展名/格式/大小），THE ExploreAPI SHALL 拒绝该上传并返回可读的错误信息，不破坏既有已安装资源。

### 需求 7：分析流水线（异步、可开关、存量回填）

**用户故事：** 作为维护应用的开发者，我希望分析在后台异步进行、可按维度开关、并能对存量照片回填，以免阻塞上传且保持轻量部署可选。

#### 验收标准

1. WHEN 一张照片完成上传入库，THE AnalysisPipeline SHALL 异步（不阻塞上传响应）对其执行已启用维度的分析。
2. THE 服务端 SHALL 提供 AnalysisFeatureFlag（`enable_face` / `enable_scene` / `enable_place`）以独立开关三类分析；默认配置 SHALL 保持后端轻量（未安装资源时相应维度自动视为不可用）。
3. THE 服务端 SHALL 提供对存量照片批量回填分析的机制（后台任务或脚本），可对未分析或需重新分析的照片补齐结果。
4. IF 分析所需的 Python 依赖或资源文件缺失，THEN THE 服务端 SHALL 优雅降级（记录日志、跳过该维度），而不导致上传或其他既有接口失败。
5. THE AnalysisPipeline SHALL 避免对同一照片同一维度重复产生冗余结果（重复分析时先清理或跳过已存在结果）。

### 需求 8：数据模型与接口契约

**用户故事：** 作为维护应用的开发者，我希望明确新增的数据表与接口契约，以便前后端协作实现。

#### 验收标准

1. THE 服务端 SHALL 新增数据表：`photo_gps`、`photo_scenes`、`faces`、`face_clusters`，均含 `user_id` 并与 `file_records` 关联；表创建 SHALL 采用与现有 `init_db` 一致的幂等方式（`CREATE TABLE IF NOT EXISTS` + 必要的幂等迁移）。
2. THE ExploreAPI SHALL 挂载于 `/api/v1/explore`，遵循现有鉴权方式（Bearer Token），并复用现有缩略图/下载接口返回照片资源。
3. THE Web 端 SHALL 新增 `web/src/api/explore.ts` 封装 ExploreAPI，复用现有 `http` 实例与鉴权拦截器。
4. THE 新增接口 SHALL 在无数据/资源未安装时返回空集合或明确的「未启用」状态，供 Web 端降级展示，而非返回错误。

### 需求 9：降级与兼容

**用户故事：** 作为使用轻量部署（未安装模型/地理库）的用户，我希望探索页在缺少分析能力时仍可访问且提示清晰，不影响其他功能。

#### 验收标准

1. IF 某一维度（人物/地点/场景）无任何结果或对应资源未安装，THEN THE ExploreView SHALL 在该分区展示占位与说明（如「暂无数据」或「需在管理入口安装模型/地理库」），而不报错或白屏。
2. THE 本功能的新增服务端依赖（如 ONNX 运行时）SHALL 为可选安装项；缺失时既有上传、浏览、回收站等接口 SHALL 不受影响。
3. THE 本功能 SHALL NOT 改变现有 `file_records` 既有列的语义，仅新增独立的分析结果表与可选字段。
