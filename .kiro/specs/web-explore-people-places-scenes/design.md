# 设计文档

## Overview

本功能为 PhotoVault 新增「探索」能力，采用**全栈 + 可选分析流水线**方案，分为四大块：

1. **数据层**：在 `file_records` 之外新增四张独立的分析结果表（`photo_gps`、`photo_scenes`、`faces`、`face_clusters`），采用与现有 `init_db` 一致的幂等建表方式，不改动 `file_records` 既有列语义。
2. **分析流水线（AnalysisPipeline）**：新增 `analysis_service.py` 及三个子分析器（GPS/逆地理编码、场景分类、人脸检测与聚类），通过复用现有后台任务机制（`background_tasks.py`）异步执行；能力可通过 AnalysisFeatureFlag 独立开关，缺失依赖/资源时优雅降级。
3. **接口层（ExploreAPI）**：新增 `server/app/api/explore.py`，挂载于 `/api/v1/explore`，提供人物/地点/场景的聚合与详情接口，以及分析资源（模型/地理库）的状态查询、上传、重新分析接口。
4. **Web 端**：新增 `ExploreView.vue`（三段式横向列表）、`ExploreManageView.vue`（资源管理入口）、`CategoryPhotosView.vue`（分类照片网格），新增 `web/src/api/explore.ts`，并在路由与 `MainLayout.vue` 侧边栏接入。

设计的核心取舍：**人脸/场景识别所需的机器学习运行时（ONNX Runtime）与模型文件、地点所需的离线地理编码库，均为可选资源，不随程序强制分发**。这与后端「仅依赖 Pillow 的轻量部署」风格保持兼容——未安装时相应维度自动不可用，Web 端降级为占位，其他既有功能（上传/浏览/回收站等）不受任何影响。

### 现状约束（已核实）

- `file_records` 现有字段无 GPS、无人脸、无场景；上传链路（`upload_service.py`）已用 Pillow 读取 EXIF（焦距），但丢弃 GPS。
- 后端依赖仅 `Pillow`；无 ONNX/torch 等 ML 运行时。
- 路由在 `main.py::_register_routes` 中通过 `include_router(prefix="/api/v1")` 注册。
- 后台任务在 `background_tasks.py::start_background_tasks` 中以 `asyncio.create_task` 启动，lifespan 管理启停。
- 配置集中在 `core/config.py::Settings`，支持 env / `.env` / `config.yaml`；`_flatten_yaml` 负责 YAML 到字段的映射。
- 缩略图/下载走 `/api/v1/files/thumbnail/{id}` 与 `/api/v1/files/download/{id}`，Web 端用 `getThumbnailUrl(fileId)` 加 `token` 查询参数直接引用。
- 管理员判定：`auth` 提供 `UserInfo.is_admin`，Web 端 `authStore.isAdmin` 已用于 `/admin/users` 路由守卫。

## Architecture

```
上传完成（upload_service.finalize / dedup 建 FileRecord）
        │  enqueue(file_id, user_id)
        ▼
AnalysisQueue（asyncio.Queue，进程内）
        │
        ▼
analysis_worker_task（background_tasks 新增，lifespan 启动）
        │   逐 file 顺序处理，按 FeatureFlag + 资源可用性选择子分析器
        ├──► PlaceAnalyzer   ：Pillow 取 GPS IFD → GeocodingDB 逆地理编码 → photo_gps
        ├──► SceneAnalyzer   ：ONNX SceneModel 推理 → 阈值过滤 → photo_scenes
        └──► FaceAnalyzer    ：ONNX 检测+特征 → 增量聚类 → faces / face_clusters
        ▼
    新增分析结果表（均含 user_id，关联 file_records.id）

Web 端                         ExploreAPI (/api/v1/explore)
 ExploreView ───────────────►  GET /explore/people  | /places | /scenes
   ├─ 人物横向列表  ◄───────────  聚合：cluster/城市/场景 + 封面 file_id + count
   ├─ 地点横向列表(+地图入口)
   └─ 场景横向列表
 CategoryPhotosView ────────►  GET /explore/people/{id} | /places/{city} | /scenes/{label}
   └─ 复用 getThumbnailUrl / ImagePreview
 ExploreManageView ─────────►  GET /explore/resources           （状态）
   ├─ 资源状态卡片            POST /explore/resources/{type}     （上传，管理员）
   ├─ 上传/更新按钮          POST /explore/reanalyze            （重新分析，管理员）
   └─ 重新分析按钮
   └─ 人物重命名  ──────────►  PUT  /explore/people/{id}
```

