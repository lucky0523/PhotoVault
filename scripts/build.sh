#!/usr/bin/env bash
#
# PhotoVault 统一构建脚本
#
# 从同一份未修改的服务端/前端源码构建两种交付物：
#   docker  ->  Docker 镜像（复用 server/Dockerfile）
#   fpk     ->  飞牛 fnOS Native 应用包 (.fpk)
#
# 用法:
#   ./scripts/build.sh web [--fnos]                     仅构建前端
#   ./scripts/build.sh docker [--tag photovault:0.1.0]  构建 Docker 镜像
#   ./scripts/build.sh fpk    [--arch x86_64|aarch64]   构建 .fpk
#   ./scripts/build.sh all                              前端 + Docker + 两种架构的 fpk
#   ./scripts/build.sh verify [--arch ARCH]             对已组装的包目录重跑自检
#   ./scripts/build.sh clean                            清理 build/fnos
#
# 通用选项:
#   --version X.Y.Z    版本号（默认从 server/pyproject.toml 读取）
#   --port N           服务端口（默认 8000）
#   --with-analysis    额外打入 onnxruntime / pillow-heif（体积 +130MB 左右）
#   --skip-web         复用已有的 web/dist，不重新构建前端
#   --tag NAME         Docker 镜像 tag
#   --arch ARCH        fpk 目标架构: x86_64 | aarch64（可重复指定）
#   --fnos             仅对 web 目标有效：构建飞牛变体的前端。fpk 目标总是用飞牛
#                      变体，不需要也不接受这个选项
#
# 关于前端的两个变体：飞牛变体额外包含「设置 → 飞牛目录授权」页面（调用
# 飞牛 JS SDK 的 pickSharedFile / authorizeSharedFile）与授权回调页。这两个页面
# 由编译期开关 PHOTOVAULT_FNOS_BUILD 控制，普通变体里会被死代码消除整块删除，
# 因此只有 .fpk 会带上它们。详见 build_web()。
#
# fpk 使用飞牛官方 python312 运行时。包内不带 CPython 解释器，但仍包含
# 按 Python 3.12 ABI 交叉下载的应用依赖 wheel（FastAPI / Pillow / bcrypt 等）。
#
# 环境要求:
#   web    : node + npm
#   docker : docker
#   fpk    : node + npm + 宿主机 python3(>=3.9，仅用于交叉下载 wheel) + rsync
#            fnpack 可选；缺失时会准备好包目录并打印后续命令
#
# 关于依赖交叉编译：不需要 Docker，也不需要 Linux 机器。pip 的 --platform +
# --only-binary=:all: + --target 可以在 macOS 上直接下载 Linux 目标架构的
# manylinux wheel。PhotoVault 的核心依赖（pydantic-core / Pillow / bcrypt /
# uvloop / httptools / watchfiles）全部提供 x86_64 与 aarch64 的 manylinux
# wheel，无需任何本地编译。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT/build"
CACHE_DIR="$BUILD_DIR/cache"
TPL_DIR="$ROOT/packaging/fnos"

APP_NAME="photovault"

# ---------------------------------------------------------------------------
# 可调参数
# ---------------------------------------------------------------------------

# 飞牛官方 Python 运行时。包名必须同时用于 manifest.install_dep_apps、运行时的
# /var/apps/<name>/target/bin 路径，以及 wheel 的 ABI 版本；三者不可分开调整。
PYTHON_DEP_APP="python312"
TARGET_PY="3.12"

# `platform` 固定为 all，避免飞牛应用中心在安装前按粗粒度 CPU 类别拒绝包。
# 重要：这只影响 manifest 的安装器兼容性声明，**不**会让包内 ELF 自动变多架构。
# --arch 仍决定实际下载的 CPython 和原生 wheel；所以 x86_64 包只能在 x86_64
# 设备上运行，aarch64 包只能在 aarch64 设备上运行。
FN_PLATFORM=all

# 架构查表。刻意用 case 而不是关联数组：macOS 自带的是 bash 3.2，没有
# `declare -A`，用了会在 set -u 下报 "unbound variable"。
#
# 设置: PIP_ARCH(wheel 平台) / ELF_ARCH(file(1) 输出里的关键词)
resolve_arch() {
  case "$1" in
    x86_64)
      PIP_ARCH=x86_64;  ELF_ARCH=x86-64 ;;
    aarch64)
      PIP_ARCH=aarch64; ELF_ARCH=aarch64 ;;
    *)
      return 1 ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------

VERSION=""
PORT="8000"
WITH_ANALYSIS=0
SKIP_WEB=0
# 只影响 `web` 目标。fpk 目标无条件使用飞牛变体，不看这个值。
WEB_FNOS=0
DOCKER_TAG=""
ARCHES=()

# 打印文件头部的注释块作为帮助（从第 2 行到第一个非注释行为止）
usage() {
  awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

[ $# -gt 0 ] || { usage; exit 1; }

case "$1" in
  -h|--help|help) usage; exit 0 ;;
esac

TARGET="$1"; shift

while [ $# -gt 0 ]; do
  case "$1" in
    --version)       VERSION="${2:?--version 需要一个值}"; shift 2 ;;
    --port)          PORT="${2:?--port 需要一个值}"; shift 2 ;;
    --tag)           DOCKER_TAG="${2:?--tag 需要一个值}"; shift 2 ;;
    --arch)          ARCHES+=("${2:?--arch 需要一个值}"); shift 2 ;;
    --with-analysis) WITH_ANALYSIS=1; shift ;;
    --skip-web)      SKIP_WEB=1; shift ;;
    --fnos)          WEB_FNOS=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[1;32m[+]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1${2:+（$2）}"
}

