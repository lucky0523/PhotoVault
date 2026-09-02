# PhotoVault

手机照片自动备份到 NAS 的私有化解决方案。支持 Android、iOS 和 Web 端，照片原图无损备份到你自己的服务器。

## 功能特性

- **多用户隔离** — 最多 20 个用户，各用户数据完全隔离
- **分块断点续传** — 2MB 分块上传，网络中断后自动恢复
- **SHA-256 去重** — 相同文件不会重复占用存储空间
- **年/月自动归档** — 可选按拍摄时间自动分目录存储
- **灵活存储策略** — 手动指定目录 × 年月分层，四种组合自由选择
- **后台自动备份** — WiFi + 电量充足时自动扫描并备份新照片和视频，可在设置中一键关闭改为仅手动备份
- **图片 + 视频备份** — 图片（JPEG、HEIC、RAW、AVIF…）与视频（MP4、MOV、MKV、WebM…）统一备份，Android 客户端同时扫描相册中的图片和视频
- **动态照片 & Ultra HDR** — 自动识别 Android 动态照片（Motion Photo）与 Ultra HDR 照片，Web 端可播放动态照片、以类 iOS Live Photo 图标标识动态照片、以角标标识 Ultra HDR
- **Web 浏览与播放** — 浏览器中查看已备份的照片/视频，缩略图预览（视频自动生成封面帧）、视频在线播放（支持拖动进度）、动态照片播放、原图下载
- **时间线视图** — 按年月归档浏览，支持设备 / 文件格式 / 焦段 / 日期范围多维筛选；日期选择器对未来日期和无照片的日期置灰但仍可点击
- **回收站功能** — 删除的照片移入回收站，支持恢复或彻底删除，默认 30 天后自动清理
- **客户端状态同步** — Android 客户端同步服务端照片状态（已备份/回收站/已删除）
- **三端客户端** — Android / iOS / Web

## 系统要求

| 项目 | 要求 |
|------|------|
| 操作系统 | x86_64 Linux（NAS、服务器、虚拟机均可） |
| Docker | Docker Engine 20.10+ 和 Docker Compose V2 |
| 端口 | 80（HTTP）、443（HTTPS，可选） |
| 存储 | 根据照片数量预留足够磁盘空间 |

## 快速部署

### 1. 克隆仓库

```bash
git clone https://github.com/your-org/PhotoVault.git
cd PhotoVault
```

### 2. 启动服务

```bash
docker compose up -d
```

首次启动会自动构建镜像（包含前端编译），可能需要几分钟。

### 3. 完成初始化

浏览器打开 `http://<你的IP>:80`，系统会自动跳转到初始化引导页：

1. 设置管理员用户名和密码（密码至少 8 位）
2. 确认存储路径（默认 `/data/photovault`）
3. 完成后即可登录使用

## HTTPS 配置

PhotoVault 通过 Caddy 反向代理支持三种网络模式：

### 模式 1：HTTP（默认）

适用于本地测试或纯内网部署。默认配置即可，无需修改。

访问地址：`http://<IP>:80`

### 模式 2：自签名 HTTPS（局域网）

适用于局域网内需要加密传输的场景（如 Android 客户端要求 HTTPS）。

```bash
cp Caddyfile.selfsigned Caddyfile
docker compose up -d
```

Caddy 会自动生成内部 CA 和证书。客户端首次连接需信任自签名证书。

如需指定 IP 地址，编辑 `Caddyfile` 将 `:443` 替换为：

```
https://192.168.1.100 {
    tls internal
    reverse_proxy photovault:8000
}
```

访问地址：`https://<IP>:443`

### 模式 3：Let's Encrypt（公网域名）

适用于有公网域名的正式部署，Caddy 自动获取和续期 TLS 证书。

**前提条件：**
- DNS 已将域名解析到服务器
- 端口 80 和 443 对外开放

```bash
cp Caddyfile.production Caddyfile
```

编辑 `Caddyfile`，将 `your-domain.com` 替换为你的实际域名：

```
photos.example.com {
    reverse_proxy photovault:8000
}
```

然后重启：

```bash
docker compose up -d
```

访问地址：`https://你的域名`

## 配置说明

### 环境变量

