# PhotoVault

手机照片自动备份到 NAS 的私有化解决方案。支持 Android、iOS 和 Web 端，照片原图无损备份到你自己的服务器。

## 功能特性

- **多用户隔离** — 最多 20 个用户，各用户数据完全隔离
- **分块断点续传** — 2MB 分块上传，网络中断后自动恢复
- **SHA-256 去重** — 相同文件不会重复占用存储空间
- **年/月自动归档** — 可选按拍摄时间自动分目录存储
- **灵活存储策略** — 手动指定目录 × 年月分层，四种组合自由选择
- **后台自动备份** — WiFi + 电量充足时自动扫描并备份新照片和视频
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
| `PHOTOVAULT_STORAGE_ROOT` | `/data/photovault` | 文件存储根目录（绝对路径） |
| `PHOTOVAULT_DATABASE_URL` | `{storage_root}/photovault.db` | SQLite 数据库路径 |
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

### config.yaml

也可在 `config/` 目录下创建 `config.yaml` 文件进行配置（环境变量优先级更高）：

```yaml
server:
  host: "127.0.0.1"
  port: 8000

storage:
  root: "/data/photovault"

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
```

**配置优先级**（从高到低）：环境变量 > .env 文件 > config.yaml > 默认值

## 存储说明

### 目录结构

```
./data/photovault/              # 宿主机映射目录（对应容器内 /data/photovault）
├── photovault.db               # SQLite 数据库
├── logs/                       # 运行日志
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

### Docker 卷映射

| 容器路径 | 宿主机路径 | 用途 |
|----------|-----------|------|
| `/data/photovault` | `./data` | 照片存储 + 数据库 |
| `/app/config` | `./config` | 配置文件 |
| `/etc/caddy/Caddyfile` | `./Caddyfile` | Caddy 配置 |
| `/data` (caddy) | `./caddy_data` | Caddy 证书存储 |
| `/config` (caddy) | `./caddy_config` | Caddy 运行配置 |

### 自定义存储路径

如需将照片存储到其他磁盘，修改 `docker-compose.yml` 中的卷映射：

```yaml
volumes:
  - /mnt/nas-disk/photos:/data/photovault
```

## 客户端连接

### Android

1. 安装 PhotoVault Android 客户端
2. 输入服务器地址（如 `192.168.1.100:80` 或 `https://photos.example.com`）
3. 使用管理员创建的账号登录
4. 授予"照片和视频"读取权限（Android 13+ 需同时授予图片与视频权限）
5. 选择要备份的相册文件夹（文件夹内的图片和视频都会被扫描备份）
6. 配置存储策略（可选按年月分层）
7. 客户端会在 WiFi + 电量 > 50% 时自动备份；升级到支持视频/动态照片的版本后会自动进行一次全量回扫，补备此前未支持的文件

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
