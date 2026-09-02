# How to Build an fnOS Native App

本文记录将 PhotoVault 打包为飞牛 fnOS Native 应用时的架构选择、构建流程、常见陷阱和验证清单。内容适用于包含 Web UI、常驻后端服务、Python 原生依赖和用户数据目录的应用。

> 本文中的命令、目录和变量以 PhotoVault 为例；将 `photovault` 替换为自己的应用名即可。

## 1. 先理解 fnOS Native 应用的边界

Native 应用不是 Docker 容器。应用文件安装后，fnOS 会提供一组固定目录和生命周期脚本环境变量：

| 用途 | fnOS 变量 | 建议存放内容 |
|---|---|---|
| 已安装应用文件 | `TRIM_APPDEST` | 服务端代码、前端静态文件、应用第三方依赖 |
| 应用配置 | `TRIM_PKGETC` | 密钥、用户配置文件 |
| 运行时数据 | `TRIM_PKGVAR` | 日志、PID、缓存、模型文件 |
| 用户共享数据 | `TRIM_DATA_SHARE_PATHS` | 照片、数据库、导入导出文件、回收站 |
| 用户可见错误 | `TRIM_TEMP_LOGFILE` | 生命周期脚本失败时的简短错误说明 |

不要在脚本中硬编码 `/var/apps/...`、`/vol1/...` 等实际安装路径；优先使用 `TRIM_*` 变量。`TRIM_APPDEST`、`TRIM_PKGETC` 和 `TRIM_PKGVAR` 在不同 NAS 或不同安装卷上可能指向不同位置。

PhotoVault 当前的数据分配如下：

```text
TRIM_APPDEST/
├── server/       FastAPI 服务端源码
├── web/dist/     Vue 前端构建产物
├── pylibs/       pip install --target 安装的第三方依赖
├── lib/env.sh    运行环境映射脚本
└── ui/           桌面入口与图标

TRIM_PKGETC/
├── jwt_secret    JWT 签名密钥
├── workdir       安装向导选定的工作目录（单行绝对路径，留空未自定义时不存在）
└── config.yaml   可选高级配置

TRIM_PKGVAR/
├── app.log       服务日志
├── app.pid       服务 PID
├── logs/         应用日志目录
└── models/       下载的 ONNX / 地理数据模型

工作目录（默认为 TRIM_DATA_SHARE_PATHS 的首个目录，可在安装向导中自定义）/
├── photovault.db
├── .thumbnails/
├── .chunks/
├── .models/      旧版本可能留下的模型目录
└── <username>/   用户照片、回收站和媒体文件
```

照片和 SQLite 数据库放在同一个工作目录中：两者是强绑定的用户资产。这样用户可以整体迁移、备份或在重新安装后继续使用，而不会发生“照片保留、数据库元数据丢失”的状态。

## 1.1 安装向导收集的值只在安装期存在

`wizard/install` 的字段会成为同名环境变量，但**只在安装那一趟的生命周期脚本里可见**。官方文档只对 `wizard/config` 承诺“提交后的值会继续作为环境变量提供给应用使用”，`wizard/install` 没有这个承诺——`cmd/main start` 时读不到 `$wizard_work_dir`。

所以凡是安装时收集、运行时还要用的值，都必须自己落盘。PhotoVault 的工作目录走的是和 `jwt_secret` 完全一样的路子：

1. `cmd/install_callback` 调用 `pv_persist_workdir`，校验后写入 `TRIM_PKGETC/workdir`
2. `app/lib/env.sh` 在 source 的末尾调用 `pv_resolve_storage_root`，从该文件读回，读不到则回落 data-share

放 `TRIM_PKGETC` 而不是 `TRIM_PKGVAR`，因为升级要保留：丢了这个文件，升级后工作目录会悄悄跳回默认共享目录，用户看到的现象是“照片全没了”。

两个容易漏掉的边界：

- **重装留空**。卸载不一定清 `TRIM_PKGETC`，若用户重装时把工作目录留空，必须**删掉**旧记录，否则会继承一个他并没有选择的目录。
- **路径类输入要当攻击面对待**。除了绝对路径和系统目录黑名单，最有价值的一条是校验**上层目录必须已存在**：否则存储空间没挂载时 `mkdir -p` 会在系统盘上凭空建出整条路径，照片越备份越多直到写满系统盘。同理 `cmd/main` 的 preflight 只检查目录存在、绝不 `mkdir`，因为服务端启动时的 `ensure_runtime_directories` 会 `mkdir -p`，必须在它之前拦下来。

