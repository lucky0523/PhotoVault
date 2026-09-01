"""飞牛（fnOS）开放能力代理接口。

存在的理由：飞牛的后端开放 API 只能由应用服务端通过 Unix Socket 调用，token 也只
存在于服务端进程的环境变量里，文档明确要求不要在前端直连、也不要把 token 暴露给
前端。所以 Web 端要看「本应用当前被授权了哪些目录」，只能经由本模块转一手。

这些接口是给「设置 → 飞牛目录授权」诊断页用的，只对 PhotoVault 管理员开放。

关于非飞牛环境：路由是无条件注册的（fpk 打包时 server/ 是整目录原样复制，没有
按变体裁剪的机会，Docker 镜像也共用同一份源码）。在没有开放网关的环境里，这些
接口会返回 503 并说明缺什么，而不是 500。前端那个调用它们的页面本身只存在于飞牛
构建里，所以其它部署形态下不会有人访问到。

Endpoints:
- GET    /api/v1/fnos/env             （环境诊断，不触达网关）
- GET    /api/v1/fnos/shared-folders  （trim.file.getSharedAccessibleFolders）
- DELETE /api/v1/fnos/shared-folders  （trim.file.delSharedAccessibleFolder）

参考：https://developer.fnnas.com/api/authorization/shared-access/
"""

from __future__ import annotations

import logging
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from app.core.fnos import (
    FnosGatewayError,
    FnosUnavailableError,
    call_gateway,
    describe_environment,
)
from app.core.security import require_admin
from app.models.auth import UserInfo

logger = logging.getLogger("photovault.api.fnos")

router = APIRouter()


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------


class FnosEnvResponse(BaseModel):
    """开放 API 可用性诊断。"""

    available: bool
    reason: str
    app_name: str
    socket_path: str
    socket_exists: bool
    #: 只报告 TRIM_API_TOKEN 有没有，绝不返回内容。
    token_present: bool


class FnosSharedFoldersResponse(BaseModel):
    """管理员授权给本应用的共享目录。"""

    paths: List[str]


class FnosDeleteResponse(BaseModel):
    """删除授权的结果，字段沿用网关的 ``suc``。"""

    suc: bool


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _call(req: str, data: dict | None = None) -> dict:
    """调用开放 API，把两类失败翻译成合适的 HTTP 状态码。

    - 环境不具备（没有 socket / 没有 token）→ 503，属于部署问题，重试也没用，
      前端应该提示去检查应用包与运行环境。
    - 网关自己报错（业务码非 0、连接失败）→ 502，属于上游返回的问题，原文透传给
      前端展示，否则「仅管理员可进行此操作」这类关键信息会丢掉。
    """
    try:
        return await call_gateway(req, data)
    except FnosUnavailableError as e:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e)
        ) from e
    except FnosGatewayError as e:
        logger.warning("fnOS gateway call failed: req=%s code=%s msg=%s", req, e.code, e.msg)
        detail = f"{e.msg}（code={e.code}）" if e.code is not None else e.msg
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=detail) from e


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/fnos/env", response_model=FnosEnvResponse)
async def get_fnos_env(
    _current_user: UserInfo = Depends(require_admin),
) -> FnosEnvResponse:
    """探测本进程能否调用飞牛开放 API。

    只做本地检查，不发请求，所以在非飞牛环境下也是 200 —— 页面需要靠
    ``available`` 与 ``reason`` 判断链路断在哪一环。
    """
    return FnosEnvResponse(**describe_environment())


@router.get("/fnos/shared-folders", response_model=FnosSharedFoldersResponse)
async def list_shared_folders(
    _current_user: UserInfo = Depends(require_admin),
) -> FnosSharedFoldersResponse:
    """查询管理员授权给本应用的共享目录。"""
    data = await _call("trim.file.getSharedAccessibleFolders")

    raw = data.get("paths")
    paths = [p for p in raw if isinstance(p, str)] if isinstance(raw, list) else []
    return FnosSharedFoldersResponse(paths=paths)


@router.delete("/fnos/shared-folders", response_model=FnosDeleteResponse)
async def delete_shared_folder(
    path: str = Query(..., min_length=1, description="要删除的应用共享目录授权路径"),
    _current_user: UserInfo = Depends(require_admin),
) -> FnosDeleteResponse:
    """删除一条共享目录授权。

    只是撤销本应用对该目录的访问授权，不会动目录里的任何文件。
    """
    data = await _call("trim.file.delSharedAccessibleFolder", {"path": path})
    return FnosDeleteResponse(suc=bool(data.get("suc")))