所有环境变量以 `PHOTOVAULT_` 为前缀：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `PHOTOVAULT_STORAGE_ROOT` | `/data/photovault` | 存储根目录（绝对路径）。仅作为下面四项未设置时的默认基准 |
| `PHOTOVAULT_MEDIA_ROOT` | `{storage_root}` | 照片存储目录（绝对路径） |
| `PHOTOVAULT_DATABASE_URL` | `{storage_root}/photovault.db` | SQLite 数据库路径 |
| `PHOTOVAULT_LOG_DIR` | `{storage_root}/logs` | 日志目录（绝对路径） |
| `PHOTOVAULT_MODELS_ROOT` | `{storage_root}/.models` | 分析模型目录（绝对路径） |
| `PHOTOVAULT_SERVER_HOST` | `127.0.0.1` | 服务监听地址 |
| `PHOTOVAULT_SERVER_PORT` | `8000` | 服务监听端口 |
| `PHOTOVAULT_JWT_SECRET_KEY` | `change-me-in-production` | JWT 签名密钥（**生产环境必须修改**） |
| `PHOTOVAULT_ACCESS_TOKEN_EXPIRE_HOURS` | `24` | Access Token 有效时间（小时） |
| `PHOTOVAULT_REFRESH_TOKEN_EXPIRE_DAYS` | `7` | Refresh Token 有效时间（天） |
| `PHOTOVAULT_MAX_USERS` | `20` | 最大用户数 |
| `PHOTOVAULT_CHUNK_SIZE_MB` | `2` | 分块上传大小（MB） |
| `PHOTOVAULT_SESSION_EXPIRE_DAYS` | `7` | 上传会话过期时间（天） |
| `PHOTOVAULT_TRASH_RETENTION_DAYS` | `30` | 回收站文件保留天数（过期自动清理） |
| `PHOTOVAULT_LOG_LEVEL` | `INFO` | 日志级别 |

#### 存储目录相互独立

照片、数据库、日志、模型这四个位置各自独立配置。`PHOTOVAULT_STORAGE_ROOT` **只是**它们未显式设置时的默认基准，服务端不会直接用它来存放任何数据。

因此可以只移动其中一项，其余保持原位：

```bash
# 只把照片放到大容量阵列，数据库/日志/模型仍在 storage_root 下
PHOTOVAULT_STORAGE_ROOT=/data/photovault
PHOTOVAULT_MEDIA_ROOT=/mnt/raid/photos
```

```bash
# 四项全部分开：此时 storage_root 完全不被使用
PHOTOVAULT_STORAGE_ROOT=/data/photovault
PHOTOVAULT_MEDIA_ROOT=/mnt/raid/photos          # 照片（含回收站、缩略图、分块暂存）
PHOTOVAULT_DATABASE_URL=/mnt/ssd/photovault.db  # 数据库放 SSD，随机读写更快
PHOTOVAULT_LOG_DIR=/var/log/photovault          # 日志交给系统日志分区
PHOTOVAULT_MODELS_ROOT=/opt/photovault/models   # 模型只读，可与数据分离
```

说明：

- 四项都必须是**绝对路径**，相对路径会在启动时直接报错——相对路径会随服务的工作目录变化（systemd、Docker、`run.sh` 各不相同），静默写错位置比启动失败更难排查。
- 回收站（`{username}/.trash`）、文件锁（`{username}/.locks`）、缩略图缓存（`.thumbnails`）和上传分块暂存（`.chunks`）都跟随**照片存储目录**。回收站和分块暂存必须与照片同一文件系统，否则移动文件将退化为跨盘复制。
- 磁盘空间告警与「关于服务端」页显示的可用容量，统计的是**照片存储目录**所在的卷。
- 已有部署无需改动：不设置新变量时，行为与之前完全一致。
- 迁移已有数据时请先停止服务，把目录内容整体复制到新位置后再设置变量。数据库中记录的是照片的绝对路径，直接改 `PHOTOVAULT_MEDIA_ROOT` 而不搬运文件会导致旧记录无法访问。

### config.yaml

`config.yaml` 是**可选**的，服务端不会自动生成，需要自己创建；不存在时直接使用默认值，不影响启动。

服务端按以下顺序查找，取第一个找到的（**不会**合并多个文件）：

1. 环境变量 `PHOTOVAULT_CONFIG_PATH` 指向的路径。设置了此变量就只认这一个路径，文件不存在也不再往下找
2. 当前工作目录下的 `config.yaml`
3. 当前工作目录下的 `config/config.yaml`
4. 服务端根目录（`app/` 的上一级）下的 `config.yaml`
5. 服务端根目录下的 `config/config.yaml`

对应到各部署方式的实际放置位置：

| 部署方式 | 放在哪里 |
|---|---|
| `./run.sh` 本地开发 | `server/config.yaml` 或 `server/config/config.yaml` |
| Docker | 宿主机 `./config/config.yaml`（经卷映射成为容器内 `/app/config/config.yaml`），或容器内 `/app/config.yaml` |
| 飞牛 NAS | `TRIM_PKGETC/config.yaml`，由 `PHOTOVAULT_CONFIG_PATH` 显式指定 |

Docker 用户直接把文件放进 `docker-compose.yml` 已映射的 `./config/` 目录即可，无需额外设置环境变量。