detect_version() {
  [ -n "$VERSION" ] && return 0
  VERSION="$(sed -n 's/^version[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' "$ROOT/server/pyproject.toml" | head -1)"
  [ -n "$VERSION" ] || die "无法从 server/pyproject.toml 解析版本号，请用 --version 指定"
  return 0
}

check_arch() {
  resolve_arch "$1" || die "不支持的架构: $1（可选 x86_64 / aarch64）"
}

# ---------------------------------------------------------------------------
# 前端
# ---------------------------------------------------------------------------

# 前端有两个变体，区别只有一个编译期开关 PHOTOVAULT_FNOS_BUILD：
#
#   普通变体  ->  Docker / 自建部署
#   fnos 变体 ->  .fpk，额外包含「设置 → 飞牛目录授权」页面与授权回调页
#
# 开关在 web/vite.config.ts 里变成 define 的字面量 __FNOS_BUILD__，普通变体下
# 那两个页面所在的 if 块是死代码，Rollup 整块删除，对应的 lazy chunk 不会产出。
# 也就是说「只在编译飞牛应用时才打包进去」是构建期保证，不是运行期判断。
#
# 用法: build_web [fnos]
build_web() {
  local variant="${1:-plain}"
  local fnos_flag=0
  [ "$variant" = "fnos" ] && fnos_flag=1

  if [ "$SKIP_WEB" -eq 1 ]; then
    [ -f "$ROOT/web/dist/index.html" ] || die "--skip-web 需要已存在的 web/dist，但没找到"
    log "跳过前端构建，复用 web/dist"
    # 复用的产物是哪个变体构建的，脚本无从得知。fnos 变体下 verify_fpk 会断言
    # 飞牛页面的 chunk 存在，所以这里只提醒，让自检去兜底。
    if [ "$fnos_flag" -eq 1 ]; then
      warn "--skip-web 复用的 web/dist 必须是飞牛变体（PHOTOVAULT_FNOS_BUILD=1）构建的，否则自检会失败"
    fi
    return 0
  fi

  need_cmd node
  need_cmd npm

  if [ "$fnos_flag" -eq 1 ]; then
    log "构建前端 (web/, 飞牛变体)"
  else
    log "构建前端 (web/)"
  fi
  cd "$ROOT/web"
  if [ ! -d node_modules ]; then
    # 注意：web/package-lock.json 被 .gitignore 忽略了，全新 clone 里不存在，
    # 所以这里必须能回退到 npm install。
    if [ -f package-lock.json ]; then
      npm ci --no-audit --no-fund
    else
      warn "没有 package-lock.json（被 .gitignore 忽略），回退到 npm install"
      npm install --no-audit --no-fund
    fi
  fi
  # 必须清掉上一次的产物：两个变体的 chunk 文件名不同，增量构建会把上一次飞牛
  # 变体留下的页面 chunk 原样留在 dist 里，普通包就带上了本该被剔除的页面。
  rm -rf "$ROOT/web/dist"

  if [ "$fnos_flag" -eq 1 ]; then
    PHOTOVAULT_FNOS_BUILD=1 npm run build
  else
    # 显式置 0 而不是 unset：调用方 shell 里可能已经导出过这个变量，
    # 那样普通构建会静默变成飞牛构建。
    PHOTOVAULT_FNOS_BUILD=0 npm run build
  fi

  [ -f "$ROOT/web/dist/index.html" ] || die "前端构建失败：web/dist/index.html 不存在"

  # 自检的镜像面：飞牛变体必须有这个页面，普通变体必须没有。构建期就查掉，
  # 免得等到 verify_fpk 或者装到设备上才发现开关没生效。
  local fnos_chunk
  fnos_chunk="$(find "$ROOT/web/dist" -name 'FnosSharedAccessView-*.js' -print -quit 2>/dev/null || true)"
  if [ "$fnos_flag" -eq 1 ]; then
    [ -n "$fnos_chunk" ] || die "飞牛变体产物里找不到飞牛目录授权页面的 chunk，__FNOS_BUILD__ 可能没生效"
  else
    [ -z "$fnos_chunk" ] || die "普通变体产物里出现了飞牛页面 chunk (${fnos_chunk#"$ROOT"/})，死代码消除没生效"
  fi

  ok "前端产物: web/dist ($(du -sh "$ROOT/web/dist" | cut -f1), 变体=${variant})"
}

# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------

build_docker() {
  need_cmd docker
  detect_version
  local tag="${DOCKER_TAG:-$APP_NAME:$VERSION}"

  # Dockerfile 内部自带前端构建阶段，所以不依赖 build_web。
  log "构建 Docker 镜像: $tag (with_analysis=$WITH_ANALYSIS)"
  cd "$ROOT"
  docker build \
    -f server/Dockerfile \
    -t "$tag" \
    --build-arg WITH_ANALYSIS="$WITH_ANALYSIS" \
    .
  ok "Docker 镜像: $tag"
  echo "    运行: docker run -d -p ${PORT}:8000 -v \$PWD/data:/data/photovault \\"
  echo "            -e PHOTOVAULT_JWT_SECRET_KEY=\"\$(openssl rand -base64 36)\" $tag"
}

