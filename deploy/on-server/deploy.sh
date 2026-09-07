#!/usr/bin/env bash
# 服务器端部署（在远程部署目录执行）：加载/拉取镜像 → 起 database → 等 healthy →
# 若存在备份 dump 则 pg_restore 静默恢复 → 同步对象存储与模型 → 全量 up → 冒烟。
# 依赖同目录下的 compose.yaml 与 .env.production；幂等，可重复执行。
#
# 数据恢复时序的原因：backend 启动时 Flyway 会对空库建全部迁移，与恢复冲突；
# 必须先恢复再启 backend，让 flyway_schema_history 与 dump 一致，启动时仅 no-op 校验。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR/.."    # 远程部署目录

command -v docker >/dev/null || { echo "服务器缺少 docker"; exit 1; }
[ -f .env.production ] || { echo "缺少 .env.production"; exit 1; }

# ubuntu 等非 docker 组成员需要 sudo；能直连则不加
if docker info >/dev/null 2>&1; then
  DOCKER="docker"
else
  command -v sudo >/dev/null || { echo "需要 sudo 但不可用"; exit 1; }
  sudo -n true 2>/dev/null || { echo "sudo 需要密码，无法非交互执行"; exit 1; }
  DOCKER="sudo docker"
fi
COMPOSE_CMD=($DOCKER compose --env-file .env.production -f compose.yaml)
$DOCKER info >/dev/null 2>&1 || { echo "docker 不可用（守护进程未启动）"; exit 1; }

echo "==> 0/7 preflight"
mkdir -p data/objects data/work data/indexes models

echo "==> 1/7 加载/拉取镜像"
if [ -f loredock-images.tar ]; then
  $DOCKER load -i loredock-images.tar
else
  # registry 模式：pull 前后端镜像
  "${COMPOSE_CMD[@]}" pull backend frontend || { echo "pull 失败：确认 .env.production 镜像标签与登录状态"; exit 1; }
fi

echo "==> 2/7 启动 database（等待 healthy）..."
"${COMPOSE_CMD[@]}" up -d --wait database

echo "==> 3/7 恢复数据（幂等：--clean --if-exists）"
if [ -f backup/loredock.dump ]; then
  backup_restore() {
    [ -f backup/loredock.dump ] || return 0
    echo "   pg_restore -Fc 到全新库..."
    $DOCKER compose --env-file .env.production -f compose.yaml exec -T database \
      sh -c 'pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
      < backup/loredock.dump
  }
  if backup_restore; then
    echo "   数据恢复成功"
  else
    echo "!! 恢复失败：销毁当前 prod 卷重试一次（保持干净状态）" >&2
    "${COMPOSE_CMD[@]}" down -v
    "${COMPOSE_CMD[@]}" up -d --wait database
    backup_restore
  fi
else
  echo "   无备份 dump，跳过（全新部署）"
fi

echo "==> 4/7 同步对象存储与 embedding 模型（与 DB 来自同一备份包）"
if [ -d backup/objects ] && [ -n "$(ls -A backup/objects 2>/dev/null)" ]; then
  rsync -a backup/objects/ data/objects/
  echo "   对象存储已就位：$(du -sh data/objects | cut -f1)"
fi
if [ -d backup/models ] && [ -n "$(ls -A backup/models 2>/dev/null)" ]; then
  rsync -a backup/models/ models/
  echo "   模型已就位：$(ls models)"
fi

echo "==> 5/7 全量启动"
# internal 模式顺带启动 certbot 首次签发；external 模式 TLS 由上游反代（如 1Panel）接管，不启 certbot
TLS_MODE="$(grep -E '^LOREDOCK_TLS_MODE=' .env.production | cut -d= -f2- | tr -d '"')"
TLS_MODE="${TLS_MODE:-internal}"
if [ "$TLS_MODE" = "internal" ]; then
  "${COMPOSE_CMD[@]}" --profile certbot up -d
  "${COMPOSE_CMD[@]}" --profile certbot up -d --wait backend frontend
else
  "${COMPOSE_CMD[@]}" up -d --wait backend frontend
fi

echo "==> 6/7 冒烟检查"
echo -n "   backend readiness: "
$DOCKER compose --env-file .env.production -f compose.yaml exec -T backend \
  curl -fsS http://127.0.0.1:8080/actuator/health/readiness && echo " OK" || echo " FAIL"
echo -n "   nginx /healthz: "
$DOCKER compose --env-file .env.production -f compose.yaml exec -T frontend \
  curl -fsS http://127.0.0.1/healthz && echo " OK" || echo " FAIL"
# 后端容器内 curl frontend 服务，验证 nginx→backend 路由链路（external 模式 nginx 仅监听容器内 80）
echo -n "   nginx->backend /api/v1/system/status: "
$DOCKER compose --env-file .env.production -f compose.yaml exec -T backend \
  curl -fsS http://frontend/api/v1/system/status -o /dev/null && echo " OK" || echo " FAIL"
DOMAIN="$(grep -E '^SSL_DOMAIN=' .env.production | cut -d= -f2-)"
if [ "$TLS_MODE" = "internal" ]; then
  # 公网可达性（证书可能仍是占位自签，故 -k；certbot 签好后应去掉 -k 复验）
  echo -n "   公网 https://$DOMAIN/api/v1/system/status: "
  $DOCKER compose --env-file .env.production -f compose.yaml exec -T frontend \
    sh -c "curl -fsk https://$DOMAIN/api/v1/system/status -o /dev/null && echo OK" || echo " FAIL"
else
  # external 模式：验证宿主映射端口链路（宿主有 curl 才做；公网经上游反代配置好后另行验证）
  HTTP_PORT="$(grep -E '^LOREDOCK_WEB_HTTP_PORT=' .env.production | cut -d= -f2- | tr -d '"')"
  HTTP_PORT="${HTTP_PORT:-80}"
  if command -v curl >/dev/null 2>&1; then
    echo -n "   宿主 http://127.0.0.1:${HTTP_PORT}/api/v1/system/status: "
    curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/v1/system/status" -o /dev/null && echo " OK" || echo " FAIL"
  else
    echo "   宿主无 curl，跳过宿主端口直连检查（公网经反代后浏览器验证）"
  fi
fi

echo "==> 7/7 状态"
"${COMPOSE_CMD[@]}" ps

echo ""
echo "完成。后续验证命令见 deploy/部署指南.md："
echo "  curl http://127.0.0.1:\${LOREDOCK_WEB_HTTP_PORT}/api/v1/system/status   # external 模式"
echo "  curl -k https://$DOMAIN/api/v1/system/status                             # internal 模式"
echo "  登录拿 cookie -> /api/projects；POST /mcp 带 read token 做 initialize"