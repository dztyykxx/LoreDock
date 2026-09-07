# syntax=docker/dockerfile:1
# LoreDock 前端镜像
# 构建阶段固定在 BUILDPLATFORM（本机原生平台，如 arm64）运行 Node，避免 Vite/vue-tsc 的
# 原生二进制（esbuild）在 QEMU 模拟 amd64 下段错误；产物是架构无关的静态文件，可 COPY 进 amd64 nginx。
# VITE_API_BASE_URL 留空 → 浏览器同源调用 /api，由 nginx 反代到后端（cookie 认证要求同源）。

# ---- 构建阶段（原生架构，engines 精确锁定 24.18.0 且 .npmrc engine-strict=true）----
FROM --platform=$BUILDPLATFORM node:24.18.0-slim AS build
WORKDIR /build

COPY package.json package-lock.json .npmrc ./
# .npmrc engine-strict=true 且 engines.npm=11.16.0，钉死 npm 版本避免镜像自带 npm 版本不达门槛
RUN npm install -g npm@11.16.0
RUN npm ci

COPY . .
RUN npm run build

# ---- 运行阶段（按 --platform 目标架构，默认 amd64）----
FROM nginx:1.27-alpine
# curl: /healthz 探活；openssl: 占位自签证书（internal TLS 模式）；gettext: envsubst（nginx 官方镜像已内置）
RUN apk add --no-cache curl openssl

COPY --from=build /build/dist /usr/share/nginx/html
# 模板放 /etc/nginx/conf.d.template/ 而非官方 /etc/nginx/templates/：由自定义入口显式只替换 ${SSL_DOMAIN}，
# 避免官方 envsubst 把 $uri/$host 等 nginx 运行时变量清空。移除镜像自带欢迎页 default.conf 防配置并存歧义。
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/nginx.prod.conf.template /etc/nginx/conf.d.template/loredock.conf
COPY nginx/nginx.prod-http.conf.template /etc/nginx/conf.d.template/loredock-http.conf
COPY nginx/entrypoint.sh /usr/local/bin/nginx-entrypoint.sh
RUN chmod +x /usr/local/bin/nginx-entrypoint.sh

EXPOSE 80 443
ENTRYPOINT ["/usr/local/bin/nginx-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]