#!/usr/bin/env bash
#
# PhotoVault 服务端启动脚本
#
# 用法:
#   ./run.sh                 # 默认 0.0.0.0:8000，开发模式（热重载）
#   ./run.sh --prod          # 生产模式（无热重载，多 worker）
#   PHOTOVAULT_BIND_HOST=127.0.0.1 PORT=9000 ./run.sh
#
# 可通过环境变量覆盖:
#   PHOTOVAULT_BIND_HOST  监听地址      (默认 0.0.0.0)
#   PORT                  监听端口      (默认 8000)
#   PHOTOVAULT_STORAGE_ROOT  存储根目录 (默认 ./dev_data)
#
# 照片、数据库、日志、模型默认都放在存储根目录下，也可分别指定（均需绝对路径）:
#   PHOTOVAULT_MEDIA_ROOT    照片存储目录 (默认 {storage_root})
#   PHOTOVAULT_DATABASE_URL  数据库路径   (默认 {storage_root}/photovault.db)
#   PHOTOVAULT_LOG_DIR       日志目录     (默认 {storage_root}/logs)
#   PHOTOVAULT_MODELS_ROOT   模型目录     (默认 {storage_root}/.models)
#
set -euo pipefail

# 切换到脚本所在目录（服务端工程根目录）
cd "$(dirname "$0")"

# ---------------------------------------------------------------------------
# 配置（可被环境变量覆盖）
# ---------------------------------------------------------------------------
# 不使用通用的 HOST 环境变量：它常被终端或系统设为本机主机名，
# 这会使 Uvicorn 不再监听全部网卡，导致局域网 IP 无法访问。
BIND_HOST="${PHOTOVAULT_BIND_HOST:-0.0.0.0}"
PORT="${PORT:-8000}"
VENV_DIR=".venv"
export PHOTOVAULT_STORAGE_ROOT="${PHOTOVAULT_STORAGE_ROOT:-$(pwd)/dev_data}"
export PHOTOVAULT_ALLOW_REGISTRATION="${PHOTOVAULT_ALLOW_REGISTRATION:-true}"

# 解析参数
MODE="dev"
for arg in "$@"; do
    case "$arg" in
        --prod) MODE="prod" ;;
        --dev)  MODE="dev" ;;
        *) echo "未知参数: $arg"; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# 虚拟环境
# ---------------------------------------------------------------------------
if [ ! -d "$VENV_DIR" ]; then
    echo "==> 未找到虚拟环境，正在创建 $VENV_DIR ..."
    python3 -m venv "$VENV_DIR"
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    echo "==> 安装依赖 ..."
    pip install --quiet --upgrade pip
    pip install --quiet -r requirements.txt
else
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
fi

# ---------------------------------------------------------------------------
# 存储目录
# ---------------------------------------------------------------------------
mkdir -p "$PHOTOVAULT_STORAGE_ROOT"

# ---------------------------------------------------------------------------
# 局域网 IP 提示
# ---------------------------------------------------------------------------
# 优先使用默认路由所走的网卡，避免在 VPN、多网卡环境中显示不可达地址。
get_lan_ip() {
    local interface ip
    interface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
    if [ -n "$interface" ]; then
        ip="$(ipconfig getifaddr "$interface" 2>/dev/null || true)"
        if [ -n "$ip" ]; then
            printf '%s' "$ip"
            return
        fi
    fi

    for interface in en0 en1; do
        ip="$(ipconfig getifaddr "$interface" 2>/dev/null || true)"
        if [ -n "$ip" ]; then
            printf '%s' "$ip"
            return
        fi
    done
}

LAN_IP="$(get_lan_ip)"

echo "================================================================"
echo " PhotoVault 服务端启动中 ($MODE 模式)"
echo "----------------------------------------------------------------"
echo " 存储目录 : $PHOTOVAULT_STORAGE_ROOT"
echo " 监听地址 : $BIND_HOST:$PORT"
echo " 本机访问 : http://localhost:$PORT"
if [ -n "$LAN_IP" ]; then
    echo " 局域网/手机 : http://$LAN_IP:$PORT"
fi
echo " API 文档 : http://localhost:$PORT/docs"
echo "================================================================"

# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
if [ "$MODE" = "prod" ]; then
    exec uvicorn app.main:app --host "$BIND_HOST" --port "$PORT" --workers 4
else
    exec uvicorn app.main:app --host "$BIND_HOST" --port "$PORT" --reload
fi