关键分层原则：**纯逻辑（可测）与副作用（IO/模型推理）分离**。逆地理编码的最近邻查找、人脸聚类的相似度归并决策、场景阈值过滤、GPS EXIF 有理数换算等，均抽为纯函数，便于属性测试；模型加载、SQL 装配、文件读写只做调用与装配。

## Components and Interfaces

### 一、配置（`server/app/core/config.py`）

在 `Settings` 新增字段（均带轻量默认值，缺省不改变现状）：

```python
# Analysis feature flags（默认关闭 face/scene，place 依赖仅 Pillow 可默认开启）
enable_place: bool = True
enable_scene: bool = False
enable_face: bool = False
# 分析资源目录（默认位于 storage_root 下，随 model_validator 派生）
models_root: str = ""          # 默认 f"{storage_root}/.models"
# 分析阈值
scene_min_confidence: float = 0.3
face_det_min_score: float = 0.5
face_cluster_similarity: float = 0.5   # 余弦相似度归并阈值
```

- `model_validator(mode="after")` 中补充：`if not self.models_root: self.models_root = f"{self.storage_root}/.models"`。
- `_flatten_yaml` 新增 `analysis:` 段映射（`enable_face`/`enable_scene`/`enable_place`/`scene_min_confidence`/`face_det_min_score`/`face_cluster_similarity`/`models_root`）。

资源文件在 `models_root` 下的约定布局：

```
{models_root}/
  faces/        det_10g.onnx, w600k_r50.onnx（检测 + 特征）
  scenes/       scene.onnx, labels_zh.json（模型 + 中文标签映射）
  geocoding/    cities.db（离线城市坐标数据集）
```

### 二、数据模型（`server/app/core/database.py`）

在 `init_db` 中追加建表（`CREATE TABLE IF NOT EXISTS`）与索引，与现有风格一致：

```sql
-- 照片 GPS 与逆地理编码结果
CREATE TABLE IF NOT EXISTS photo_gps (
    file_id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    city TEXT,
    province TEXT,
    country TEXT,
    geocoded_at TIMESTAMP,
    FOREIGN KEY (file_id) REFERENCES file_records(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 场景标签（一张照片可多条）
CREATE TABLE IF NOT EXISTS photo_scenes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    scene_label TEXT NOT NULL,
    confidence REAL NOT NULL,
    FOREIGN KEY (file_id) REFERENCES file_records(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 人物聚类
CREATE TABLE IF NOT EXISTS face_clusters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    display_name TEXT,
    cover_face_id INTEGER,
    centroid BLOB,          -- 归并用的聚类中心向量（float32）
    face_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 单张人脸
CREATE TABLE IF NOT EXISTS faces (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    cluster_id INTEGER,
    bbox TEXT NOT NULL,      -- JSON: [x1,y1,x2,y2]
    det_score REAL NOT NULL,
    embedding BLOB NOT NULL, -- float32 向量
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id) REFERENCES file_records(id),
    FOREIGN KEY (cluster_id) REFERENCES face_clusters(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

索引：`idx_photo_gps_user(user_id, city)`、`idx_photo_scenes_user(user_id, scene_label)`、`idx_faces_user(user_id, cluster_id)`、`idx_faces_file(file_id)`。

为支持「重新分析不产生冗余」（需求 7.5），分析前先按 `file_id` 删除该维度旧结果再写入。

### 三、分析流水线

#### 队列与后台 worker（`server/app/services/analysis_queue.py` + `background_tasks.py`）

- `analysis_queue.py`：模块级 `asyncio.Queue` 单例 + `enqueue_analysis(file_id, user_id)` / `enqueue_reanalysis_all(user_id)` 辅助；进程内队列，重启后未完成项由回填补齐（见回填）。
- `background_tasks.py` 新增 `analysis_worker_task()`：从队列取项，打开独立 aiosqlite 连接，调用 `AnalysisService.analyze_file(...)`；异常仅记录日志不中断（需求 7.4）。在 `start_background_tasks()` 追加该任务。

#### `AnalysisService`（`server/app/services/analysis_service.py`）

编排入口，按 FeatureFlag + 资源可用性调度三个分析器：

```python
class AnalysisService:
    async def analyze_file(self, user_id: int, file_id: int) -> None:
        path = await self._resolve_physical_path(user_id, file_id)
        if settings.enable_place and self._place.available:
            await self._place.analyze(user_id, file_id, path)
        if settings.enable_scene and self._scene.available:
            await self._scene.analyze(user_id, file_id, path)
        if settings.enable_face and self._face.available:
            await self._face.analyze(user_id, file_id, path)