# ---------------------------------------------------------------------------
# fpk：依赖（交叉下载 Linux wheel）
# ---------------------------------------------------------------------------

install_deps() {
  local arch="$1" target="$2"
  check_arch "$arch"
  local pip_arch="$PIP_ARCH"

  need_cmd python3 "用于交叉下载 Linux wheel"

  local reqs=(-r "$ROOT/server/requirements.txt")
  if [ "$WITH_ANALYSIS" -eq 1 ]; then
    reqs+=(-r "$ROOT/server/requirements-analysis.txt")
  fi

  log "安装依赖 (${pip_arch}, cp${TARGET_PY//./})"
  mkdir -p "$target"
  python3 -m pip install \
    --quiet \
    --target "$target" \
    --platform "manylinux2014_${pip_arch}" \
    --platform "manylinux_2_28_${pip_arch}" \
    --python-version "$TARGET_PY" \
    --implementation cp \
    --only-binary=:all: \
    --no-compile \
    --no-warn-conflicts \
    --upgrade \
    "${reqs[@]}" \
    || die "依赖安装失败（某个包可能没有 ${pip_arch} / cp${TARGET_PY//./} 的 manylinux wheel）"

  ok "依赖: $(du -sh "$target" | cut -f1)"
}

# ---------------------------------------------------------------------------
# fpk：图标
# ---------------------------------------------------------------------------
# 飞牛要求包根目录有 ICON.PNG (64x64) 和 ICON_256.PNG (256x256)，单个文件不超过
# 1024 KB；入口图标放 app/ui/images/icon_{0}.png，{0} 会被替换成尺寸。
# ---------------------------------------------------------------------------

make_icons() {
  local pkg="$1"
  local src="$TPL_DIR/ICON.PNG"
  [ -f "$src" ] || src="$ROOT/web/public/icon.png"

  mkdir -p "$pkg/app/ui/images"

  if [ ! -f "$src" ]; then
    warn "找不到图标源文件，fnpack build 会因为缺少 ICON.PNG 失败。"
    warn "请准备 $TPL_DIR/ICON.PNG（256x256 以上）后重新构建。"
    return 0
  fi

  if ! command -v sips >/dev/null 2>&1; then
    warn "没有 sips，无法按尺寸生成图标，直接复制原图（尺寸可能不合规）"
    cp "$src" "$pkg/app/ui/images/icon_64.png"
    cp "$src" "$pkg/app/ui/images/icon_256.png"
    cp "$src" "$pkg/ICON.PNG"
    cp "$src" "$pkg/ICON_256.PNG"
    return 0
  fi

  local s
  for s in 64 256; do
    sips -s format png -z "$s" "$s" "$src" --out "$pkg/app/ui/images/icon_${s}.png" >/dev/null
  done
  cp "$pkg/app/ui/images/icon_64.png"  "$pkg/ICON.PNG"
  cp "$pkg/app/ui/images/icon_256.png" "$pkg/ICON_256.PNG"
  ok "图标: 由 $(basename "$src") 生成 64x64 / 256x256"
}

# ---------------------------------------------------------------------------
# fpk：组装
# ---------------------------------------------------------------------------

