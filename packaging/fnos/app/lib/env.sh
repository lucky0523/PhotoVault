#!/bin/bash
#
# PhotoVault 运行环境解析（被 cmd/ 下的生命周期脚本 source）
#
# 这个文件是整个「不改业务源码」策略的核心：PhotoVault 的所有可配置项都能通过
# PHOTOVAULT_ 前缀的环境变量注入（server/app/core/config.py 的 env_prefix），
# 所以适配飞牛只需要在这里把 TRIM_* 映射成 PHOTOVAULT_*。
#
# 目录职责按飞牛文档划分：
#   TRIM_APPDEST            已安装的应用文件（只读代码、自带运行时）
#   TRIM_PKGETC             应用配置    -> JWT 密钥、可选的 config.yaml
#   TRIM_PKGVAR             运行时数据  -> 日志、ONNX 模型、PID
#   TRIM_DATA_SHARE_PATHS   用户可见共享目录 -> 照片、SQLite 库、缩略图、回收站
#
# 刻意把 SQLite 库放在共享目录而不是 TRIM_PKGVAR：库里存着去重记录、回收站状态、
# 人脸聚类等元数据，和照片是强绑定的。放一起可以整体备份/迁移，也避免"照片还在但
# 元数据没了"的半损坏状态。
#
# shellcheck shell=bash

PV_APPNAME="${TRIM_APPNAME:-photovault}"

# 本包**不携带 Python 解释器**，固定使用 manifest 中 install_dep_apps 声明的
# 飞牛官方 python312 运行时。飞牛文档规定其可执行文件位于：
#   /var/apps/python312/target/bin/python3
#
# 将 bin 目录加入 PATH 后，生命周期脚本与服务进程都能使用 python3；同时保留
# PV_PYTHON 的绝对路径，避免系统 PATH 被其它工具覆盖时启动到错误的解释器。
PV_DEP_RUNTIME_APP="python312"
PV_PYTHON="/var/apps/python312/target/bin/python3"
PV_RUNTIME_MODE="fnos:python312"
PATH="/var/apps/python312/target/bin:$PATH"
export PATH

PV_PYLIBS="${TRIM_APPDEST}/pylibs"
PV_SERVER_DIR="${TRIM_APPDEST}/server"
PV_WEB_DIR="${TRIM_APPDEST}/web/dist"

PV_LOG_FILE="${TRIM_PKGVAR}/app.log"
PV_PID_FILE="${TRIM_PKGVAR}/app.pid"
# 这两个目录在卸载时会被删除，cmd/uninstall_callback 里按同样的相对位置
# （TRIM_PKGVAR/logs、TRIM_PKGVAR/models）硬编码了一份——那里不能 source 本文件。
# 改动这两行时记得同步过去。
PV_LOG_DIR="${TRIM_PKGVAR}/logs"
PV_MODELS_DIR="${TRIM_PKGVAR}/models"

PV_SECRET_FILE="${TRIM_PKGETC}/jwt_secret"
PV_USER_CONFIG="${TRIM_PKGETC}/config.yaml"

# 安装向导（wizard/install）选定的工作目录落盘位置。
#
# data-share 可能声明多个目录（冒号分隔），本应用只声明一个，取第一个。
#
# 这是应用**唯一**能保证可写的用户可见目录：飞牛只对 config/resource 里申报的
# data-share 自动给运行用户授予 ACL（官方文档：这些目录使用 Windows ACL 权限模型，
# 系统会自动为应用运行用户授予所需的 ACL 访问权限）。
PV_DATA_SHARE="${TRIM_DATA_SHARE_PATHS%%:*}"

# 存储根目录固定为 data-share。
#
# 照片的实际存放位置（media_root）**不在这里决定**：它由管理员在首次启动的初始化
# 向导里，通过飞牛的目录授权（trim.file.sharedAccess）选定并授权，再由服务端自己
# 落盘、自己读取。生命周期脚本刻意不参与——安装阶段既拿不到授权，也没有界面。
PV_STORAGE_ROOT="$PV_DATA_SHARE"