## 2. `fnpack` 不是编译器

`fnpack build` 只负责打包。当前 `fnpack 1.2.3` 的 build 命令只有一个参数：

```bash
fnpack build --directory <package-directory>
```

它不会：

- 编译 Python、Go、Rust、C/C++ 代码；
- 将 ARM 二进制转换成 x86_64；
- 下载 Python wheel；
- 根据 NAS 架构自动替换包内原生依赖。

它会：

1. 校验基础目录、manifest、JSON 和图标；
2. 将 `app/` 压缩为 `app.tgz`；
3. 生成 `.fpk`；
4. 规范化 manifest 格式并写入 `checksum`。

因此，**必须在调用 fnpack 之前完成所有目标架构的构建或依赖准备**。

## 3. `manifest.platform` 与真实 ABI 是两层概念

`manifest` 中的 `platform` 是 fnOS 应用中心使用的兼容性声明，不是 ELF 架构字符串。

合法值包括：

```ini
platform=x86
platform=arm
platform=all
```

不要写：

```ini
platform=x86_64
platform=amd64
platform=x86-64
platform=arm64
platform=aarch64
```

这些不是 fnOS manifest 的平台值，fnpack 会拒绝其中的非法值。

PhotoVault 当前固定使用：

```ini
platform=all
```

这可以避免应用中心按粗粒度 CPU 类别拒绝安装；但它**不会让包内二进制自动变成多架构**。

例如，一个包即使写了：

```ini
platform=all
```

如果其中的原生扩展是：

```text
bcrypt/_bcrypt.abi3.so: ELF 64-bit x86-64
PIL/_imaging.cpython-312-x86_64-linux-gnu.so
```

它仍然只能在 x86_64 设备上运行。`--arch x86_64` 的职责是决定包内 ELF 和 wheel 的实际目标，而不是决定 manifest 的值。

## 4. Python：使用 fnOS 运行时，不在包内重复携带解释器

PhotoVault Native 包固定声明：

```ini
install_dep_apps=python312
```

并从 fnOS 运行时执行 Python：

```bash
export PATH=/var/apps/python312/target/bin:$PATH
PYTHON=/var/apps/python312/target/bin/python3
```

当前包中**不包含**：

```text
app/python/
```

但仍包含：

```text
app/pylibs/
```

两者差异如下：

| 内容 | 谁提供 | 是否位于 FPK 中 |
|---|---|---|
| CPython 3.12 解释器和标准库 | fnOS 的 `python312` 应用 | 否 |
| FastAPI、Uvicorn、Pillow、bcrypt、Pydantic 等应用依赖 | PhotoVault | 是，位于 `pylibs/` |

系统有 Python 不代表系统有应用所需的第三方库，因此不能省掉 `pylibs/`。

### Python 版本与 ABI 必须一致

既然运行时是 Python 3.12，带原生扩展的依赖也必须针对 CPython 3.12 下载：

```text
cpython-312-x86_64-linux-gnu.so
```

不能把 CPython 3.11 的原生模块交给 Python 3.12 使用。纯 Python 包有时不会立刻出错，但 Pillow、pydantic-core、httptools、watchfiles 等带 `.so` 的模块会在导入时失败。

三者必须绑定管理：

```text
fnOS runtime package: python312
Python ABI:           CPython 3.12
native wheel tag:     cpython-312-<target-arch>
```

## 5. 在 macOS 上准备 Linux x86_64 Python 依赖

不需要 Docker 或 Linux 编译机也可以准备目标 Linux wheel。使用 pip 的平台选择参数下载预编译二进制包：

```bash
python3 -m pip install \
  --target pylibs \
  --platform manylinux2014_x86_64 \
  --platform manylinux_2_28_x86_64 \
  --python-version 3.12 \
  --implementation cp \
  --only-binary=:all: \
  --no-compile \
  -r server/requirements.txt
```