stage_fpk() {
  local arch="$1"
  check_arch "$arch"

  need_cmd rsync

  local fn_platform="$FN_PLATFORM"
  local pkg="$BUILD_DIR/fnos/$arch/$APP_NAME"

  log "组装包目录: ${pkg#"$ROOT"/}  (platform=$fn_platform, runtime=${PYTHON_DEP_APP})"
  rm -rf "$pkg"
  mkdir -p "$pkg/app" "$pkg/cmd" "$pkg/config" "$pkg/wizard"

  # --- 1. manifest ---
  # 每个包都声明飞牛官方 Python 3.12 运行时；运行时依赖会在本应用安装/启动
  # 前由应用中心准备。包内没有 python/ 解释器目录。
  #
  # manifest 是逐行 key=value 的格式，没有注释语法，所以两个和飞牛开放能力相关
  # 的声明在这里解释（改动它们前先看这段）：
  #
  #   micro_app=true
  #     调用前端 JS SDK（@trimjs/web-app）的前提。不声明时应用页面不会按微应用
  #     环境加载，宿主不注入桥，pickSharedFile / authorizeSharedFile 初始化不了，
  #     「设置 → 飞牛目录授权」页面会停在「SDK 初始化失败」。
  #
  #   disable_authorization_path=false
  #     原本是 true（本应用只用自己申请的 data-share，不需要授权路径）。接入
  #     trim.file.sharedAccess 后必须放开：应用共享授权写入的就是「应用设置 →
  #     授权路径」这份列表，关掉等于声明本应用不使用授权路径，管理员也就没有
  #     地方查看和撤销 pickSharedFile 授权出来的目录。
  #
  # 对应的 Scope 声明在 config/resource 的 api-scope 里（JSON 同样不能写注释）：
  # 只声明确实用到的 trim.file.sharedAccess，它同时覆盖两个 JS SDK 方法和
  # getSharedAccessibleFolders / delSharedAccessibleFolder 两个后端 API。
  sed \
    -e "s/@@VERSION@@/$VERSION/g" \
    -e "s/@@PORT@@/$PORT/g" \
    "$TPL_DIR/manifest" >"$pkg/manifest"

  # --- 2. 生命周期脚本（全部 9 个）---
  # install_init 需要知道本包的目标架构：manifest 的 platform 固定为 all，只有
  # 这里能在安装前判断「x86_64 的包被下到 ARM 设备上」并给出明确提示。
  local f
  for f in main install_init install_callback upgrade_init upgrade_callback \
           uninstall_init uninstall_callback config_init config_callback; do
    [ -f "$TPL_DIR/cmd/$f" ] || die "缺少生命周期脚本模板: packaging/fnos/cmd/$f"
    sed -e "s/@@BUILD_ARCH@@/$arch/g" "$TPL_DIR/cmd/$f" >"$pkg/cmd/$f"
    chmod 755 "$pkg/cmd/$f"
  done

  # --- 3. 配置与向导 ---
  cp "$TPL_DIR/config/privilege" "$TPL_DIR/config/resource" "$pkg/config/"
  for f in install upgrade uninstall config; do
    [ -f "$TPL_DIR/wizard/$f" ] && cp "$TPL_DIR/wizard/$f" "$pkg/wizard/$f"
  done

  # --- 4. UI 入口 ---
  mkdir -p "$pkg/app/ui"
  sed -e "s/@@PORT@@/$PORT/g" "$TPL_DIR/app/ui/config" >"$pkg/app/ui/config"
  make_icons "$pkg"

  # --- 5. 环境映射 ---
  mkdir -p "$pkg/app/lib"
  cp "$TPL_DIR/app/lib/env.sh" "$pkg/app/lib/env.sh"
  chmod 644 "$pkg/app/lib/env.sh"

  # --- 6. 服务端源码（原样，不做任何修改）---
  log "复制服务端源码"
  rsync -a \
    --exclude '.env' \
    --exclude '.env.*' \
    --exclude '.venv/' \
    --exclude 'dev_data/' \
    --exclude 'tests/' \
    --exclude '__pycache__/' \
    --exclude '*.py[co]' \
    --exclude '.pytest_cache/' \
    --exclude '.hypothesis/' \
    --exclude 'config.yaml' \
    --exclude 'Dockerfile' \
    --exclude '.dockerignore' \
    --exclude 'run.sh' \
    --exclude 'stop.sh' \
    --exclude 'sqlite+aiosqlite:*' \
    "$ROOT/server/" "$pkg/app/server/"

  # --- 7. 前端产物 ---
  # 必须落在 app/web/dist，即安装后的 $TRIM_APPDEST/web/dist。
  # server/app/main.py 从自身位置往上退三层再拼 "web/dist" 来定位它。
  log "复制前端产物"
  mkdir -p "$pkg/app/web"
  rsync -a "$ROOT/web/dist/" "$pkg/app/web/dist/"

  # --- 8. 应用依赖 ---
  # 不打包解释器。依赖按 python312（CPython 3.12）和目标架构下载，运行时由
  # /var/apps/python312/target/bin/python3 加载。
  install_deps "$arch" "$pkg/app/pylibs"

  verify_fpk "$pkg" "$arch"

  # 通过全局变量回传路径，而不是 echo + 命令替换：后者会把上面所有日志和自检
  # 结果一起吞进变量里。
  STAGED_PKG="$pkg"
}

# ---------------------------------------------------------------------------
# fpk：自检
# ---------------------------------------------------------------------------
# 装到飞牛上才发现问题的成本很高，所以把所有能静态验证的约束都查一遍，包括
# fnpack 自己的打包检查清单。
#
# 本文件开了 pipefail，而 `cmd | grep -q pattern` 在 grep 命中后会立刻退出，使
# 上游命令收到 SIGPIPE，整条管道被判定失败。这不仅会误报，对
# `! find ... | grep -q .` 这种取反写法还会造成假通过（真有泄漏却检不出）。
# 所以下面所有断言都不使用管道：先用命令替换取到变量，再做纯字符串/文件判断。
# ---------------------------------------------------------------------------

VERIFY_ERRS=0
VERIFY_SKIPS=0

pass()       { printf '    \033[1;32mok\033[0m   %s\n' "$1"; }
fail_check() { printf '    \033[1;31mFAIL\033[0m %s\n' "$1"; VERIFY_ERRS=$((VERIFY_ERRS + 1)); }
skip_check() { printf '    \033[1;33mskip\033[0m %s (%s)\n' "$1" "$2"; VERIFY_SKIPS=$((VERIFY_SKIPS + 1)); }

assert() {
  local desc="$1"; shift
  if "$@"; then pass "$desc"; else fail_check "$desc"; fi
}

# 判断字符串 $1 是否包含子串 $2（大小写不敏感）。
# 用 tr 而不是 grep：tr 会读完全部输入，不会触发 SIGPIPE。
contains_ci() {
  local hay needle
  hay="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  needle="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
  case "$hay" in *"$needle"*) return 0 ;; *) return 1 ;; esac
}

# 用 sips 读 PNG 尺寸，返回 "WxH"
png_size() {
  local w h
  w="$(sips -g pixelWidth "$1" 2>/dev/null | awk '/pixelWidth/{print $2}')"
  h="$(sips -g pixelHeight "$1" 2>/dev/null | awk '/pixelHeight/{print $2}')"
  printf '%sx%s' "${w:-?}" "${h:-?}"
}