配置示例：

```yaml
server:
  host: "127.0.0.1"
  port: 8000

storage:
  root: "/data/photovault"
  # 可选：把照片单独放到别的磁盘，未设置时等于 root
  # media_root: "/mnt/raid/photos"

# 可选：数据库位置，未设置时为 {storage.root}/photovault.db
# database_url: "/mnt/ssd/photovault.db"

auth:
  access_token_expire_hours: 24
  refresh_token_expire_days: 7
  max_users: 20
  jwt_secret_key: "your-secret-key-here"

backup:
  chunk_size_mb: 2
  session_expire_days: 7

trash:
  retention_days: 30

logging:
  level: "INFO"
  # 可选：日志目录，未设置时为 {storage.root}/logs
  # dir: "/var/log/photovault"

analysis:
  # 可选：模型目录，未设置时为 {storage.root}/.models
  # models_root: "/opt/photovault/models"
```

**配置优先级**（从高到低）：环境变量 > .env 文件 > config.yaml > 默认值

因为环境变量优先级更高，已经通过环境变量设置的项，写在 `config.yaml` 里不会生效。飞牛安装包在 `env.sh` 里显式导出了存储路径、端口、注册开关等项，所以那边留给 `config.yaml` 调整的只有未被导出的部分。

## 存储说明

### 目录结构

默认配置下（四项均未单独设置）全部数据集中在存储根目录：

```
./data/photovault/              # 宿主机映射目录（对应容器内 /data/photovault）
├── photovault.db               # SQLite 数据库    -> PHOTOVAULT_DATABASE_URL
├── logs/                       # 运行日志        -> PHOTOVAULT_LOG_DIR
├── .models/                    # 分析模型        -> PHOTOVAULT_MODELS_ROOT
│                               # 以下均属照片存储 -> PHOTOVAULT_MEDIA_ROOT
├── .chunks/                    # 上传分块暂存
├── .thumbnails/                # 缩略图缓存
│   └── {username}/
└── {username}/                 # 用户备份文件
    ├── .locks/                 # 文件锁（防止并发操作）
    ├── .trash/                 # 回收站（按原路径结构存储）
    │   └── {device}/
    │       └── {source_folder}/
    └── {device}/
        └── {source_folder}/
            └── {year}/{month}/ # 若启用年月分层
```

右侧标注了每一部分对应的环境变量。设置某个变量后，该部分会整体搬到新位置，其余保持不变。

### Docker 卷映射

| 容器路径 | 宿主机路径 | 用途 |
|----------|-----------|------|
| `/data/photovault` | `./data` | 照片存储 + 数据库 + 日志 + 模型（默认全在此） |
| `/app/config` | `./config` | 可选的 `config.yaml`（放进去即生效） |
| `/etc/caddy/Caddyfile` | `./Caddyfile` | Caddy 配置 |
| `/data` (caddy) | `./caddy_data` | Caddy 证书存储 |
| `/config` (caddy) | `./caddy_config` | Caddy 运行配置 |

### 自定义存储路径

最简单的做法是整体换掉映射的宿主机目录，容器内路径不变：

```yaml
volumes:
  - /mnt/nas-disk/photos:/data/photovault
```

如果希望**只把照片**放到大容量磁盘，而数据库和日志留在系统盘，就分别映射并设置对应变量：

```yaml
services:
  photovault:
    volumes:
      - /mnt/nas-disk/photos:/photos      # 大容量阵列
      - ./data:/data/photovault           # 数据库、日志
    environment:
      - PHOTOVAULT_STORAGE_ROOT=/data/photovault
      - PHOTOVAULT_MEDIA_ROOT=/photos
```

注意变量值是**容器内**的路径，需要和上面的卷映射目标一致。数据库和日志未设置变量，因此仍位于 `/data/photovault` 下。

## 客户端连接

### Android

1. 安装 PhotoVault Android 客户端
2. 输入服务器地址（如 `192.168.1.100:80` 或 `https://photos.example.com`）
3. 使用管理员创建的账号登录
4. 授予"照片和视频"读取权限（Android 13+ 需同时授予图片与视频权限）
5. 选择要备份的相册文件夹（文件夹内的图片和视频都会被扫描备份）
6. 配置存储策略（可选按年月分层）
7. 客户端会在 WiFi + 电量 > 50% 时自动备份；升级到支持视频/动态照片的版本后会自动进行一次全量回扫，补备此前未支持的文件
8. 如需完全手动控制，可在「设置 → 备份条件」关闭「自动备份」开关；关闭后仅在本地页点击「立即备份」时才会备份，所有后台自动触发都不会上传

#### 备份方式一览（Android）

