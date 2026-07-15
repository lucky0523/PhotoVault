# Implementation Plan: Web 探索页（人物 / 地点 / 场景）web-explore-people-places-scenes

## Overview

本计划将设计拆解为增量式编码任务，按「数据层与配置 → 分析流水线 → 接口 → Web 端 → 联调」的顺序推进。三类分析能力（地点 / 场景 / 人物）可独立开关，地点仅依赖 Pillow 可优先落地；场景与人物依赖可选的 ONNX 运行时与模型文件，缺失时优雅降级。纯逻辑（GPS 换算、场景阈值过滤、人脸聚类决策、最近邻城市）集中在纯函数中以便属性测试。每一步均建立在前序步骤之上，不留孤立、未接入的代码。

带 `*` 的子任务为可选（属性测试/组件测试），可为快速交付 MVP 跳过。

## Tasks

- [x] 1. 数据层与配置基础
  - [x] 1.1 新增分析结果表与索引
    - 修改 `server/app/core/database.py`：在 `init_db` 中以 `CREATE TABLE IF NOT EXISTS` 新增 `photo_gps`、`photo_scenes`、`face_clusters`、`faces` 四表及对应索引；保持幂等，不改动 `file_records`
    - _Requirements: 8.1, 9.3_
  - [x] 1.2 新增分析相关配置项
    - 修改 `server/app/core/config.py`：`Settings` 新增 `enable_place`（默认 True）、`enable_scene`/`enable_face`（默认 False）、`models_root`、`scene_min_confidence`、`face_det_min_score`、`face_cluster_similarity`；`model_validator` 派生 `models_root` 默认值；`_flatten_yaml` 新增 `analysis:` 段映射
    - _Requirements: 7.2, 6.1_
  - [x] 1.3 建表幂等单元测试
    - 重复调用 `init_db` 不报错，四表与索引存在
    - _Requirements: 8.1_

- [x] 2. 分析流水线：纯逻辑与地点分析（仅依赖 Pillow）
  - [x] 2.1 GPS EXIF 解析与最近邻城市（纯函数）
    - 新建 `server/app/services/place_analyzer.py`：`parse_gps_ifd(gps_ifd) -> Optional[(lat, lon)]`（有理数 + N/S/E/W 换算）与 `nearest_city(lat, lon, cities) -> Optional[CityRecord]`
    - _Requirements: 2.1, 2.2_
  - [x] 2.2 GPS 解析属性测试
    - **Property 1: GPS 解析范围与方向正确** — **Validates: Requirements 2.1**
  - [x] 2.3 PlaceAnalyzer 落库与资源可用性
    - `PlaceAnalyzer.analyze(user_id, file_id, path)`：Pillow 取 GPS IFD → 逆地理编码（加载 `geocoding/cities.db`）→ 写 `photo_gps`（写前按 file_id 清旧）；`available` 依据 `cities.db` 是否存在；未安装时可仅存经纬度不填城市
    - _Requirements: 2.2, 2.5, 2.6, 7.5_

- [x] 3. 分析流水线：编排、队列与后台 worker
  - [x] 3.1 AnalysisService 编排
    - 新建 `server/app/services/analysis_service.py`：按 FeatureFlag + 各分析器 `available` 调度；解析文件物理路径；单维度异常隔离
    - 场景/人脸分析器先落 `available=False` 的空实现桩（第 5、6 节补全），保证地点链路可独立跑通
    - _Requirements: 7.1, 7.4, 9.2_
  - [x] 3.2 分析队列与后台 worker
    - 新建 `server/app/services/analysis_queue.py`（`asyncio.Queue` 单例 + `enqueue_analysis` / `enqueue_reanalysis_all`）
    - 修改 `server/app/services/background_tasks.py`：新增 `analysis_worker_task()` 消费队列并调用 `AnalysisService.analyze_file`，异常仅记录；在 `start_background_tasks()` 注册该任务
    - _Requirements: 7.1, 7.4_
  - [x] 3.3 上传链路挂钩
    - 修改 `server/app/services/upload_service.py`：在建立 `FileRecord`（finalize 与去重建引用处）后调用 `enqueue_analysis(file_id, user_id)`，不阻塞上传响应
    - _Requirements: 7.1_
  - [x] 3.4 存量回填脚本
    - 新建 `server/scripts/backfill_analysis.py`（仿 `backfill_focal_length.py`）：对未分析/指定重分析照片调用 `AnalysisService.analyze_file`
    - _Requirements: 7.3, 7.5_