```

每个分析器暴露 `available: bool`（资源是否就绪，`models_root` 下文件存在且可加载）。ONNX 相关分析器在 `__init__` 中**惰性**尝试 `import onnxruntime`，失败则 `available=False`（需求 9.2）。

#### PlaceAnalyzer（`place_analyzer.py`）

- 用 Pillow `Image.getexif().get_ifd(IFD.GPSInfo)` 读取 GPS（tag 34853），纯函数 `parse_gps_ifd(gps_ifd) -> Optional[(lat, lon)]` 负责有理数/参考方向（N/S/E/W）换算。
- 逆地理编码：加载 `geocoding/cities.db`（城市名 + 经纬度），纯函数 `nearest_city(lat, lon, cities) -> CityRecord` 做最近邻（球面距离/近似平方距离）。默认采用离线数据集（如公开的 GeoNames cities 子集导入的 SQLite），**不发起外网请求**（需求 2.2）。
- 写 `photo_gps`。`available = Path(models_root/geocoding/cities.db).exists()`；不可用时仅在有原始坐标时可选写入经纬度而不填城市（需求 2.5）。

#### SceneAnalyzer（`scene_analyzer.py`）

- 惰性加载 `scenes/scene.onnx`（如 Places365/MobileNet）+ `labels_zh.json`（英文标签→中文名）。
- 推理：Pillow 解码 → 预处理（resize/归一化，纯函数）→ ONNX run → softmax → top-k。
- 纯函数 `filter_scenes(scores, labels, min_conf) -> list[(label, conf)]` 过滤阈值（需求 3.2）。写 `photo_scenes`。

#### FaceAnalyzer（`face_analyzer.py`）

- 惰性加载 `faces/det_*.onnx`（检测，如 SCRFD）+ `faces/w600k_*.onnx`（特征，如 ArcFace）。
- 检测 → 对分数 ≥ `face_det_min_score` 的人脸对齐裁剪 → 特征向量（L2 归一化）。
- **增量聚类**（纯决策函数 `assign_cluster(embedding, clusters, threshold) -> Optional[cluster_id]`）：与各 `face_clusters.centroid` 计算余弦相似度，取最大且 ≥ `face_cluster_similarity` 者归入，否则新建聚类（需求 4.2）。归入后以增量均值更新 `centroid` 与 `face_count`，必要时设置 `cover_face_id`。写 `faces`。

> 说明：聚类中心用增量均值近似，避免存储/重算全量向量；这是自托管场景下性能与效果的折中，阈值可通过配置调整。

#### 回填（`server/scripts/backfill_analysis.py`）

仿 `scripts/backfill_focal_length.py`：遍历用户未分析（对应表无记录）或指定重分析的照片，调用 `AnalysisService.analyze_file`。供需求 7.3 与「重新分析」接口复用（接口触发时向队列批量 enqueue）。

#### 上传链路挂钩（`server/app/services/upload_service.py`）

在成功建立 `FileRecord`（`finalize` 及去重建引用处）后调用 `enqueue_analysis(file_id, user_id)`。入队为非阻塞操作，不改变上传响应（需求 7.1）。

### 四、ExploreAPI（`server/app/api/explore.py`）

挂载 `include_router(explore_router, prefix="/api/v1", tags=["explore"])`。所有接口 `Depends(get_current_user)`；资源上传与全库重新分析额外要求管理员（复用 admin 的管理员校验依赖，非管理员返回 403，需求 6.6）。

响应模型（Pydantic）与端点：

| 方法 & 路径 | 说明 | 主要返回 |
|---|---|---|
| `GET /explore/people?library=` | 人物聚类列表 | `[{cluster_id, name, face_count, cover_file_id, cover_face_bbox}]` |
| `GET /explore/people/{cluster_id}?page=&page_size=` | 某人物下照片 | 分页 `FileInfoResponse` 列表 |
| `PUT /explore/people/{cluster_id}` body `{name}` | 重命名人物 | `{success, name}` |
| `GET /explore/places?library=` | 城市聚合 | `[{city, province, country, count, cover_file_id}]` |
| `GET /explore/places/{city}?page=&page_size=` | 某城市照片 | 分页 `FileInfoResponse` |
| `GET /explore/scenes?library=` | 场景聚合 | `[{label, name_zh, count, cover_file_id}]` |
| `GET /explore/scenes/{label}?page=&page_size=` | 某场景照片 | 分页 `FileInfoResponse` |
| `GET /explore/resources` | 三类资源状态 | `{face, scene, geocoding}` 各含 `ResourceStatus` |
| `POST /explore/resources/{type}` (admin) | 上传/更新资源文件 | `{success, message, status}` |
| `POST /explore/reanalyze` (admin) body `{dimensions?}` | 触发存量重新分析 | `{success, queued}` |

- `library` 过滤：为空表示「全部图库」；否则按 `file_records.device_name` 过滤（LibraryFilter 取值来源为 `GET /files/devices`）。
- 照片列表项复用 `FileInfoResponse` 形态，`thumbnail_url = /api/v1/files/thumbnail/{id}`，与现有前端一致。
- `ResourceStatus`：`{type, installed, name, version, size, updated_at, enabled}`；`enabled` 反映对应 FeatureFlag。
- 资源上传采用 `multipart/form-data`（`UploadFile`），保存到 `models_root/{type}/`；保存前做扩展名/大小/基本格式校验，失败返回 400 且不覆盖既有文件（需求 6.7）；上传成功后分析器下次实例化即加载新文件（需求 6.3，分析器每次任务按需加载或检测 mtime 重载）。
- 无数据/资源未安装时，聚合接口返回**空列表**并在需要处带 `enabled/installed=false` 语义，不返回错误（需求 8.4）。

`type` 取值：`face` | `scene` | `geocoding`。对 `face`/`scene` 可能含多个文件（模型 + 标签），接口以 `type` + 表单字段名区分具体文件，或允许多文件上传到该类型目录。

### 五、Web 端

#### 路由（`web/src/router/index.ts`）

在 `/` 子路由下新增：

```ts
{ path: 'explore', name: 'Explore', component: () => import('@/views/ExploreView.vue') },
{ path: 'explore/manage', name: 'ExploreManage', component: () => import('@/views/ExploreManageView.vue') },
{ path: 'explore/people/:id', name: 'ExplorePeople', component: () => import('@/views/CategoryPhotosView.vue') },
{ path: 'explore/places/:city', name: 'ExplorePlaces', component: () => import('@/views/CategoryPhotosView.vue') },
{ path: 'explore/scenes/:label', name: 'ExploreScenes', component: () => import('@/views/CategoryPhotosView.vue') },
```

#### 侧边栏（`web/src/views/MainLayout.vue`）

在「图片浏览」下新增 `<el-menu-item index="/explore">`，图标用 Element Plus 的 `Compass`/`Search`，文案「探索」。

#### API 封装（`web/src/api/explore.ts`）

对上表接口逐一封装（复用 `http`）：`getPeople/getPeoplePhotos/renamePerson/getPlaces/getPlacePhotos/getScenes/getScenePhotos/getResources/uploadResource/reanalyze`，并复用 `files.ts` 的 `getThumbnailUrl`。

#### `ExploreView.vue`

- 顶部：标题「探索」+ LibraryFilter 下拉（选项来自 `getDeviceStats()`，默认「全部图库」）+ 右侧「管理」按钮跳 `/explore/manage`。
- 三个分区组件（可抽 `ExploreSection.vue`）：
  - 人物：圆形头像（封面用 `getThumbnailUrl(cover_file_id)`，理想情况按 `cover_face_bbox` 裁剪，MVP 可先用整图圆形裁切）+ 名称，点击进 `/explore/people/:id`。
  - 地点：首位「地图」入口卡片 + 城市卡片，点击进 `/explore/places/:city`。
  - 场景：场景卡片，点击进 `/explore/scenes/:label`。
- 横向滚动容器（overflow-x）。每个分区独立请求、独立加载态；空/未启用时显示占位说明（需求 9.1）。

#### `CategoryPhotosView.vue`

- 依 route name 区分维度，调用对应详情接口，分页加载，渲染缩略图网格，复用 `ImagePreview` 预览。
- 人物页额外提供「重命名」入口（`renamePerson`）。

#### `ExploreManageView.vue`

- 三张资源卡片（人脸模型 / 场景模型 / 地理编码库），展示 `ResourceStatus`；`el-upload` 手动上传更新；「重新分析」按钮调用 `reanalyze`。
- 非管理员：隐藏/禁用上传与重新分析操作（前端据 `authStore.isAdmin`），后端二次校验兜底。

## Data Models

- 新增四表见上，均含 `user_id` 做隔离；`file_records` 不变。
- 前端类型（`explore.ts`）：`PersonCluster`、`PlaceGroup`、`SceneGroup`、`ResourceStatus`、`ExplorePhoto`（对齐 `FileInfo`）。
- 聚类 `centroid`、人脸 `embedding` 以 `float32` 字节存 BLOB；读写用 `numpy.frombuffer/tobytes`（numpy 随 onnxruntime 提供，属可选依赖范畴）。

## Error Handling

- **依赖/资源缺失**：ONNX 运行时或模型文件缺失时，对应分析器 `available=False`，`AnalysisService` 跳过该维度并记录一次性日志；上传与既有接口不受影响（需求 7.4、9.2）。
- **单张分析失败**：`analysis_worker_task` 捕获单文件异常、记录日志、继续处理下一项，不使 worker 崩溃。
- **无 GPS / 无人脸 / 场景低置信**：正常「无结果」，不写记录、不报错（需求 2.1、3.2）。
- **资源上传校验失败**：返回 400 并保留既有资源不被破坏（需求 6.7）。
- **聚合接口无数据**：返回空集合 + `installed/enabled` 标志，前端降级占位（需求 8.4、9.1）。
- **权限**：非管理员访问上传/全库重新分析返回 403（需求 6.6）。
- **重复分析**：写入前按 `file_id` 清理该维度旧结果，保证幂等（需求 7.5）。

## Testing Strategy

- **属性测试（≥100 次迭代，pytest + Hypothesis）：**
  - `parse_gps_ifd`：随机有理数 + N/S/E/W 参考，验证换算范围（纬度∈[-90,90]、经度∈[-180,180]）与符号正确性、无 GPS 返回 None（Property 1）。
  - `filter_scenes`：随机分数向量与阈值，验证「输出均 ≥ 阈值、且为输入子集、按置信度降序」（Property 2）。
  - `assign_cluster`：随机向量与既有聚类，验证「返回值要么是相似度最高且 ≥ 阈值的既有聚类、要么为 None（新建）」的判定一致性与非负性（Property 3）。
- **示例/单元测试：**
  - `nearest_city`：给定小城市集与已知坐标，返回期望城市。
  - 建表幂等：重复 `init_db` 不报错、四表存在。
  - ExploreAPI：mock 分析结果，验证聚合/详情/重命名/权限（管理员 vs 非管理员）/降级空集合。
  - 上传校验：非法扩展名/超限返回 400 且不覆盖既有文件。
- **前端：** `ExploreView` 空数据占位渲染、LibraryFilter 过滤透传、点击导航到 `CategoryPhotosView`；管理页按 `isAdmin` 控制操作可见性。
- **集成：** 端到端 mock 一条上传→入队→分析→聚合可查询的链路（对分析器打桩，避免依赖真实模型）。

属性测试标签格式：**Feature: web-explore-people-places-scenes, Property {number}: {property_text}**。

## Correctness Properties

*属性是指在系统所有有效执行中都应成立的特征或行为。*

### Property 1: GPS 解析范围与方向正确

*对于任意* 合法的 GPS IFD（含纬度/经度有理数分量与 N/S/E/W 参考方向），`parse_gps_ifd` 返回的纬度落在 [-90, 90]、经度落在 [-180, 180]，且 S/W 参考产生负号、N/E 产生正号；缺少必要分量或参考方向时返回 None。

**Validates: Requirements 2.1**

### Property 2: 场景阈值过滤正确、单调且有序

*对于任意* 分数向量、标签集合与阈值 `min_conf`，`filter_scenes` 的输出满足：每一项置信度 ≥ `min_conf`、每一项标签均来自输入标签集合、输出按置信度降序排列，且不含重复标签；当所有分数 < `min_conf` 时输出为空。

**Validates: Requirements 3.1, 3.2**

### Property 3: 人脸聚类归并决策一致

*对于任意* 待归并特征向量、既有聚类中心集合与相似度阈值 `t`，`assign_cluster` 当且仅当存在相似度 ≥ `t` 的既有聚类时返回其中相似度最高者的 `cluster_id`，否则返回 None（表示新建）；返回的 `cluster_id` 必属于输入聚类集合。

**Validates: Requirements 4.2**

### Property 4: 用户隔离与重复分析幂等

*对于任意* 分析结果写入，其记录的 `user_id` 恒等于被分析文件的归属用户；且对同一文件同一维度重复执行分析后，该维度结果集合与单次执行等价（不产生重复累积）。

**Validates: Requirements 2.6, 3.7, 4.7, 7.5, 8.1**
