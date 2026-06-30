#!/usr/bin/env bash
#
# PhotoVault 服务端停止脚本
#
# 用法:
#   ./stop.sh            # 停止默认端口 8000 上的服务
#   PORT=9000 ./stop.sh  # 停止指定端口上的服务
#   ./stop.sh -9         # 强制停止 (SIGKILL)
#
set -euo pipefail

PORT="${PORT:-8000}"
SIGNAL="TERM"

for arg in "$@"; do
    case "$arg" in
        -9|--force) SIGNAL="KILL" ;;
        *) echo "未知参数: $arg"; exit 1 ;;
    esac
done

# 查找监听该端口的进程
PIDS="$(lsof -ti ":$PORT" 2>/dev/null || true)"

if [ -z "$PIDS" ]; then
    echo "端口 $PORT 上没有运行中的服务。"
    # 兜底：按 uvicorn 进程名再查一次
    UVICORN_PIDS="$(pgrep -f 'uvicorn app.main:app' 2>/dev/null || true)"
    if [ -n "$UVICORN_PIDS" ]; then
        echo "发现 uvicorn 进程，正在停止 ..."
        # shellcheck disable=SC2086
        kill "-$SIGNAL" $UVICORN_PIDS
        echo "已停止 uvicorn 进程: $UVICORN_PIDS"
    fi
    exit 0
fi

echo "正在停止端口 $PORT 上的服务 (PID: $PIDS, 信号: SIG$SIGNAL) ..."
# shellcheck disable=SC2086
kill "-$SIGNAL" $PIDS

# 等待最多 5 秒确认退出
for _ in $(seq 1 5); do
    if [ -z "$(lsof -ti ":$PORT" 2>/dev/null || true)" ]; then
        echo "服务已停止。"
        exit 0
    fi
    sleep 1
done

echo "服务仍在运行，可使用 './stop.sh -9' 强制停止。"
exit 1