PV_PORT="${TRIM_SERVICE_PORT:-8000}"
PV_FAILLOG="${TRIM_TEMP_LOGFILE:-/dev/stderr}"

# ---------------------------------------------------------------------------
# 日志与错误上报
# ---------------------------------------------------------------------------

pv_log() {
  mkdir -p "$(dirname "$PV_LOG_FILE")" 2>/dev/null
  echo "$(date '+%Y-%m-%d %H:%M:%S') [${TRIM_APP_STATUS:-?}] $1" >>"$PV_LOG_FILE" 2>/dev/null
}

# app.log 的体积上限。正常情况下它每次启停只写几行，一年也到不了 1 MB；设上限是为了
# 兜住异常场景——崩溃循环时每次重启都会往 stderr 写一份 traceback，没有上限的话会
# 慢慢吃掉系统盘（app.log 在 TRIM_PKGVAR 下，不在数据盘上）。
PV_LOG_MAX_BYTES=$((2 * 1024 * 1024))

# 超过上限就轮转，只保留一个备份，总占用因此封顶约 4 MB。
#
# 刻意不做多级轮转：这个文件是给「服务起不来时看一眼」用的，历史价值很低。完整的
# 应用日志在 {log_dir}/photovault.log 里，那份由 Python 的 RotatingFileHandler 管着
# （10 MB × 6）。
pv_rotate_app_log() {
  [ -f "$PV_LOG_FILE" ] || return 0

  local size
  size="$(wc -c <"$PV_LOG_FILE" 2>/dev/null | tr -d '[:space:]')"
  case "$size" in
    ''|*[!0-9]*) return 0 ;;
  esac
  [ "$size" -gt "$PV_LOG_MAX_BYTES" ] || return 0

  if mv -f "$PV_LOG_FILE" "${PV_LOG_FILE}.1" 2>/dev/null; then
    pv_log "Rotated previous app.log (${size} bytes) to app.log.1"
  fi
}

# 写入用户可见的错误信息。文档要求生命周期脚本在以非零码退出前把清晰的错误
# 信息写进 TRIM_TEMP_LOGFILE。
pv_fail() {
  echo "$1" >"$PV_FAILLOG" 2>/dev/null
  pv_log "ERROR: $1"
}

# ---------------------------------------------------------------------------
# 目录与密钥
# ---------------------------------------------------------------------------

pv_ensure_dirs() {
  mkdir -p "$TRIM_PKGVAR" "$TRIM_PKGETC" "$PV_LOG_DIR" "$PV_MODELS_DIR" 2>/dev/null
  return 0
}

# JWT 密钥必须在安装时生成并持久化：server/app/core/config.py 里的默认值是
# "change-me-in-production"，既不会自动生成也不会落盘。任何忘记注入的部署都能被
# 轻易伪造 token，而且所有这类部署共享同一个密钥。
#
# 放在 TRIM_PKGETC（配置目录）下，升级保留，用户不需要重新登录。
pv_ensure_secret() {
  if [ -s "$PV_SECRET_FILE" ]; then
    return 0
  fi

  mkdir -p "$TRIM_PKGETC" 2>/dev/null

  local tmp="${PV_SECRET_FILE}.tmp.$$"
  if ! ( umask 077 && "$PV_PYTHON" -c 'import secrets; print(secrets.token_urlsafe(48))' >"$tmp" ) 2>>"$PV_LOG_FILE"; then
    rm -f "$tmp"
    return 1
  fi
  if [ ! -s "$tmp" ]; then
    rm -f "$tmp"
    return 1
  fi

  chmod 600 "$tmp" 2>/dev/null
  mv -f "$tmp" "$PV_SECRET_FILE"
  pv_log "Generated a new JWT secret key at ${PV_SECRET_FILE}"
  return 0
}

# 老版本曾把密钥放在 TRIM_PKGVAR，升级时搬过来，避免用户被强制登出。
pv_migrate_secret() {
  local legacy="${TRIM_PKGVAR}/jwt_secret"
  if [ -s "$legacy" ] && [ ! -s "$PV_SECRET_FILE" ]; then
    mkdir -p "$TRIM_PKGETC" 2>/dev/null
    if mv -f "$legacy" "$PV_SECRET_FILE" 2>>"$PV_LOG_FILE"; then
      chmod 600 "$PV_SECRET_FILE" 2>/dev/null
      pv_log "Migrated JWT secret from TRIM_PKGVAR to TRIM_PKGETC"
    fi
  fi
}

