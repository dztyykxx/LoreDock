#!/usr/bin/env bash
# 本机执行：把本地开发数据导出为可迁移备份包（PG dump + 对象存储 + embedding 模型），并校验模型 SHA。
# 生产要求 DB 与对象文件来自同一静默写入窗口：pg_dump 自带一致性快照，对象文件相邻分钟 rsync 即可；
# 若存在活跃写入，请先停掉本地后端/作业再执行。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="${1:-$REPO_ROOT/deploy/backup}"
cd "$REPO_ROOT"

command -v docker >/dev/null || { echo "缺少 docker"; exit 1; }
docker compose ps >/dev/null 2>&1 || { echo "本地开发数据库未运行（先启动 Docker 与 dev compose）"; exit 1; }

mkdir -p "$BACKUP_DIR/objects" "$BACKUP_DIR/models"

echo "==> 1/4 导出 PG dump（自定义格式，一致性快照）..."
docker compose exec -T database sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > "$BACKUP_DIR/loredock.dump"

echo "==> 2/4 拷贝对象存储（保留 UUID 分片目录树，恢复按同树还原）..."
rsync -a data/objects/ "$BACKUP_DIR/objects/"

echo "==> 3/4 拷贝 embedding 模型目录..."
rsync -a .loredock-run/models/ "$BACKUP_DIR/models/"

echo "==> 4/4 校验模型 SHA-256..."
EXPECTED_SHA="${LOREDOCK_KNOWLEDGE_EMBEDDING_MODEL_SHA256:-3a40c6eab3abdf2bd07651031a36038c2dfaf4ebb8d62ddc78f2324b2ff4389a}"
MODEL_FILE="$BACKUP_DIR/models/bge-small-zh-v1.5/model.onnx"
if [ ! -f "$MODEL_FILE" ]; then
  echo "!! 未找到模型文件：$MODEL_FILE" >&2
  exit 1
fi
ACTUAL_SHA="$(shasum -a 256 "$MODEL_FILE" | awk '{print $1}')"
if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "!! 模型 SHA 不匹配：实际=${ACTUAL_SHA} 期望=${EXPECTED_SHA}" >&2
  echo "!! 请以 .env.production 中 LOREDOCK_KNOWLEDGE_EMBEDDING_MODEL_SHA256 的期望值为准" >&2
else
  echo "   模型 SHA 校验通过：${ACTUAL_SHA}"
fi

echo "==> 备份完成：$(du -sh "$BACKUP_DIR" | cut -f1) @ $BACKUP_DIR"