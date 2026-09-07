#!/usr/bin/env bash
# 本机执行：把部署件 + 数据备份 +（若为 save 回退）镜像 tar rsync 到服务器，随后远程执行部署脚本。
# 用法：
#   ./deploy/base/upload.sh user@host              # 默认部署目录 ~/loredock-deploy
#   RSH="ssh -p 2222" ./deploy/base/upload.sh user@host ~/loredock-deploy
set -euo pipefail

SERVER="${1:?用法: upload.sh user@host [远程部署目录]}"
REMOTE_DIR="${2:-~/loredock-deploy}"
RSH="${RSH:-ssh}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

[ -f deploy/.env.production ] || { echo "缺少 deploy/.env.production：先 cp deploy/.env.production.example deploy/.env.production 并填真值"; exit 1; }

# 远端 shell 的 tilde 展开差异：本脚本在开发机上运行，$HOME 是本机的，
# 不能用来展开远端 ~。先向远端询问真实 $HOME，再用它把 ~ 展开成远端绝对路径；
# 后续所有 mkdir/rsync/ssh 都基于该绝对路径，杜绝「字面 ~ 目录」与「本地 HOME 误展开」两类坑。
REMOTE_HOME="$($RSH "$SERVER" 'printf %s "$HOME"')"
REMOTE_ABS="${REMOTE_DIR/#\~/$REMOTE_HOME}"

echo "==> 1/4 准备服务器目录 $REMOTE_ABS ..."
$RSH "$SERVER" "mkdir -p '$REMOTE_ABS'"

echo "==> 2/4 同步部署件（compose / 远程脚本 / certbot 入口 / .env.production）..."
# 注意：源目录不带尾斜杠，确保 on-server/、certbot/ 作为子目录落在远程根目录，与 compose 挂载路径对应
rsync -az -e "$RSH" \
  deploy/compose.yaml \
  deploy/on-server \
  deploy/certbot \
  deploy/.env.production \
  "$SERVER:$REMOTE_ABS/"

echo "==> 3/4 同步数据备份（dump + objects + models）..."
if [ -f deploy/backup/loredock.dump ]; then
  rsync -az -e "$RSH" deploy/backup/ "$SERVER:$REMOTE_ABS/backup/"
else
  echo "   （无 deploy/backup/loredock.dump，跳过数据同步。首次迁移请先运行 backup-data.sh）"
fi

echo "==> 4/4 同步镜像（save 回退模式才需要）..."
if [ -f deploy/base/images/loredock-images.tar ]; then
  rsync -az -e "$RSH" deploy/base/images/loredock-images.tar "$SERVER:$REMOTE_ABS/"
else
  echo "   （无镜像 tar；若 .env.production 配置了 registry 镜像则服务器直接 pull）"
fi

echo "==> 远程执行部署 deploy/on-server/deploy.sh ..."
$RSH "$SERVER" "cd '$REMOTE_ABS' && bash on-server/deploy.sh"
echo "==> 上传与部署完成（冒烟输出见上）"