# 共享目录用的是 Windows ACL 而不是 POSIX ACL，`test -w` 在这种模型下可能给出
# 假阴性，所以这里做一次真实写入探测。
#
# 参数可选：默认探测最终的工作目录，安装时也用它探测一个还没落盘的候选目录。
pv_storage_writable() {
  local dir="${1:-$PV_STORAGE_ROOT}"
  local probe="${dir}/.pv_write_probe.$$"
  if ( : >"$probe" ) 2>/dev/null; then
    rm -f "$probe" 2>/dev/null
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# 向导值 -> PHOTOVAULT_* 环境变量
# ---------------------------------------------------------------------------
# wizard/config 收集的值以「字段名」原样成为环境变量（没有 TRIM_ 前缀）。
# 文档明确要求把向导值当作不可信输入，所以每一项都先校验再使用；非法值直接忽略，
# 让服务端回落到自己的默认值，而不是拿一个坏值去启动。
#
# 刻意不暴露 enable_place / enable_scene / enable_face：这三个开关由 Web 端
# 「探索-管理」页写入 {storage_root}/.analysis_flags.json，而该文件的优先级高于
# 环境变量。如果这里也提供入口，用户会看到两个互相打架的开关。
# ---------------------------------------------------------------------------

pv_export_wizard_settings() {
  case "${wizard_allow_registration:-}" in
    true|false) export PHOTOVAULT_ALLOW_REGISTRATION="$wizard_allow_registration" ;;
    "") : ;;
    *) pv_log "WARN: ignoring invalid wizard_allow_registration='${wizard_allow_registration}'" ;;
  esac

  case "${wizard_max_users:-}" in
    "") : ;;
    *[!0-9]*) pv_log "WARN: ignoring non-numeric wizard_max_users='${wizard_max_users}'" ;;
    *) if [ "$wizard_max_users" -ge 1 ] 2>/dev/null; then
         export PHOTOVAULT_MAX_USERS="$wizard_max_users"
       else
         pv_log "WARN: ignoring out-of-range wizard_max_users='${wizard_max_users}'"
       fi ;;
  esac

  case "${wizard_trash_retention_days:-}" in
    "") : ;;
    *[!0-9]*) pv_log "WARN: ignoring non-numeric wizard_trash_retention_days='${wizard_trash_retention_days}'" ;;
    *) if [ "$wizard_trash_retention_days" -ge 1 ] 2>/dev/null; then
         export PHOTOVAULT_TRASH_RETENTION_DAYS="$wizard_trash_retention_days"
       else
         pv_log "WARN: ignoring out-of-range wizard_trash_retention_days='${wizard_trash_retention_days}'"
       fi ;;
  esac

  case "${wizard_log_level:-}" in
    DEBUG|INFO|WARNING|ERROR) export PHOTOVAULT_LOG_LEVEL="$wizard_log_level" ;;
    "") : ;;
    *) pv_log "WARN: ignoring invalid wizard_log_level='${wizard_log_level}'" ;;
  esac
}

# ---------------------------------------------------------------------------
# 导出服务端需要的全部环境变量
# ---------------------------------------------------------------------------