- [x] 4. ExploreAPI 与 Web 端骨架（地点可端到端）
  - [x] 4.1 explore 路由与聚合/详情接口
    - 新建 `server/app/api/explore.py`：实现 `GET /explore/people|/places|/scenes`、`GET /explore/{dim}/{key}`（分页）、`PUT /explore/people/{id}`；`library` 过滤；无数据返回空集合
    - 在 `server/app/main.py::_register_routes` 注册 `explore_router`（prefix `/api/v1`）
    - _Requirements: 2.3, 2.4, 3.3, 3.4, 4.3, 4.4, 4.5, 8.2, 8.4_
  - [x] 4.2 资源状态、上传与重新分析接口
    - `GET /explore/resources`（ResourceStatus）、`POST /explore/resources/{type}`（管理员，multipart 校验保存）、`POST /explore/reanalyze`（管理员，批量 enqueue）
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 4.3 ExploreAPI 单元测试
    - mock 分析结果验证聚合/详情/重命名/权限/降级空集合；上传非法文件返回 400 且不覆盖既有资源
    - _Requirements: 6.6, 6.7, 8.4_
  - [x] 4.4 前端 API 封装与路由/菜单接入
    - 新建 `web/src/api/explore.ts`；`web/src/router/index.ts` 新增 `/explore`、`/explore/manage`、`/explore/{dim}/:key`；`web/src/views/MainLayout.vue` 侧边栏新增「探索」菜单
    - _Requirements: 1.1, 8.3_
  - [x] 4.5 ExploreView 三段式布局
    - 新建 `web/src/views/ExploreView.vue`（人物圆形头像 / 地点卡片含「地图」入口 / 场景卡片，横向滚动）+ 顶部 LibraryFilter（默认「全部图库」，选项来自 `getDeviceStats`）+「管理」入口
    - 各分区独立加载态与空/未启用占位降级
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 9.1_
  - [x] 4.6 CategoryPhotosView 分类照片网格
    - 新建 `web/src/views/CategoryPhotosView.vue`：按 route 维度调详情接口、分页、复用 `getThumbnailUrl` 与 `ImagePreview`；人物页含「重命名」；「地图」入口展示按城市汇总的地点总览
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
  - [x] 4.7 ExploreManageView 资源管理页
    - 新建 `web/src/views/ExploreManageView.vue`：三类资源状态卡片 + `el-upload` 上传更新 + 「重新分析」；按 `authStore.isAdmin` 控制操作可见性
    - _Requirements: 6.1, 6.2, 6.4_

- [x] 5. 场景分析（可选 ONNX）
  - [x] 5.1 场景阈值过滤纯函数
    - `server/app/services/scene_analyzer.py`：`filter_scenes(scores, labels, min_conf) -> list[(label, conf)]`（降序、去重、阈值过滤）
    - _Requirements: 3.2_
  - [x] 5.2 场景过滤属性测试
    - **Property 2: 场景阈值过滤正确、单调且有序** — **Validates: Requirements 3.1, 3.2**
  - [x] 5.3 SceneAnalyzer 推理与落库
    - 惰性 `import onnxruntime`（失败 `available=False`）；加载 `scenes/scene.onnx` + `labels_zh.json`；预处理→推理→`filter_scenes`→写 `photo_scenes`（写前按 file_id 清旧）；中文标签映射
    - _Requirements: 3.1, 3.5, 3.6, 3.7, 7.5, 9.2_

- [x] 6. 人物分析（可选 ONNX）
  - [x] 6.1 人脸聚类决策纯函数
    - `server/app/services/face_analyzer.py`：`assign_cluster(embedding, clusters, threshold) -> Optional[int]`（余弦相似度最高且 ≥ 阈值否则 None）
    - _Requirements: 4.2_
  - [x] 6.2 人脸聚类属性测试
    - **Property 3: 人脸聚类归并决策一致** — **Validates: Requirements 4.2**
  - [x] 6.3 FaceAnalyzer 检测/特征/增量聚类落库
    - 惰性加载检测 + 特征 ONNX 模型；检测阈值过滤→对齐裁剪→特征（L2 归一化）→`assign_cluster`→写 `faces`，增量更新 `face_clusters.centroid`/`face_count`/`cover_face_id`（写前按 file_id 清旧人脸）
    - _Requirements: 4.1, 4.2, 4.6, 4.7, 7.5, 9.2_
  - [x] 6.4 用户隔离与幂等测试
    - **Property 4: 用户隔离与重复分析幂等** — **Validates: Requirements 2.6, 3.7, 4.7, 7.5, 8.1**

- [x] 7. Checkpoint - 端到端联调与测试
  - 后端：pytest 全绿（含新增属性/单元测试）；`init_db` 幂等；地点链路可在无 ONNX 环境跑通
  - 前端：`npm run build`（vue-tsc + vite）通过；探索页在空数据/未启用时正常降级
  - 联调：mock 上传→入队→分析→ExploreAPI 聚合可查询；管理页上传资源后分析器可加载
  - _Requirements: 全部_

## Notes

- 三维度可独立交付：**地点（第 2 节，仅 Pillow）** 可先行上线；场景/人物（第 5、6 节）依赖可选 ONNX 运行时与模型文件，缺失时降级。
- ONNX 运行时与模型文件、离线地理编码库均为**可选资源**，不加入 `requirements.txt` 强制依赖；建议在文档中给出可选安装说明（如 `requirements-analysis.txt`）。
- 资源文件通过 Web 管理入口上传到 `models_root/{face|scene|geocoding}/`，无需重启即被下次分析加载。
- 每个任务均引用具体需求条目，保证可追溯性。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1", "5.1", "6.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "5.2", "6.2"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["3.2", "5.3", "6.3"] },
    { "id": 5, "tasks": ["3.3", "3.4", "6.4"] },
    { "id": 6, "tasks": ["4.1", "4.2"] },
    { "id": 7, "tasks": ["4.3", "4.4"] },
    { "id": 8, "tasks": ["4.5", "4.6", "4.7"] },
    { "id": 9, "tasks": ["7"] }
  ]
}
```
