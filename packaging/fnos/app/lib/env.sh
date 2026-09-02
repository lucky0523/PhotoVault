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
# 必须落盘：wizard/install 的取值只在安装期作为环境变量存在，之后每次 start 都拿
# 不到（官方文档只承诺 wizard/config 的值会"继续作为环境变量提供给应用使用"）。
# 所以 install_callback 把它写到这里，后续每次 source 本文件时再读回来。
#
# 放 TRIM_PKGETC 而不是 TRIM_PKGVAR：和 jwt_secret 同理，升级要保留，否则升级后
# 工作目录会悄悄跳回默认共享目录，用户的照片看起来就"全没了"。
PV_WORKDIR_FILE="${TRIM_PKGETC}/workdir"

# data-share 可能声明多个目录（冒号分隔），本应用只声明一个，取第一个。
# 这是工作目录的默认值：用户在安装向导里留空时就用它。
PV_DATA_SHARE="${TRIM_DATA_SHARE_PATHS%%:*}"

# 实际生效的工作目录（照片 + 数据库）。由本文件末尾的 pv_resolve_storage_root
# 赋值——函数必须先定义，所以不能在这里直接算。
PV_STORAGE_ROOT=""

# 工作目录是否为用户自定义。影响两件事：要不要替用户创建目录（共享目录由系统创建
# 并授 ACL，自定义目录得我们自己建），以及错误提示的措辞。
PV_STORAGE_IS_CUSTOM=0

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
# 工作目录（wizard/install）
# ---------------------------------------------------------------------------
# 路径类向导值的破坏力比其它字段大得多：一个手误就可能把几十 GB 照片写进系统盘，
# 或者写到 /etc 这类目录里。文档也明确要求把向导值当不可信输入、使用前再校验。
# 所以这里逐条检查，任何一条不过就带着原因拒绝安装，而不是"尽力猜一个能用的值"。
# ---------------------------------------------------------------------------

# 明确禁止的系统目录。用户的工作目录不可能是这些位置，误填的后果却很严重。
pv_workdir_is_system_path() {
  case "$1" in
    /|/bin|/boot|/dev|/etc|/home|/lib|/lib32|/lib64|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var)
      return 0 ;;
    /bin/*|/boot/*|/dev/*|/etc/*|/lib/*|/lib32/*|/lib64/*|/proc/*|/root/*|/run/*|/sbin/*|/sys/*|/usr/*)
      return 0 ;;
    # 飞牛把应用装在 /var/apps 下，那是只读的程序目录，不是数据目录。
    /var/apps|/var/apps/*)
      return 0 ;;
  esac
  return 1
}

# 校验一个候选工作目录。合法则返回 0 且不输出；否则把原因写到 stdout 并返回 1。
pv_workdir_reject_reason() {
  local dir="$1"

  case "$dir" in
    /*) : ;;
    *) echo "必须是以 / 开头的绝对路径"; return 1 ;;
  esac

  # 控制字符（换行、回车、制表符）会破坏单行落盘的格式，读回来就变成另一个路径。
  case "$dir" in
    *[[:cntrl:]]*) echo "不能包含控制字符"; return 1 ;;
  esac

  case "$dir" in
    */../*|*/..) echo "不能包含 .. 路径段，请填写展开后的完整路径" ; return 1 ;;
  esac

  if pv_workdir_is_system_path "$dir"; then
    echo "'${dir}' 是系统目录，不能用作工作目录"
    return 1
  fi

  # 上层目录必须已存在。这一条专门拦"存储空间没挂载"和路径拼错：否则 mkdir -p 会
  # 在系统盘上凭空建出整条路径，照片越备份越多，直到把系统盘写满。
  local parent
  parent="$(dirname "$dir")"
  if [ ! -d "$parent" ]; then
    echo "上层目录不存在: ${parent}（请确认存储空间已挂载、路径拼写正确）"
    return 1
  fi

  return 0
}

