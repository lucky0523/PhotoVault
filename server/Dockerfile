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

WORKDIR /build

# Install dependencies first (layer cache optimization)
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

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

# Copy server application code
COPY server/ ./

# Copy Vue.js build output from builder stage
# main.py resolves web/dist relative to 3 levels up from app/main.py
# With WORKDIR=/app and main.py at /app/app/main.py, it resolves to /web/dist
COPY --from=builder-web /build/dist /web/dist

# Create directories for runtime data
RUN mkdir -p /data/photovault /app/config

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
