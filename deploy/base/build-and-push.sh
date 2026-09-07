#!/usr/bin/env bash
# 本机执行：构建前后端镜像（交叉架构，目标默认 linux/amd64），默认 docker save 到 base/images/，
# 设 LOREDOCK_PUSH=1 时改为 docker push 到 registry（镜像标签见 .env.production）。
# 开发机是 Apple Silicon(arm64)，云服务器几乎肯定是 amd64；docker save/load 不能跨架构，必须交叉构建。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# 标签与推送从 .env.production 读取（grep 取值，不 source，避免 bcrypt 哈希中的 $ 被展开）
ENV_FILE="$REPO_ROOT/deploy/.env.production"
get_var() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"' || true; }
BACKEND_IMAGE="${LOREDOCK_BACKEND_IMAGE:-$(get_var LOREDOCK_BACKEND_IMAGE)}"
FRONTEND_IMAGE="${LOREDOCK_FRONTEND_IMAGE:-$(get_var LOREDOCK_FRONTEND_IMAGE)}"
BACKEND_IMAGE="${BACKEND_IMAGE:-loredock-backend:0.1.0}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-loredock-frontend:0.1.0}"
PLATFORM="${LOREDOCK_BUILD_PLATFORM:-linux/amd64}"
PUSH="${LOREDOCK_PUSH:-0}"

echo "==> 平台：${PLATFORM}（开发机=$(docker info --format '{{.Architecture}}')）"
echo "==> 后端镜像：$BACKEND_IMAGE"
docker buildx build --platform "$PLATFORM" --load -t "$BACKEND_IMAGE" backend/
echo "==> 前端镜像：$FRONTEND_IMAGE"
docker buildx build --platform "$PLATFORM" --load -t "$FRONTEND_IMAGE" frontend/

echo "==> 架构校验（应为 linux/amd64）："
ARCH_BK="$(docker image inspect "$BACKEND_IMAGE" --format '{{.Os}}/{{.Architecture}}')"
ARCH_FE="$(docker image inspect "$FRONTEND_IMAGE" --format '{{.Os}}/{{.Architecture}}')"
echo "   backend=$ARCH_BK  frontend=$ARCH_FE"

if [ "$PUSH" = "1" ]; then
  echo "==> docker push 到 registry..."
  docker push "$BACKEND_IMAGE"
  docker push "$FRONTEND_IMAGE"
else
  SAVE_DIR="$REPO_ROOT/deploy/base/images"
  mkdir -p "$SAVE_DIR"
  echo "==> docker save 到 $SAVE_DIR/loredock-images.tar ..."
  docker save -o "$SAVE_DIR/loredock-images.tar" "$BACKEND_IMAGE" "$FRONTEND_IMAGE"
  du -sh "$SAVE_DIR/loredock-images.tar"
fi
echo "==> 镜像构建完成"