pv_export_settings() {
  # 依赖查找路径。刻意不用 venv：venv 会把绝对路径写死进 pyvenv.cfg 和脚本
  # shebang，而 TRIM_APPDEST 只有安装时才确定。pip install --target 的产物是
  # 完全可重定位的。
  export PYTHONPATH="$PV_PYLIBS"
  export PYTHONDONTWRITEBYTECODE=1
  export PYTHONUNBUFFERED=1

  # 可选的高级配置文件。config.py 支持 PHOTOVAULT_CONFIG_PATH，文件不存在时会
  # 安全地回落。注意环境变量优先级高于 config.yaml，所以下面显式设置的项无法被
  # config.yaml 覆盖；留给用户调的是那些这里没有设置的项。
  export PHOTOVAULT_CONFIG_PATH="$PV_USER_CONFIG"

  # 四个存储位置各自独立设置，STORAGE_ROOT 只是未设置项的默认基准。飞牛这边刻意
  # 把它们分开：日志和模型属于应用自身的运行数据，留在 TRIM_PKGVAR，卸载时随包
  # 清理，也不会污染用户相册；数据库跟着 STORAGE_ROOT 落在 data-share。
  #
  # 刻意**不导出** PHOTOVAULT_MEDIA_ROOT：照片位置由管理员在初始化向导里经飞牛目录
  # 授权选定，服务端自己落盘和读取。这里一导出就会变成环境变量，而环境变量优先级
  # 高于服务端的运行时配置，管理员在界面上的选择就再也生效不了。
  export PHOTOVAULT_STORAGE_ROOT="$PV_STORAGE_ROOT"
  export PHOTOVAULT_LOG_DIR="$PV_LOG_DIR"
  export PHOTOVAULT_MODELS_ROOT="$PV_MODELS_DIR"

  # 让服务端在首次启动时先要求管理员选定并授权工作目录，选定前不建数据库。
  #
  # 只有飞牛需要这一步：这里的照片只能放在平台授予过 ACL 的目录里，而授权只能在
  # 已经跑起来的应用界面里完成（trim.file.sharedAccess）。Docker 和本地开发不设这个
  # 变量，仍走原来的一步式初始化。
  export PHOTOVAULT_REQUIRE_WORKDIR_SETUP=true

  # 真实监听端口只由 uvicorn 命令行决定；这个变量的唯一作用是让
  # GET /api/v1/server/info 返回正确的地址，手机端扫码配对依赖它。
  export PHOTOVAULT_SERVER_PORT="$PV_PORT"

  # PHOTOVAULT_DATABASE_URL 留空，让 config.py 填成 "{storage_root}/photovault.db"。
  # 数据库需要和照片一起留在工作目录：升级、重装都不能丢，而 TRIM_PKGVAR 不保证保留。
  #
  # 若将来要显式设置，用纯路径而不是 "sqlite+aiosqlite://..." 形式。带前缀的 URL
  # 现在已能被正确解析（服务端统一走 sqlite_path_from_url），但纯路径少一层歧义。
  unset PHOTOVAULT_DATABASE_URL

  if [ -s "$PV_SECRET_FILE" ]; then
    PHOTOVAULT_JWT_SECRET_KEY="$(cat "$PV_SECRET_FILE")"
    export PHOTOVAULT_JWT_SECRET_KEY
  fi

  # 默认关闭自助注册：服务暴露在局域网端口上，且不经过 NAS 登录态。
  export PHOTOVAULT_ALLOW_REGISTRATION=false

  # --- 飞牛开放 API（trim.file.sharedAccess）-------------------------------
  # 后端调用开放 API 时要在请求顶层带 appName，且必须与 manifest 的 appname 一致，
  # 否则授权会记到别的应用名下、查询也拿不到结果。这里显式导出运行时的真值，
  # 免得服务端去猜。
  export PHOTOVAULT_FNOS_APP_NAME="$PV_APPNAME"

  # TRIM_API_TOKEN 由系统在调用本脚本时注入，子进程本来就会继承，这里显式 export
  # 一次只是把这条依赖写明：server/app/core/fnos.py 每次调用都从环境变量现读它。
  #
  # 只在非空时导出。若无条件 export，未注入时会得到一个空值的已导出变量，
  # 诊断接口就分不清「系统没注入」和「注入了空串」。
  #
  # 文档要求不要把该 token 持久化到数据库、文件或配置里：它可能在应用重新注册、
  # 重新安装或运行环境变化后更新。所以这里只往环境变量传，不落盘。
  if [ -n "${TRIM_API_TOKEN:-}" ]; then
    export TRIM_API_TOKEN
  fi

  # 向导值放最后，允许它覆盖上面的默认值。
  pv_export_wizard_settings
}

