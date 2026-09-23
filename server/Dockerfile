# =============================================================================
# PhotoVault - Multi-stage Production Dockerfile
# =============================================================================
# Stage 1: Build Vue.js frontend
# Stage 2: Python runtime with server + Vue.js dist
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Build Vue.js frontend
# ---------------------------------------------------------------------------
FROM node:20-alpine AS builder-web

# 仓库布局在构建阶段里原样复刻（/build/web + /build/server），因为 vite.config.ts
# 要从 ../server/app/__init__.py 读产品版本号——那是全仓库唯一的版本修改入口。
WORKDIR /build/web

# Install dependencies first (layer cache optimization)
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

# 只带版本号那一个文件，不是整个 server/：服务端源码变动不该让前端依赖层失效。
COPY server/app/__init__.py /build/server/app/__init__.py

# Copy source and build
COPY web/ ./
RUN npm run build


# ---------------------------------------------------------------------------
# Stage 2: Python runtime
# ---------------------------------------------------------------------------
FROM python:3.11-slim AS runtime

# Install system dependencies for Pillow (JPEG, PNG, etc.) and ffmpeg (video thumbnails)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libjpeg62-turbo-dev \
    libpng-dev \
    zlib1g-dev \
    libffi-dev \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies first (layer cache optimization)
COPY server/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Optional analysis dependency (onnxruntime) for the people / scenes
# dimensions. Off by default because the models and runtime add substantial
# image size; HEIC/HEIF decoding is already installed as a core dependency.
# Enable with: docker build --build-arg WITH_ANALYSIS=1 ...
ARG WITH_ANALYSIS=0
COPY server/requirements-analysis.txt ./
RUN if [ "$WITH_ANALYSIS" = "1" ]; then \
        pip install --no-cache-dir -r requirements-analysis.txt; \
    fi

# Copy server application code
COPY server/ ./

# Copy Vue.js build output from builder stage
# main.py resolves web/dist relative to 3 levels up from app/main.py
# With WORKDIR=/app and main.py at /app/app/main.py, it resolves to /web/dist
COPY --from=builder-web /build/web/dist /web/dist

# Create directories for runtime data
RUN mkdir -p /data/photovault /app/config

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