verify_fpk() {
  local pkg="$1" arch="$2"
  check_arch "$arch"
  VERIFY_ERRS=0
  VERIFY_SKIPS=0

  local elf_arch="$ELF_ARCH"
  local fn_platform="$FN_PLATFORM"

  log "自检 ($arch)"

  # === fnpack 的打包检查清单 ===
  assert "[fnpack] manifest 存在"          test -f "$pkg/manifest"
  assert "[fnpack] config/privilege 存在"  test -f "$pkg/config/privilege"
  assert "[fnpack] config/resource 存在"   test -f "$pkg/config/resource"
  assert "[fnpack] ICON.PNG 存在"          test -f "$pkg/ICON.PNG"
  assert "[fnpack] ICON_256.PNG 存在"      test -f "$pkg/ICON_256.PNG"
  assert "[fnpack] app/ 目录存在"          test -d "$pkg/app"
  assert "[fnpack] cmd/ 目录存在"          test -d "$pkg/cmd"
  assert "[fnpack] wizard/ 目录存在"       test -d "$pkg/wizard"

  local uidir
  uidir="$(sed -n 's/^desktop_uidir=//p' "$pkg/manifest" 2>/dev/null || true)"
  if [ -n "$uidir" ]; then
    assert "[fnpack] app/${uidir}/ 目录存在（manifest 声明了 desktop_uidir）" \
           test -d "$pkg/app/$uidir"
  fi

  assert "privilege 是合法 JSON" python3 -c "import json,sys;json.load(open(sys.argv[1]))" "$pkg/config/privilege"
  assert "resource 是合法 JSON"  python3 -c "import json,sys;json.load(open(sys.argv[1]))" "$pkg/config/resource"
  assert "app/ui/config 是合法 JSON" python3 -c "import json,sys;json.load(open(sys.argv[1]))" "$pkg/app/ui/config"

  local wf
  for wf in "$pkg"/wizard/*; do
    [ -f "$wf" ] || continue
    assert "wizard/$(basename "$wf") 是合法 JSON" \
           python3 -c "import json,sys;json.load(open(sys.argv[1]))" "$wf"
  done

  # === 图标规格 ===
  if command -v sips >/dev/null 2>&1; then
    local sz
    sz="$(png_size "$pkg/ICON.PNG")"
    assert "ICON.PNG 是 64x64（实际 ${sz}）"        test "$sz" = "64x64"
    sz="$(png_size "$pkg/ICON_256.PNG")"
    assert "ICON_256.PNG 是 256x256（实际 ${sz}）"  test "$sz" = "256x256"
  else
    skip_check "图标尺寸" "没有 sips"
  fi
  local icon_kb
  icon_kb=$(( $(wc -c <"$pkg/ICON_256.PNG") / 1024 ))
  assert "ICON_256.PNG 不超过 1024 KB（实际 ${icon_kb} KB）" test "$icon_kb" -le 1024

  # === manifest 内容 ===
  local mf_version mf_port mf_platform ui_port mf_dep
  mf_version="$(sed -n 's/^version=//p' "$pkg/manifest" || true)"
  mf_port="$(sed -n 's/^service_port=//p' "$pkg/manifest" || true)"
  mf_platform="$(sed -n 's/^platform=//p' "$pkg/manifest" || true)"
  mf_dep="$(sed -n 's/^install_dep_apps=//p' "$pkg/manifest" || true)"
  ui_port="$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*"\([0-9]*\)".*/\1/p' "$pkg/app/ui/config" || true)"

  assert "manifest 写入了版本号 ($VERSION)" test "$mf_version" = "$VERSION"
  # 模板把 platform 固定为 all；这里断言它未被后续步骤改写。
  assert "manifest.platform 固定为 all" test "$mf_platform" = "$fn_platform"
  assert "manifest.service_port 与 ui/config.port 一致 ($PORT)" \
         test "$mf_port" = "$PORT" -a "$ui_port" = "$PORT"
  assert "没有残留未替换的 @@占位符@@" test -z "$(grep -l '@@' "$pkg/manifest" "$pkg/app/ui/config" "$pkg/app/lib/env.sh" "$pkg"/cmd/* 2>/dev/null || true)"

  assert "manifest 声明 install_dep_apps=${PYTHON_DEP_APP}" test "$mf_dep" = "$PYTHON_DEP_APP"

  # === 生命周期脚本 ===
  local sc
  for sc in main install_init install_callback upgrade_init upgrade_callback \
            uninstall_init uninstall_callback config_init config_callback; do
    assert "cmd/$sc 存在且可执行" test -x "$pkg/cmd/$sc"
    assert "cmd/$sc 语法正确"     bash -n "$pkg/cmd/$sc"
  done
  assert "app/lib/env.sh 语法正确" bash -n "$pkg/app/lib/env.sh"

  # 安装前架构自检必须认得包自己的架构，否则要么误拦真机、要么放过错架构的包。
  local ii_arch
  ii_arch="$(sed -n 's/^BUILD_ARCH="\(.*\)"$/\1/p' "$pkg/cmd/install_init" | head -1)"
  assert "cmd/install_init 记录的构建架构是 ${arch}（实际 ${ii_arch:-空}）" \
         test "$ii_arch" = "$arch"

  # === 源码与前端 ===
  assert "服务端入口存在" test -f "$pkg/app/server/app/main.py"
  assert "前端入口存在"   test -f "$pkg/app/web/dist/index.html"

  # 飞牛目录授权页面只应存在于 .fpk 里。这条断言同时能抓住两种事故：
  #   - 前端不是用飞牛变体构建的（例如 --skip-web 复用了普通变体的 web/dist）
  #   - __FNOS_BUILD__ 开关失效，条件注册的路由被整块删掉了
  # 两种情况装到设备上的表现都一样：侧边栏没有入口，且直接访问路由是空白页。
  local fnos_chunk
  fnos_chunk="$(find "$pkg/app/web/dist" -name 'FnosSharedAccessView-*.js' -print -quit 2>/dev/null || true)"
  assert "飞牛目录授权页面已打入前端产物" test -n "$fnos_chunk"

  # === 飞牛开放能力声明 ===
  # 页面能打开但调不通，绝大多数是这三项之一没配好，静态就能查。
  local manifest_micro_app manifest_auth_path
  manifest_micro_app="$(sed -n 's/^micro_app=\(.*\)$/\1/p' "$pkg/manifest" | head -1)"
  assert "manifest 声明 micro_app=true（JS SDK 的前提）" \
         test "$manifest_micro_app" = "true"

  # 应用共享授权写入的就是「应用设置 → 授权路径」这份列表，声明为 true 会让
  # 管理员无处查看和撤销授权目录。
  manifest_auth_path="$(sed -n 's/^disable_authorization_path=\(.*\)$/\1/p' "$pkg/manifest" | head -1)"
  assert "manifest 没有禁用授权路径（disable_authorization_path=false）" \
         test "$manifest_auth_path" = "false"

  # 路径走 argv 而不是拼进源码，和上面复刻 web/dist 定位算法的写法保持一致。
  if python3 - "$pkg/config/resource" <<'PY' 2>/dev/null; then
import json, sys
with open(sys.argv[1]) as f:
    scopes = json.load(f).get("api-scope") or []
sys.exit(0 if "trim.file.sharedAccess" in scopes else 1)
PY
    pass "config/resource 声明 api-scope: trim.file.sharedAccess"
  else
    fail_check "config/resource 缺少 api-scope: trim.file.sharedAccess"
  fi

  assert "env.sh 向服务端导出 PHOTOVAULT_FNOS_APP_NAME" \
         grep -q 'PHOTOVAULT_FNOS_APP_NAME' "$pkg/app/lib/env.sh"

  # 复刻 server/app/main.py 定位 web/dist 的算法：从 app/main.py 往上退三层再拼
  # "web/dist"。这是整个移植里最容易出错、又没有任何环境变量能兜底的一处，所以
  # 用同样的算法反推一遍，而不是硬编码期望路径。
  local resolved
  resolved="$(python3 - "$pkg/app/server/app/main.py" <<'PY'
import os, sys
p = os.path.abspath(sys.argv[1])
print(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(p))), "web", "dist"))
PY
)"
  assert "web/dist 落在服务端代码期望的位置 (${resolved#"$pkg/"})" test -f "$resolved/index.html"

  # === Python 运行时 ===
  # 解释器由 install_dep_apps=python312 提供，FPK 自身只带应用依赖。
  assert "FPK 不包含 python/ 解释器目录" test ! -d "$pkg/app/python"
  local dep_assign runtime_path
  dep_assign="$(sed -n 's/^PV_DEP_RUNTIME_APP="\(.*\)"$/\1/p' "$pkg/app/lib/env.sh" || true)"
  runtime_path="$(sed -n 's/^PV_PYTHON="\(.*\)"$/\1/p' "$pkg/app/lib/env.sh" || true)"
  assert "env.sh 的运行时依赖是 ${PYTHON_DEP_APP}" test "$dep_assign" = "$PYTHON_DEP_APP"
  assert "env.sh 使用飞牛 python312 的绝对路径" \
         test "$runtime_path" = "/var/apps/${PYTHON_DEP_APP}/target/bin/python3"

  # === 依赖 ===
  assert "uvicorn 已打入依赖目录" test -d "$pkg/app/pylibs/uvicorn"
  assert "fastapi 已打入依赖目录" test -d "$pkg/app/pylibs/fastapi"

  local first_so; first_so="$(find "$pkg/app/pylibs" -name '*.so' -print -quit 2>/dev/null || true)"
  if [ -z "$first_so" ]; then
    fail_check "依赖里找不到任何原生扩展 (.so)，wheel 可能选错了平台"
  elif command -v file >/dev/null 2>&1; then
    local sodesc; sodesc="$(file -b "$first_so" 2>/dev/null || true)"
    if contains_ci "$sodesc" "$elf_arch"; then
      pass "原生扩展是 Linux $arch 的 ELF ($(basename "$first_so"))"
    else
      fail_check "原生扩展架构不符，期望 ${elf_arch}，实际: ${sodesc:-无输出}"
    fi
  else
    skip_check "原生扩展架构" "没有 file 命令"
  fi

  # ABI tag 必须与目标解释器版本一致，否则 import 时会直接找不到模块
  local abi_so
  abi_so="$(find "$pkg/app/pylibs" -name "*.cpython-${TARGET_PY//./}-*.so" -print -quit 2>/dev/null || true)"
  assert "原生扩展的 ABI tag 是 cp${TARGET_PY//./}" test -n "$abi_so"

  if [ "$WITH_ANALYSIS" -eq 1 ]; then
    assert "onnxruntime 已打入（--with-analysis）" test -d "$pkg/app/pylibs/onnxruntime"
    assert "pillow_heif 已打入（--with-analysis）"  test -d "$pkg/app/pylibs/pillow_heif"
  fi

  # === 泄漏检查 ===
  # .env 里是开发机的绝对路径，而 dotenv 的优先级高于 config.yaml，混进包里会
  # 静默覆盖运行时配置，且很难排查。
  local leaked
  leaked="$(find "$pkg/app/server" -name '.env' -print -quit 2>/dev/null || true)"
  assert "没有泄漏 .env" test -z "$leaked"

  leaked="$(find "$pkg/app/server" \( -name 'dev_data' -o -name 'tests' -o -name '__pycache__' \) -print -quit 2>/dev/null || true)"
  assert "没有泄漏 dev_data / tests / __pycache__" test -z "$leaked"

  leaked="$(find "$pkg/app/server" -name 'sqlite+aiosqlite:*' -print -quit 2>/dev/null || true)"
  assert "没有泄漏 'sqlite+aiosqlite:' 垃圾目录" test -z "$leaked"

  leaked="$(find "$pkg/app/server" -name 'config.yaml' -print -quit 2>/dev/null || true)"
  assert "没有泄漏 config.yaml" test -z "$leaked"

  if [ "$VERIFY_ERRS" -gt 0 ]; then
    die "自检失败：$VERIFY_ERRS 项不通过"
  fi
  [ "$VERIFY_SKIPS" -gt 0 ] && warn "有 $VERIFY_SKIPS 项被跳过（缺少宿主机工具）"
  ok "自检通过，包体积 $(du -sh "$pkg" | cut -f1)"
  return 0
}