这不是在 macOS 上“编译 Linux 程序”，而是要求 pip 下载与目标环境匹配的 manylinux wheel。

关键限制：

- 必须使用 `--only-binary=:all:`，避免 pip 在开发机上尝试源码编译；
- 某些 Python 包可能没有特定 Python 版本或架构的 wheel；
- pip 成功不等于 ABI 一定正确，必须检查包内 `.so`；
- `--platform`、`--python-version` 与运行时 Python 必须同步调整。

示例验证：

```bash
file build/fnos/x86_64/photovault/app/pylibs/PIL/_imaging*.so
```

目标结果应包含：

```text
ELF 64-bit ... x86-64
```

## 6. 目录布局不是可自由调整的

PhotoVault 的 `server/app/main.py` 通过相对位置查找前端：从 `app/main.py` 向上退三层后再拼接 `web/dist`。

Native 包必须保留：

```text
$TRIM_APPDEST/
├── server/app/main.py
└── web/dist/index.html
```

不能随意将前端移动到：

```text
server/static/
app/public/
assets/
```

否则服务可能启动成功，但 Web UI 会出现：

```text
404 Frontend not found
```

在打包后必须对 `.fpk` 二次解包，确认 `app.tgz` 中存在：

```text
server/app/main.py
web/dist/index.html
```

## 7. 生命周期脚本应覆盖完整流程

fnOS Native 包的 `cmd/` 目录通常包含九个脚本：

```text
cmd/main
cmd/install_init
cmd/install_callback
cmd/upgrade_init
cmd/upgrade_callback
cmd/uninstall_init
cmd/uninstall_callback
cmd/config_init
cmd/config_callback
```

职责建议：

| 脚本 | 建议职责 |
|---|---|
| `install_init` | 安装前架构和基本环境检查 |
| `install_callback` | 创建配置目录、生成密钥、验证 data-share 和运行时 |
| `main` | `start`、`stop`、`status` |
| `upgrade_init` | 记录版本变化、必要的升级前检查 |
| `upgrade_callback` | 配置/密钥迁移、升级后初始化 |
| `config_init` | 校验用户在设置页提交的配置 |
| `config_callback` | 配置修改后按需重启服务 |
| `uninstall_init` | 说明用户数据保留策略 |
| `uninstall_callback` | 完成卸载后提示数据位置 |

脚本要尽量幂等：安装恢复、重试、升级或配置提交都可能重复运行。

### 退出码约定

`cmd/main` 的常用约定：

```text
0：成功；status 中表示服务正在运行
1：失败
3：status 中表示服务未运行
```

如果生命周期脚本失败，应在非零退出前向 `TRIM_TEMP_LOGFILE` 写入短而可执行的错误信息：

```bash
echo "共享数据目录不可写: $DATA_DIR" > "$TRIM_TEMP_LOGFILE"
exit 1
```

长期诊断日志写入：

```text
$TRIM_PKGVAR/app.log
```

## 8. data-share、ACL 和用户数据策略

PhotoVault 的资源声明：

```json
{
  "data-share": {
    "shares": [
      { "name": "photovault" }
    ]
  }
}
```

fnOS 会创建该目录，并为包用户提供相应访问权限。共享目录使用 Windows ACL 模型，不能完全依赖 POSIX 权限位判断。

不要只写：

```bash
test -w "$DATA_DIR"
```

更可靠的是做最小真实写入探测：

```bash
probe="$DATA_DIR/.write_probe.$$"
if ( : > "$probe" ) 2>/dev/null; then
  rm -f "$probe"
else
  echo "共享数据目录不可写: $DATA_DIR" > "$TRIM_TEMP_LOGFILE"
  exit 1
fi
```

### 卸载策略

照片、SQLite 元数据、缩略图和回收站属于用户数据。卸载脚本不应自动删除它们；应在卸载提示中明确说明：

```text
应用已卸载，照片与数据库仍保留在共享目录 photovault。
如需彻底删除，请在确认备份后手动删除该共享目录。
```

## 9. 配置、密钥和数据不要混放

