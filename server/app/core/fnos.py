"""飞牛（fnOS）开放 API 客户端。

飞牛的后端开放能力不是普通的 HTTP 服务，调用方式有三处硬约束（见
https://developer.fnnas.com/api/calling/ ）：

1. 传输层走 Unix Socket ``/var/run/trim_open_gateway_apiscope.socket``，
   而不是 TCP。所以不能用 requests / httpx 的普通 URL 调法。
2. 所有能力共用一个入口 ``POST /api/v1/trimapp``，靠请求体里的 ``req``
   字段区分要调哪个能力；应用名走顶层 ``appName``。
3. 认证用 ``Authorization: Bearer <token>``，token 由系统在调用应用脚本
   （cmd/main 等）时写进环境变量 ``TRIM_API_TOKEN``，由服务进程继承。

为什么不引 httpx：requirements.txt 里没有它，而这里需要的只是「往 Unix Socket
写一个 HTTP/1.1 请求再把响应读回来」，asyncio 标准库足够。为一个诊断接口新增
一个跨架构的依赖（fpk 需要为 x86_64 / aarch64 各下一份 wheel）不划算。

为什么 token 不进 Settings：``get_settings()`` 带 lru_cache，值会在进程生命周期
内固定。而文档明确说 TRIM_API_TOKEN 可能在应用重新注册、重新安装或运行环境变化
后更新，要求每次调用都从当前进程环境变量读取，不要持久化。所以这里每次调用都读
``os.environ``。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from typing import Any

logger = logging.getLogger("photovault.core.fnos")

# 开放网关的 Unix Socket。允许用环境变量覆盖，方便在非飞牛机器上用一个假的
# socket 做联调；生产路径由飞牛固定。
DEFAULT_GATEWAY_SOCKET = "/var/run/trim_open_gateway_apiscope.socket"

#: 所有后端能力共用的入口路径。
GATEWAY_PATH = "/api/v1/trimapp"

#: 单次调用的超时。网关是本机 Unix Socket，正常应在毫秒级返回；给到 10s 只是
#: 为了不让偶发卡顿变成失败，同时保证不会挂死请求线程。
GATEWAY_TIMEOUT_S = 10.0

#: 响应体大小上限。这些接口返回的都是短 JSON，设上限避免异常响应把内存吃满。
MAX_RESPONSE_BYTES = 1 << 20  # 1 MiB


class FnosUnavailableError(RuntimeError):
    """当前进程不具备调用开放 API 的条件（不在飞牛环境、socket 或 token 缺失）。"""


class FnosGatewayError(RuntimeError):
    """网关返回了传输层或业务层错误。

    Attributes:
        code: 业务码；传输层失败时为 None。
        msg: 错误消息。
    """

    def __init__(self, msg: str, code: int | None = None) -> None:
        super().__init__(msg)
        self.code = code
        self.msg = msg


def get_socket_path() -> str:
    """返回开放网关 Unix Socket 路径。"""
    return os.environ.get("PHOTOVAULT_FNOS_GATEWAY_SOCKET") or DEFAULT_GATEWAY_SOCKET


def get_app_name() -> str:
    """返回调用开放 API 时使用的 appName。

    必须与应用包 manifest 的 ``appname`` 一致，否则授权会记到别的应用名下，
    查询也拿不到结果。优先用 packaging/fnos/app/lib/env.sh 导出的
    ``PHOTOVAULT_FNOS_APP_NAME``（其值来自运行时的 ``TRIM_APPNAME``）。
    """
    return (
        os.environ.get("PHOTOVAULT_FNOS_APP_NAME")
        or os.environ.get("TRIM_APPNAME")
        or "photovault"
    )


def get_api_token() -> str:
    """每次调用都现读 TRIM_API_TOKEN，不缓存（见模块 docstring）。"""
    return os.environ.get("TRIM_API_TOKEN", "")


def describe_environment() -> dict[str, Any]:
    """探测能否调用开放 API，返回可直接给前端展示的诊断信息。

    只做本地检查（socket 文件是否存在、token 有没有），不会真的发请求，所以在
    任何环境下调用都是安全的。
    """
    socket_path = get_socket_path()
    socket_exists = os.path.exists(socket_path)
    token_present = bool(get_api_token())

    reasons: list[str] = []
    if not socket_exists:
        reasons.append(
            f"找不到开放网关 Unix Socket（{socket_path}），"
            "当前进程可能不在飞牛应用运行时中"
        )
    if not token_present:
        reasons.append(
            "环境变量 TRIM_API_TOKEN 缺失，该 token 由系统在调用 cmd/main 时注入，"
            "服务进程需要继承它"
        )

    return {
        "available": socket_exists and token_present,
        "reason": "；".join(reasons),
        "app_name": get_app_name(),
        "socket_path": socket_path,
        "socket_exists": socket_exists,
        "token_present": token_present,
    }


async def call_gateway(req: str, data: dict[str, Any] | None = None) -> dict[str, Any]:
    """调用一个后端开放能力，返回响应里的 ``data`` 字段。

    Args:
        req: 能力标识，例如 ``trim.file.getSharedAccessibleFolders``。
        data: 该能力的参数；``appName`` 不要放进来，它是顶层字段。

    Returns:
        响应 ``data`` 字段的内容（对象）。

    Raises:
        FnosUnavailableError: socket 或 token 缺失。
        FnosGatewayError: 连接失败、响应无法解析，或业务码非 0。
    """
    env = describe_environment()
    if not env["available"]:
        raise FnosUnavailableError(env["reason"])

    payload = {
        # 网关会把 reqId 原样回传，用来把日志里的请求和响应对上。
        "reqId": f"{int(time.time() * 1000)}-{req}",
        "req": req,
        "appName": env["app_name"],
        "data": data or {},
    }

    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    response = await _post_json(env["socket_path"], body)

    code = response.get("code")
    if code != 0:
        raise FnosGatewayError(
            response.get("msg") or f"开放 API 返回业务码 {code}",
            code=code if isinstance(code, int) else None,
        )

    result = response.get("data")
    return result if isinstance(result, dict) else {}


async def _post_json(socket_path: str, body: bytes) -> dict[str, Any]:
    """往 Unix Socket 上发一个 HTTP/1.1 POST，把 JSON 响应解析出来。"""
    try:
        return await asyncio.wait_for(
            _post_json_inner(socket_path, body), timeout=GATEWAY_TIMEOUT_S
        )
    except asyncio.TimeoutError as e:
        raise FnosGatewayError(f"调用开放 API 超时（>{GATEWAY_TIMEOUT_S}s）") from e
    except (OSError, ConnectionError) as e:
        raise FnosGatewayError(f"连接开放网关失败：{e}") from e


async def _post_json_inner(socket_path: str, body: bytes) -> dict[str, Any]:
    reader, writer = await asyncio.open_unix_connection(socket_path)
    try:
        # Host 是 HTTP/1.1 的必填头，Unix Socket 上没有真实主机名，按文档的
        # curl 示例用 localhost。Connection: close 让服务端发完就关，省掉
        # keep-alive 状态管理——这些调用都是低频的。
        request = (
            f"POST {GATEWAY_PATH} HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "Content-Type: application/json\r\n"
            f"Authorization: Bearer {get_api_token()}\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "\r\n"
        ).encode("utf-8") + body

        writer.write(request)
        await writer.drain()

        head = await reader.readuntil(b"\r\n\r\n")
        status_code, headers = _parse_head(head)
        payload = await _read_body(reader, headers)
    except asyncio.IncompleteReadError as e:
        raise FnosGatewayError("开放网关返回了不完整的响应") from e
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except (OSError, ConnectionError):
            # 对端已经关掉连接时 wait_closed 可能抛错，这不影响已读到的响应。
            pass

    if status_code != 200:
        # 401/403 一般是 token 失效或 api-scope 没声明，把原文带出来最有用。
        raise FnosGatewayError(
            f"开放网关返回 HTTP {status_code}：{payload.decode('utf-8', 'replace')[:500]}"
        )

    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as e:
        raise FnosGatewayError(
            f"开放网关响应不是合法 JSON：{payload.decode('utf-8', 'replace')[:200]}"
        ) from e

    if not isinstance(parsed, dict):
        raise FnosGatewayError("开放网关响应不是 JSON 对象")

    return parsed


def _parse_head(head: bytes) -> tuple[int, dict[str, str]]:
    """解析状态行与响应头。"""
    lines = head.decode("iso-8859-1").split("\r\n")

    parts = lines[0].split(" ", 2)
    if len(parts) < 2 or not parts[1].isdigit():
        raise FnosGatewayError(f"无法解析开放网关的状态行：{lines[0]!r}")
    status_code = int(parts[1])

    headers: dict[str, str] = {}
    for line in lines[1:]:
        if not line:
            continue
        name, _, value = line.partition(":")
        headers[name.strip().lower()] = value.strip()

    return status_code, headers


async def _read_body(reader: asyncio.StreamReader, headers: dict[str, str]) -> bytes:
    """按 Content-Length 读响应体；没有该头时读到 EOF（我们要求了 Connection: close）。

    刻意不支持 chunked：网关返回的是短 JSON，且我们声明了 Connection: close。
    真遇到 chunked 会在上层 JSON 解析处报错，并把原文前 200 字节带出来，比在这里
    静默拼错更容易定位。
    """
    raw_length = headers.get("content-length")
    if raw_length and raw_length.isdigit():
        length = int(raw_length)
        if length > MAX_RESPONSE_BYTES:
            raise FnosGatewayError(f"开放网关响应过大（{length} 字节）")
        return await reader.readexactly(length)

    payload = await reader.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise FnosGatewayError("开放网关响应过大")
    return payload
