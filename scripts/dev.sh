#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java_home="${LOREDOCK_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
node_bin="${LOREDOCK_NODE_BIN:-/opt/homebrew/opt/node@24/bin}"

if [[ -f "${repo_root}/.env" ]]; then
  set -a
  # .env 是开发者本地配置，不纳入版本控制。
  source "${repo_root}/.env"
  set +a
fi

export JAVA_HOME="${java_home}"
export PATH="${java_home}/bin:${node_bin}:${PATH}"
storage_root="${LOREDOCK_STORAGE_ROOT:-${repo_root}/data/objects}"
if [[ "${storage_root}" != /* ]]; then
  storage_root="${repo_root}/${storage_root#./}"
fi
export LOREDOCK_STORAGE_ROOT="${storage_root}"

if [[ "$(java -version 2>&1 | head -n 1)" != *'21.'* ]]; then
  echo "LoreDock 后端需要 Java 21，请设置 LOREDOCK_JAVA_HOME。" >&2
  exit 1
fi
if [[ "$(node --version)" != v24.* ]]; then
  echo "LoreDock 前端需要 Node.js 24，请设置 LOREDOCK_NODE_BIN。" >&2
  exit 1
fi

docker compose -f "${repo_root}/compose.yaml" up --detach --wait database
mkdir -p "${LOREDOCK_STORAGE_ROOT}"

if [[ ! -d "${repo_root}/frontend/node_modules" ]]; then
  (cd "${repo_root}/frontend" && npm ci)
fi

backend_pid=""
frontend_pid=""
cleanup() {
  [[ -n "${frontend_pid}" ]] && kill "${frontend_pid}" >/dev/null 2>&1 || true
  [[ -n "${backend_pid}" ]] && kill "${backend_pid}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

(cd "${repo_root}/backend" && exec ./mvnw spring-boot:run) &
backend_pid="$!"
(cd "${repo_root}/frontend" && exec ./node_modules/.bin/vite --host 0.0.0.0 --port "${LOREDOCK_FRONTEND_PORT:-5173}") &
frontend_pid="$!"

echo "LoreDock 本地开发已启动："
echo "  前端 http://localhost:${LOREDOCK_FRONTEND_PORT:-5173}"
echo "  后端 http://localhost:${LOREDOCK_BACKEND_PORT:-8080}"
echo "按 Ctrl+C 停止前后端；数据库保留运行，可执行 docker compose stop database 停止。"

wait "${backend_pid}" "${frontend_pid}"