PhotoVault 的 JWT 密钥应在首次安装时生成：

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
```

并保存在：

```text
$TRIM_PKGETC/jwt_secret
```

原因：

- 不能使用代码中的默认 JWT 密钥；
- 密钥不应放在可升级覆盖的 `TRIM_APPDEST`；
- 密钥不应只放在临时目录；
- 放在配置目录可以在升级后保持 token 有效；
- 旧版本若把密钥放在 `TRIM_PKGVAR`，升级回调应兼容迁移。

## 10. 不要盲目使用多 worker

对 PhotoVault，Native 启动命令固定使用：

```bash
python3 -m uvicorn app.main:app \
  --host 0.0.0.0 \
  --port "$TRIM_SERVICE_PORT" \
  --workers 1
```

原因：

- 图片分析队列是进程内 `asyncio.Queue`；
- 清理、监控、回收站、分析等后台任务都在 FastAPI 进程内；
- SQLite 是单文件数据库；
- 多 worker 会产生多份分析队列和重复后台任务，并增加 SQLite 写锁竞争。

多 worker 不是免费的性能优化。先确认状态、队列、定时任务和数据库是否支持多进程，再决定是否扩容。

## 11. 数据库配置优先用纯路径

早期版本里各后台任务对 `database_url` 的解析不一致：会话清理任务把带前缀的 URL 原样交给 `aiosqlite.connect()`，从而在文件系统里建出第二个空数据库。现在服务端已统一走 `app.core.config.sqlite_path_from_url()`，两种写法都能正确解析：

```text
PHOTOVAULT_DATABASE_URL=sqlite+aiosqlite:///srv/photos/photovault.db   # 可用
PHOTOVAULT_DATABASE_URL=/srv/photos/photovault.db                      # 推荐
```

仍推荐纯路径：少一层歧义，日志里出现的也是可以直接 `ls` 的路径。

存储位置有四个互相独立的变量，未设置的才回落到 `PHOTOVAULT_STORAGE_ROOT`：

```bash
export PHOTOVAULT_STORAGE_ROOT="$DATA_DIR"      # 仅作为下面各项的默认基准
export PHOTOVAULT_MEDIA_ROOT="$DATA_DIR"        # 照片：必须在共享目录里才能被文件管理器看到
export PHOTOVAULT_LOG_DIR="$TRIM_PKGVAR/logs"   # 日志：应用自身运行数据，随包清理
export PHOTOVAULT_MODELS_ROOT="$TRIM_PKGVAR/models"
unset PHOTOVAULT_DATABASE_URL                   # 回落到 $DATA_DIR/photovault.db
```

数据库要和照片一起留在共享目录：升级和重装都不能丢，而 `TRIM_PKGVAR` 不保证保留。

## 12. fnpack 的退出码和旧产物陷阱

`fnpack 1.2.3` 有一个重要行为：某些校验失败时可能打印：

```text
Packing failed. ...
```

但 shell 退出码仍可能是 `0`。

因此不能仅依赖：

```bash
fnpack build --directory "$PACKAGE_DIR"
if [ $? -eq 0 ]; then
  echo success
fi
```

建议：

1. 构建前删除固定输出名 `photovault.fpk`；
2. 捕获 fnpack 输出；
3. 检查输出中是否存在 `fail`；
4. 确认新的 FPK 文件确实生成；
5. 对 FPK 再次解包和校验。

删除旧产物很重要：如果上次成功生成的 `.fpk` 还在，本次失败后仅检查“文件是否存在”会得到假成功。

## 13. Shell 跨平台兼容性

构建脚本需要在 macOS 上执行时，不能默认 Bash 5 可用。

### 不要依赖关联数组

macOS 自带 Bash 常见版本为 3.2，不支持：

```bash
declare -A ARCH_MAP
```

应使用兼容写法：

```bash
case "$arch" in
  x86_64)
    PIP_ARCH=x86_64
    ELF_ARCH=x86-64
    ;;
  aarch64)
    PIP_ARCH=aarch64
    ELF_ARCH=aarch64
    ;;