# ---------------------------------------------------------------------------
# fpk：产物自检
# ---------------------------------------------------------------------------
# 前面的 verify_fpk 检查的是暂存目录。这里检查 fnpack 真正吐出来的 .fpk，确认
# 打包过程没有丢文件、没有丢可执行位、也没有改坏 manifest。
#
# .fpk 的结构是一层 tar.gz，里面放 manifest / cmd / config / wizard / 图标，
# 以及把整个 app/ 二次压缩成的 app.tgz。fnpack 会重写 manifest（规范成
# "key = value" 对齐格式）并追加一个 checksum 字段。
# ---------------------------------------------------------------------------

verify_fpk_artifact() {
  local fpk="$1" arch="$2"
  check_arch "$arch"
  VERIFY_ERRS=0
  VERIFY_SKIPS=0

  log "产物自检 ($(basename "$fpk"))"

  local tmpd
  tmpd="$(mktemp -d)" || die "无法创建临时目录"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmpd'" RETURN

  assert "fpk 是 gzip 归档" test -n "$(file -b "$fpk" 2>/dev/null | grep -o gzip || true)"

  if ! tar -xzf "$fpk" -C "$tmpd" 2>/dev/null; then
    fail_check "无法解开 .fpk"
    die "产物自检失败"
  fi

  local f
  for f in manifest ICON.PNG ICON_256.PNG config/privilege config/resource app.tgz; do
    assert "归档内含 $f" test -f "$tmpd/$f"
  done
  assert "归档内含 wizard/ 目录" test -d "$tmpd/wizard"

  local sc
  for sc in main install_init install_callback upgrade_init upgrade_callback \
            uninstall_init uninstall_callback config_init config_callback; do
    assert "归档内 cmd/$sc 保留了可执行位" test -x "$tmpd/cmd/$sc"
  done

  assert "归档内 cmd/install_init 记录的构建架构是 $arch" \
         test "$(sed -n 's/^BUILD_ARCH="\(.*\)"$/\1/p' "$tmpd/cmd/install_init" | head -1)" = "$arch"

  # fnpack 重写后的 manifest 用 "key = value" 格式，取值时要容忍两侧空格
  mf_get() {
    sed -n "s/^$1[[:space:]]*=[[:space:]]*\(.*\)[[:space:]]*$/\1/p" "$tmpd/manifest" | head -1
  }
  assert "manifest.appname = $APP_NAME"        test "$(mf_get appname)" = "$APP_NAME"
  assert "manifest.version = $VERSION"         test "$(mf_get version)" = "$VERSION"
  assert "manifest.platform = $FN_PLATFORM"    test "$(mf_get platform)" = "$FN_PLATFORM"
  assert "manifest.service_port = $PORT"       test "$(mf_get service_port)" = "$PORT"
  assert "fnpack 已写入 checksum"              test -n "$(mf_get checksum)"

  # app.tgz 里的实际布局。fnpack 1.2.3 生成的条目既没有 "./" 前缀也没有目录尾
  # 斜杠，但这属于实现细节，所以先把两种写法都归一化掉，免得 fnpack 换版本后
  # 这里静默失效。
  local list="$tmpd/app.list"
  if ! tar -tzf "$tmpd/app.tgz" 2>/dev/null | sed -e 's|^\./||' -e 's|/$||' >"$list"; then
    fail_check "无法列出 app.tgz"
    die "产物自检失败"
  fi

  has_entry() { grep -Fxq "$1" "$list"; }

  assert "app.tgz 内含 server/app/main.py"  has_entry "server/app/main.py"
  assert "app.tgz 内含 web/dist/index.html" has_entry "web/dist/index.html"
  assert "app.tgz 内含 lib/env.sh"          has_entry "lib/env.sh"
  assert "app.tgz 内含 ui/config"           has_entry "ui/config"
  assert "app.tgz 内含 pylibs/uvicorn"      has_entry "pylibs/uvicorn"

  assert "app.tgz 不含自带 Python 解释器" \
         test -z "$(grep -m1 '^python/' "$list" || true)"

  # 服务端定位前端的算法在归档里也必须成立：server/app/main.py 往上退三层是 app
  # 根，所以 web/dist 必须与 server/ 同级。
  assert "归档内 web/dist 与 server/ 同级（服务端定位前端的前提）" \
         has_entry "web/dist"

  if [ "$VERIFY_ERRS" -gt 0 ]; then
    die "产物自检失败：$VERIFY_ERRS 项不通过"
  fi
  ok "产物自检通过"
  return 0
}

