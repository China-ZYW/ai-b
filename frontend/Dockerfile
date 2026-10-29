# =============================================================================
# aibi 前端 Dockerfile（多阶段：node 构建 → nginx 静态托管）
#
# 构建：docker build -t aibi-frontend ./frontend
# 运行：随 docker-compose.yml 一起起，或独立跑：
#   docker run --rm -p 80:80 aibi-frontend
#
# 反代 /api 与 /mcp 到后端容器（compose 网络内的服务名 backend:8080）。
# 生产环境建议再加 TLS 终结层（Nginx/Caddy/云 LB）。
# =============================================================================

# ---------- 构建阶段 ----------
FROM node:20-alpine AS builder
WORKDIR /app

# 依赖层缓存：先只拷锁文件
COPY package.json package-lock.json* ./
RUN npm ci --prefer-offline --no-audit

# 源码
COPY . .

# vue-tsc 类型检查 + vite build（package.json 里 npm run build 已含）
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine AS runtime

# SPA 路由兜底 + /api /mcp 反代
COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # 静态资源长缓存（vite 产物带 hash）
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # API 反代到 backend 容器（compose 网络内服务名）
    location /api/ {
        proxy_pass         http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        # SSE：关缓冲 + 长超时
        proxy_buffering    off;
        proxy_cache        off;
        proxy_read_timeout 300s;
    }

    # MCP 端点（Streamable HTTP + SSE）
    location /mcp {
        proxy_pass         http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_buffering    off;
        proxy_cache        off;
        proxy_read_timeout 600s;
    }

    # OAuth 元数据发现（RFC 9728 / 8414）
    location /.well-known/ {
        proxy_pass http://backend:8080;
    }

    # 健康检查
    location = /actuator/health {
        proxy_pass http://backend:8080/actuator/health;
    }

    # SPA history fallback
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-store";
    }
}
EOF

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.0/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