# 解析实际生效的工作目录：优先安装时落盘的自定义路径，否则用默认共享目录。
pv_resolve_storage_root() {
  PV_STORAGE_ROOT="$PV_DATA_SHARE"
  PV_STORAGE_IS_CUSTOM=0

  [ -s "$PV_WORKDIR_FILE" ] || return 0

  local saved
  saved="$(head -n 1 "$PV_WORKDIR_FILE" 2>/dev/null | tr -d '\r')"
  [ -n "$saved" ] || return 0

  # 落盘前已经完整校验过，这里只做最低限度的形状确认，防止文件被手工改坏之后把
  # 服务指到一个荒唐的位置。不合理就回落到共享目录，并留下日志。
  if [ "${saved#/}" = "$saved" ] || pv_workdir_is_system_path "$saved"; then
    pv_log "WARN: ignoring invalid work dir '${saved}' from ${PV_WORKDIR_FILE}, using ${PV_DATA_SHARE}"
    return 0
  fi

  PV_STORAGE_ROOT="$saved"
  PV_STORAGE_IS_CUSTOM=1
  return 0
}

# 把安装向导选定的工作目录校验、创建并落盘。只在 install_callback 里调用一次。
# 向导值留空表示使用默认共享目录，此时不写文件。
pv_persist_workdir() {
  local dir="${wizard_work_dir:-}"

  # 去掉首尾空白和结尾多余的斜杠：从文件管理器复制路径时很容易带上。
  dir="$(printf '%s' "$dir" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  while [ "${#dir}" -gt 1 ] && [ "${dir%/}" != "$dir" ]; do
    dir="${dir%/}"
  done

  if [ -z "$dir" ]; then
    # 重装场景：卸载不一定清掉 TRIM_PKGETC，上一次安装可能留下了自定义路径。用户
    # 这次留空表示要用默认共享目录，必须删掉旧记录，否则会被悄悄"继承"到一个他并
    # 没有选择的目录。
    if [ -e "$PV_WORKDIR_FILE" ]; then
      rm -f "$PV_WORKDIR_FILE" 2>/dev/null
      pv_log "Cleared previously saved work dir (wizard left blank)"
    fi
    pv_resolve_storage_root
    pv_log "No custom work dir given, using data share ${PV_STORAGE_ROOT}"
    return 0
  fi

  local reason
  if ! reason="$(pv_workdir_reject_reason "$dir")"; then
    pv_fail "工作目录设置无效：${reason}"
    return 1
  fi

  if ! mkdir -p "$dir" 2>>"$PV_LOG_FILE"; then
    pv_fail "无法创建工作目录: ${dir}（运行用户: ${TRIM_USERNAME:-?}）"
    return 1
  fi

  if ! pv_storage_writable "$dir"; then
    pv_fail "工作目录不可写: ${dir}（运行用户: ${TRIM_USERNAME:-?}）"
    return 1
  fi

  mkdir -p "$TRIM_PKGETC" 2>/dev/null
  local tmp="${PV_WORKDIR_FILE}.tmp.$$"
  if ! printf '%s\n' "$dir" >"$tmp" 2>>"$PV_LOG_FILE"; then
    rm -f "$tmp"
    pv_fail "无法记录工作目录到 ${PV_WORKDIR_FILE}"
    return 1
  fi
  mv -f "$tmp" "$PV_WORKDIR_FILE"

  pv_resolve_storage_root
  pv_log "Work dir set to ${PV_STORAGE_ROOT} (custom=${PV_STORAGE_IS_CUSTOM})"
  return 0
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
  # 把它们分开：照片和数据库放用户可见、容量充足的工作目录（默认是共享目录
  # data-share，也可在安装向导里自定义），而日志和模型属于应用自身的运行数据，
  # 留在 TRIM_PKGVAR，卸载时随包清理，也不会污染用户相册。
  export PHOTOVAULT_STORAGE_ROOT="$PV_STORAGE_ROOT"
  export PHOTOVAULT_MEDIA_ROOT="$PV_STORAGE_ROOT"
  export PHOTOVAULT_LOG_DIR="$PV_LOG_DIR"
  export PHOTOVAULT_MODELS_ROOT="$PV_MODELS_DIR"

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

# ---------------------------------------------------------------------------
# source 时立即解析工作目录
# ---------------------------------------------------------------------------
# 放在文件末尾：pv_resolve_storage_root 依赖上面定义的函数，而 PV_STORAGE_ROOT 又要
# 在本文件返回后就可用（cmd/main 的 preflight、install_callback 的校验都直接读它）。
#
# install_callback 会在落盘之后再调用一次 pv_persist_workdir 来刷新这个值——安装那
# 一趟 source 时文件还不存在，这里拿到的只能是默认共享目录。
pv_resolve_storage_root