# ---------------------------------------------------------------------------
# fpk：打包
# ---------------------------------------------------------------------------

# 只构建部分架构时，其它架构目录里可能还躺着上一次构建的 .fpk。它的文件名同样带
# 版本号和架构，和刚出炉的产物看起来一样正规，很容易被一起发布出去——而旧包缺少
# 本次才加进来的东西。这个坑真实发生过一次：只重建了 x86_64，aarch64 还是几十
# 分钟前的旧包，装上去没有「飞牛目录授权」页面，manifest 里也没有 micro_app。
#
# 只提醒不删除：误删别人刚构建好的产物比留着旧包更糟。
warn_stale_fpks() {
  local built=" $* "
  local other stale
  for other in x86_64 aarch64; do
    case "$built" in *" $other "*) continue ;; esac
    stale="$BUILD_DIR/fnos/$other/${APP_NAME}-${VERSION}-${other}.fpk"
    [ -f "$stale" ] || continue
    warn "本次没有构建 ${other}：${stale#"$ROOT"/} 是上一次构建（$(date -r "$stale" '+%m-%d %H:%M')）留下的，内容可能已过期，别直接发布"
    echo "    重建: ./scripts/build.sh fpk --arch ${other}（或用 ./scripts/build.sh all 一次出齐两种架构）"
  done
}

