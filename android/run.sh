#!/usr/bin/env bash
#
# PhotoVault Android 一键 编译 / 安装 / 运行 脚本
#
# 用法:
#   ./run.sh                # 编译 debug 包，安装到设备并启动
#   ./run.sh -c             # 先 clean 再编译安装运行
#   ./run.sh -r             # 使用 release 变体
#   ./run.sh -b             # 只编译，不安装/运行
#   ./run.sh --no-run       # 编译并安装，但不启动
#   ./run.sh -t             # 安装前先跑单元测试
#   ./run.sh -s <serial>    # 指定目标设备/模拟器
#   ./run.sh -l             # 安装启动后跟随查看应用日志(logcat)
#   ./run.sh -h             # 显示帮助
#
set -euo pipefail

# 始终以脚本所在目录(android/)为工作目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_ID="com.huoyi.photovault"
LAUNCH_ACTIVITY="$APP_ID/.MainActivity"

# ---- 默认参数 ----
DO_CLEAN=false
VARIANT="debug"      # debug | release
BUILD_ONLY=false
DO_RUN=true
RUN_TESTS=false
FOLLOW_LOG=false
DEVICE_SERIAL=""

# ---- 颜色输出 ----
if [[ -t 1 ]]; then
  BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
else
  BLUE=''; GREEN=''; YELLOW=''; RED=''; NC=''
fi
info()  { echo -e "${BLUE}▶${NC} $*"; }
ok()    { echo -e "${GREEN}✔${NC} $*"; }
warn()  { echo -e "${YELLOW}⚠${NC} $*"; }
fail()  { echo -e "${RED}✖${NC} $*" >&2; exit 1; }

usage() {
  # 打印文件开头(去掉 shebang 后)连续的注释行作为帮助文本
  awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
  exit 0
}

# ---- 解析参数 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--clean)     DO_CLEAN=true; shift ;;
    -r|--release)   VARIANT="release"; shift ;;
    -b|--build-only) BUILD_ONLY=true; shift ;;
    --no-run)       DO_RUN=false; shift ;;
    -t|--test)      RUN_TESTS=true; shift ;;
    -l|--log)       FOLLOW_LOG=true; shift ;;
    -s|--serial)    DEVICE_SERIAL="${2:-}"; [[ -z "$DEVICE_SERIAL" ]] && fail "-s 需要提供设备序列号"; shift 2 ;;
    -h|--help)      usage ;;
    *)              fail "未知参数: $1 (使用 -h 查看帮助)" ;;
  esac
done

# 首字母大写用于拼接 Gradle 任务名 (Debug / Release)
VARIANT_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"

# ---- 定位 JDK ----
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  for cand in \
    "$(/usr/libexec/java_home 2>/dev/null || true)" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/opt/homebrew/opt/openjdk@17" \
    "/usr/lib/jvm/java-17-openjdk"; do
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then
      export JAVA_HOME="$cand"
      break
    fi
  done
fi
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || fail "未找到可用的 JDK，请设置 JAVA_HOME (需要 JDK 17)。"
info "JAVA_HOME = $JAVA_HOME"

# ---- 定位 adb ----
ADB=""
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]]; then
      ADB="$sdk/platform-tools/adb"
      break
    fi
  done
fi

adb_cmd() {
  "$ADB" -s "$DEVICE_SERIAL" "$@"
}

GRADLEW="./gradlew"
[[ -x "$GRADLEW" ]] || GRADLEW="sh ./gradlew"

# ---- clean ----
if [[ "$DO_CLEAN" == true ]]; then
  info "clean ..."
  $GRADLEW clean
fi

# ---- 单元测试 ----
if [[ "$RUN_TESTS" == true ]]; then
  info "运行单元测试 (test${VARIANT_CAP}UnitTest) ..."
  $GRADLEW ":app:test${VARIANT_CAP}UnitTest"
  ok "单元测试通过"
fi

# ---- 编译 ----
info "编译 assemble${VARIANT_CAP} ..."
$GRADLEW ":app:assemble${VARIANT_CAP}"
ok "编译完成"

APK_DIR="app/build/outputs/apk/${VARIANT}"
if [[ "$BUILD_ONLY" == true ]]; then
  ok "APK 输出目录: $APK_DIR"
  ls -1 "$APK_DIR"/*.apk 2>/dev/null || true
  exit 0
fi

# ---- 检查部署设备（不影响已经完成的编译）----
if [[ -z "$ADB" ]]; then
  warn "未找到 adb，跳过安装和启动。APK 输出目录: $APK_DIR"
  exit 0
fi

ADB_DEVICES="$("$ADB" devices 2>/dev/null || true)"
DEVICE_COUNT="$(awk '$2 == "device" { count++ } END { print count + 0 }' <<< "$ADB_DEVICES")"

if [[ -n "$DEVICE_SERIAL" ]]; then
  if ! awk -v serial="$DEVICE_SERIAL" \
      '$1 == serial && $2 == "device" { found=1 } END { exit !found }' \
      <<< "$ADB_DEVICES"; then
    warn "指定设备 $DEVICE_SERIAL 不可用，跳过安装和启动。APK 输出目录: $APK_DIR"
    exit 0
  fi
else
  DEVICE_SERIAL="$(awk '$2 == "device" { print $1; exit }' <<< "$ADB_DEVICES")"
  if [[ -z "$DEVICE_SERIAL" ]]; then
    warn "没有检测到可用的 ADB 设备，跳过安装和启动。APK 输出目录: $APK_DIR"
    exit 0
  fi
  if [[ "$DEVICE_COUNT" -gt 1 ]]; then
    warn "检测到多台设备，将使用 $DEVICE_SERIAL。可用 -s <serial> 指定。"
  fi
fi

info "使用 ADB 设备: $DEVICE_SERIAL"

# ---- 安装 ----
info "安装 install${VARIANT_CAP} 到设备 ..."
if [[ -n "$DEVICE_SERIAL" ]]; then
  ANDROID_SERIAL="$DEVICE_SERIAL" $GRADLEW ":app:install${VARIANT_CAP}"
else
  $GRADLEW ":app:install${VARIANT_CAP}"
fi
ok "安装完成"

# ---- 启动 ----
if [[ "$DO_RUN" == true ]]; then
  info "启动 $LAUNCH_ACTIVITY ..."
  adb_cmd shell am start -n "$LAUNCH_ACTIVITY" >/dev/null
  ok "已启动 PhotoVault"

  if [[ "$FOLLOW_LOG" == true ]]; then
    info "跟随日志 (Ctrl+C 退出) ..."
    # 清空后按应用进程过滤
    adb_cmd logcat -c || true
    PID="$(adb_cmd shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$PID" ]]; then
      adb_cmd logcat --pid="$PID"
    else
      adb_cmd logcat | grep --line-buffered -i "photovault"
    fi
  fi
fi
