# PhotoVault Server Application

# 产品版本号的唯一修改入口。
#
# 服务端、Web、fnOS 安装包、Docker 镜像 tag 全部由这一行派生，改版本只改这里：
#
#   server/pyproject.toml   dynamic = ["version"] + [tool.hatch.version] 读本文件
#   web                     vite.config.ts 构建期读本文件，注入 __APP_VERSION__
#   .fpk / Docker tag       scripts/build.sh 的 detect_version() 读本文件
#   运行时 API              /connection/test、/server/about 与 FastAPI OpenAPI 元数据
#
# 为什么真源必须在这里，而不是 pyproject.toml 或仓库根的 VERSION 文件：
# .fpk 把 server/ 原样复制到设备上直接用 uvicorn 跑源码，既没有安装过包（读不到
# dist-info，importlib.metadata 拿不到版本），也不会带上 server/ 之外的文件。所以
# 只有「server/ 内部的一个 Python 字面量」这一种形式在所有部署形态下都成立。
#
# Android 的 versionName/versionCode 不在此列：versionCode 是必须单调递增的发布
# 序号（服务端会拒收重复值），与产品版本不是同一个语义。
__version__ = "1.3"

__all__ = ["__version__"]
