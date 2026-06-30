#!/usr/bin/env bash
#
# PhotoVault 服务端启动脚本
#
# 用法:
#   ./run.sh                 # 默认 0.0.0.0:8000，开发模式（热重载）
#   ./run.sh --prod          # 生产模式（无热重载，多 worker）
#   HOST=127.0.0.1 PORT=9000 ./run.sh
#
# 可通过环境变量覆盖:
#   HOST                  监听地址      (默认 0.0.0.0)
#   PORT                  监听端口      (默认 8000)
#   PHOTOVAULT_STORAGE_ROOT  存储根目录 (默认 ./dev_data)
#
set -euo pipefail

# 切换到脚本所在目录（服务端工程根目录）
cd "$(dirname "$0")"

# ---------------------------------------------------------------------------
# 配置（可被环境变量覆盖）
# ---------------------------------------------------------------------------
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"
VENV_DIR=".venv"
export PHOTOVAULT_STORAGE_ROOT="${PHOTOVAULT_STORAGE_ROOT:-$(pwd)/dev_data}"

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
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo '')"

echo "================================================================"
echo " PhotoVault 服务端启动中 ($MODE 模式)"
echo "----------------------------------------------------------------"
echo " 存储目录 : $PHOTOVAULT_STORAGE_ROOT"
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
    exec uvicorn app.main:app --host "$HOST" --port "$PORT" --workers 4
else
    exec uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
fi