esac
```

### 变量紧跟中文标点时使用花括号

不推荐：

```bash
echo "$size）"
```

应写：

```bash
echo "${size}）"
```

否则多字节中文字符可能被 shell 当作变量名的一部分，配合 `set -u` 时会触发难以理解的 `unbound variable`。

### `pipefail` 与 `grep -q`

启用：

```bash
set -o pipefail
```

后，以下写法可能因为 `grep -q` 提前退出、上游收到 SIGPIPE 而出现误判：

```bash
find ... | grep -q ...
```

尤其危险的是：

```bash
! find ... | grep -q .
```

它可能在存在泄漏文件时错误通过。

更稳妥的方式是先收集结果，再判断：

```bash
leaked="$(find "$dir" -name '.env' -print -quit 2>/dev/null || true)"
test -z "$leaked"
```

## 14. FPK 构建与验证清单

### 构建前

- [ ] `manifest` 的 `appname`、`version`、`platform`、`service_port` 合法；
- [ ] 固定声明 `install_dep_apps=python312`；
- [ ] `config/privilege` 和 `config/resource` 是合法 JSON；
- [ ] 根目录存在 `ICON.PNG`（64x64）和 `ICON_256.PNG`（256x256）；
- [ ] `app/ui/config` 中的端口与 manifest 一致；
- [ ] `cmd/*` 生命周期脚本可执行且 `bash -n` 通过；
- [ ] 前端位于 `app/web/dist/index.html`；
- [ ] 服务端位于 `app/server/app/main.py`；
- [ ] 不包含开发 `.env`、`.venv`、测试目录、开发数据、`__pycache__`；
- [ ] 不包含 `app/python/` 解释器目录。

### 构建命令

PhotoVault x86_64 包：

```bash
./scripts/build.sh fpk --arch x86_64
```

最终产物：

```text
build/fnos/x86_64/photovault-<version>-x86_64.fpk
```

### 构建后

检查打包后的 manifest：

```bash
tar -xOzf build/fnos/x86_64/photovault-<version>-x86_64.fpk manifest
```

应包含：

```ini
platform = all
install_dep_apps = python312
service_port = 8000
```

确认包内没有解释器：

```bash
tmp="$(mktemp -d)"
tar -xzf build/fnos/x86_64/photovault-<version>-x86_64.fpk -C "$tmp" app.tgz
tar -tzf "$tmp/app.tgz" | grep '^python/'
```

该命令应无输出。

确认原生依赖与 Python 3.12/x86_64 ABI 匹配：

```bash
tar -tzf "$tmp/app.tgz" | grep 'cpython-312-x86_64-linux-gnu\.so$'
```

## 15. 真机排障顺序

“无法安装”和“无法启动”需要分层判断：

| 阶段 | 典型问题 |
|---|---|
| 本地 fnpack 校验 | manifest、JSON、图标、目录结构错误 |
| 应用中心安装器 | 同名应用残留、端口冲突、运行时依赖无法满足、资源创建失败 |
| 生命周期回调 | `python312` 不可用、data-share ACL 不可写、密钥生成失败 |
| 服务启动 | Python ABI 不匹配、缺少 wheel、路径错误、端口监听失败、数据库错误 |
| 页面访问 | 前端 dist 路径错误、API base URL 错误、JWT 或 CORS 问题 |

推荐先测试不带业务数据的干净安装，再检查：

1. 应用中心的完整错误文本和时间；
2. `TRIM_TEMP_LOGFILE` 显示的回调错误；
3. `$TRIM_PKGVAR/app.log`；
4. 端口是否已被占用；
5. `python312` 是否已安装并且可执行；
6. data-share 是否创建成功且包用户可写；
7. `.fpk` 中 wheel 的 ABI 是否为 `cp312 + x86_64`。

不要仅因为“无法安装”就重新打一个不同架构的 FPK；先确定失败发生在安装器、回调还是服务运行阶段。

## 16. 当前 PhotoVault Native 包的最终约束

```ini
appname=photovault
platform=all
install_dep_apps=python312
service_port=8000
checkport=true
ctl_stop=true
```

运行时：

```text
解释器：/var/apps/python312/target/bin/python3
Python ABI：CPython 3.12
x64 原生依赖：manylinux x86_64 / cpython-312-x86_64-linux-gnu
包内解释器：不包含
```

最后一条必须明确：`platform=all` 是应用中心兼容性声明；当前 x64 构建产物中的原生 wheel 仍然只适合 x86_64 NAS。如果要发布 ARM 版本，必须重新按 `aarch64` 下载 Python 3.12 原生 wheel 并生成独立的 ARM 构建产物。
