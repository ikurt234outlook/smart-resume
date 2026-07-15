# syntax=docker/dockerfile:1.7

# 多阶段构建 - 构建阶段
FROM node:20-alpine AS builder

WORKDIR /app

# 通过 packageManager 字段激活锁定的 pnpm 版本
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable

# 复制依赖清单并安装全部依赖（构建需要 devDependencies）
COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,id=smart-resume-pnpm,target=/pnpm/store \
    pnpm install --frozen-lockfile --store-dir /pnpm/store

# 复制源代码
COPY . .

# 设置环境变量为空（因为代码中已经包含 /api 前缀）
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# 构建应用
RUN pnpm build

# 运行阶段 - 使用 Nginx 托管静态文件
FROM nginx:1.31

# 复制自定义 Nginx 配置
COPY --from=builder /app/dist /usr/share/nginx/html

# 创建 Nginx 配置文件
RUN echo 'server { \
    listen 80; \
    server_name localhost; \
    root /usr/share/nginx/html; \
    index index.html; \
    location / { \
        try_files $uri $uri/ /index.html; \
    } \
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ { \
        expires 1y; \
        add_header Cache-Control "public, immutable"; \
    } \
    location ~* \.html$ { \
        add_header Cache-Control "no-cache, no-store, must-revalidate"; \
    } \
    gzip on; \
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript; \
}' > /etc/nginx/conf.d/default.conf

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# 启动 Nginx
CMD ["nginx", "-g", "daemon off;"]