| 备份方式 | 类型 | 触发时机 | 关闭「自动备份」后 |
| --- | --- | --- | --- |
| 定时后台扫描 | 自动 | 每 15 分钟（可在设置中调整间隔） | 停止上传（仅刷新状态/数量） |
| 拍照/新增媒体 | 自动 | 相册出现新照片或视频后很快触发 | 停止上传 |
| 充电 / 连上 WiFi | 自动 | 满足条件时自动开始或续传 | 停止自动开始 / 续传 |
| 开机后 | 自动 | 重启设备后恢复定时扫描 | 停止上传 |
| 升级补备 | 自动 | 升级到支持新格式的版本后全量回扫一次 | 停止上传 |
| 新增备份文件夹 | 自动 | 添加文件夹后立即扫描 | 停止上传 |
| 意外中断后恢复 | 自动 | 应用被系统关闭后重新拉起时继续未完成的上传 | 停止恢复 |
| 立即备份（按钮） | 手动 | 在本地页点击「立即备份」 | **仍可用**（需满足网络/电量/服务器连接） |
| 单张重新备份 | 手动 | 长按某张照片选择「重新备份」 | **仍可用** |
| 失败任务重试 | 手动 | 在备份任务页点击「重试」 | **仍可用** |

> 说明：无论哪种方式，已在服务端删除（回收站 / 彻底删除）的照片都不会被自动重新上传，只能通过「单张重新备份」强制重传。关闭「自动备份」后仍会扫描相册以更新各文件夹的备份数量，只是不再自动上传。

### iOS

1. 安装 PhotoVault iOS 客户端
2. 输入服务器地址并登录
3. 授权相册访问权限
4. 选择备份范围和存储策略
5. 后台自动备份

### Web

1. 浏览器访问服务器地址
2. 登录后可浏览已备份的照片和视频（网格 / 列表 / 时间线视图）
3. **视频播放** — 点击视频缩略图（带播放角标）在灯箱中在线播放，支持拖动进度（服务端 HTTP Range 流式传输）
4. **动态照片播放** — 动态照片右上角显示类 iOS Live Photo 图标，点击"LIVE"按钮播放嵌入的动态视频
5. **Ultra HDR 标识** — Ultra HDR 照片右下角显示"HDR"角标
6. 支持拖拽上传照片
7. 支持原图下载
8. **回收站管理** — 查看已删除的照片，支持恢复或彻底删除，显示剩余保留时间

## 常见问题

### 端口 80/443 被占用

修改 `docker-compose.yml` 中 Caddy 的端口映射：

```yaml
caddy:
  ports:
    - "8080:80"
    - "8443:443"
```

然后通过 `http://<IP>:8080` 访问。

### 存储目录权限问题

确保 `./data` 目录对容器用户可写：

```bash
mkdir -p data
chmod 777 data
```

或指定容器运行用户与宿主机一致。

### 自签名证书不受信任

**Android：** 在设置 → 安全 → 加密与凭据中安装 CA 证书。

**iOS：** 通过 Safari 下载证书描述文件，在设置 → 通用 → 关于本机 → 证书信任设置中启用。

**浏览器：** 首次访问时点击"高级"→"继续访问"。

### 忘记管理员密码

删除数据库文件重新初始化（会丢失所有用户数据，照片文件不受影响）：

```bash
rm ./data/photovault.db
docker compose restart photovault
```

重新访问 Web 界面完成初始化设置。

### 上传大文件失败

默认分块大小为 2MB，7 天内可断点续传。如果网络不稳定，可适当减小分块大小：

```yaml
backup:
  chunk_size_mb: 1
```

### Docker 构建失败

确保有足够的磁盘空间和网络访问：

```bash
docker compose build --no-cache
```

## 开发指南

### 项目结构

```
PhotoVault/
├── server/          # Python FastAPI 后端
│   ├── app/         # 应用代码
│   ├── tests/       # 测试
│   └── Dockerfile
├── web/             # Vue.js 前端
├── android/         # Android 客户端 (Kotlin)
├── ios/             # iOS 客户端 (Swift)
├── scripts/         # 工具脚本
├── docker-compose.yml
├── Caddyfile
└── config/          # 运行时配置目录
```

### 本地运行后端

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 设置存储目录
export PHOTOVAULT_STORAGE_ROOT=$(pwd)/dev_data
mkdir -p dev_data

# 启动开发服务器
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

API 文档访问：`http://localhost:8000/docs`

### 本地运行前端

```bash
cd web
npm install
npm run dev
```

前端开发服务器默认在 `http://localhost:5173`，API 请求会代理到后端 `localhost:8000`。

### 运行测试

```bash
cd server
pip install -r requirements-dev.txt
pytest
```

## 许可证

MIT License