build_fpk() {
  detect_version
  # fpk 一定用飞牛变体：飞牛目录授权页面只在这里才应该出现。
  build_web fnos

  local archlist=("${ARCHES[@]:-}")
  [ -n "${archlist[0]:-}" ] || archlist=(x86_64)

  warn_stale_fpks "${archlist[@]}"

  local arch pkg fpk
  for arch in "${archlist[@]}"; do
    STAGED_PKG=""
    stage_fpk "$arch"
    pkg="$STAGED_PKG"

    if ! command -v fnpack >/dev/null 2>&1; then
      warn "未安装 fnpack，包目录已就绪但没有生成 .fpk"
      echo "    在装有 fnpack 的环境执行: fnpack build --directory ${pkg#"$ROOT"/}"
      continue
    fi

    log "fnpack build ($arch)"

    local outdir; outdir="$(dirname "$pkg")"
    # fnpack 固定输出 <appname>.fpk。每个架构使用独立输出目录，清除该固定名字
    # 可避免把上一次同架构构建留下的原始产物误判为本次成功。
    local raw="$outdir/$APP_NAME.fpk"
    rm -f "$raw"

    # fnpack 1.2.3 在校验失败时也返回退出码 0，只在 stdout 打印 "Packing failed"，
    # 所以必须从输出里识别失败，再确认文件真的生成了。
    local fnout
    fnout="$( cd "$outdir" && fnpack build --directory "$pkg" 2>&1 )" || true
    printf '%s\n' "$fnout" | sed 's/^/    /'
    if printf '%s' "$fnout" | grep -qi 'fail'; then
      die "fnpack 校验未通过 ($arch)"
    fi
    [ -f "$raw" ] || die "fnpack 返回成功但没生成 $raw ($arch)"

    # 文件名带上真实的目标架构。manifest 的 platform 固定为 all，两种架构的包
    # 曾经同名（…-all.fpk），只靠上级目录区分，很容易把 x86_64 的包发给 ARM 用户。
    fpk="$outdir/${APP_NAME}-${VERSION}-${arch}.fpk"
    mv -f "$raw" "$fpk"

    verify_fpk_artifact "$fpk" "$arch"
    ok "fpk: ${fpk#"$ROOT"/} ($(du -h "$fpk" | cut -f1))"
  done
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

case "$TARGET" in
  web)
    if [ "$WEB_FNOS" -eq 1 ]; then
      build_web fnos
    else
      build_web
    fi
    ;;
  docker)
    build_docker
    ;;
  fpk)
    build_fpk
    ;;
  all)
    detect_version
    # 这里刻意不预先构建一次前端再让 fpk 复用（原先是 build_web + SKIP_WEB=1）：
    #   - docker 用不上 web/dist，server/Dockerfile 自带前端构建阶段；
    #   - fpk 需要的是飞牛变体，和普通变体不能共用同一份 web/dist。
    # 所以直接交给 build_fpk 去构建飞牛变体，避免把普通变体的产物打进 .fpk。
    if command -v docker >/dev/null 2>&1; then
      build_docker
    else
      warn "未安装 docker，跳过镜像构建"
    fi
    ARCHES=(x86_64 aarch64)
    build_fpk
    ;;
  verify)
    detect_version
    archlist=("${ARCHES[@]:-}")
    [ -n "${archlist[0]:-}" ] || archlist=(x86_64)
    for arch in "${archlist[@]}"; do
      pkg="${VERIFY_DIR:-$BUILD_DIR/fnos/$arch/$APP_NAME}"
      [ -d "$pkg" ] || die "包目录不存在: $pkg"
      verify_fpk "$pkg" "$arch"
    done
    ;;
  clean)
    log "清理 build/fnos（保留下载缓存）"
    rm -rf "$BUILD_DIR/fnos"
    ok "已清理"
    ;;
  *)
    echo "未知目标: $TARGET" >&2
    usage
    exit 1
    ;;
esac
