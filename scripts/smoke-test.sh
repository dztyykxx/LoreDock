#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
smoke_project="loredock-smoke"
smoke_tmp="$(mktemp -d)"
compose=(docker compose -p "${smoke_project}" -f "${repo_root}/compose.yaml")
java_home="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
node_bin="/opt/homebrew/opt/node@24/bin"
backend_pid=""
frontend_pid=""
core_contract_tests="IdentityWebContractTest,ProjectWebContractTest,KnowledgeDocumentWebContractTest,KnowledgeSearchWebContractTest,CodeSnapshotWebContractTest,WebQaWebContractTest,WebQaSseContractTest,KnowledgeGapWebContractTest"
# 公开测试夹具，仅用于让隔离冒烟进程满足固定 ADMIN/MEMBER 启动约束。
smoke_password_hash='$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'

cleanup_processes() {
  if [[ -n "${frontend_pid}" ]]; then
    kill "${frontend_pid}" >/dev/null 2>&1 || true
    wait "${frontend_pid}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${backend_pid}" ]]; then
    kill "${backend_pid}" >/dev/null 2>&1 || true
    wait "${backend_pid}" >/dev/null 2>&1 || true
  fi
  backend_pid=""
  frontend_pid=""
}

cleanup() {
  local exit_status="$?"
  if [[ "${exit_status}" -ne 0 ]]; then
    echo "冒烟验证失败，输出最近日志：" >&2
    [[ -f "${smoke_tmp}/backend.log" ]] && tail -n 120 "${smoke_tmp}/backend.log" >&2 || true
    [[ -f "${smoke_tmp}/frontend.log" ]] && tail -n 40 "${smoke_tmp}/frontend.log" >&2 || true
  fi
  cleanup_processes
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "${smoke_tmp}"
}
trap cleanup EXIT INT TERM

wait_http() {
  local url="$1"
  local expected="$2"
  for _ in $(seq 1 60); do
    if curl --fail --silent "${url}" | grep --quiet "${expected}"; then
      return 0
    fi
    sleep 1
  done
  echo "等待地址失败: ${url}" >&2
  return 1
}

start_backend() {
  (
    cd "${repo_root}"
    exec env \
      JAVA_HOME="${java_home}" \
      PATH="${java_home}/bin:${PATH}" \
      LOREDOCK_DB_URL="jdbc:postgresql://localhost:55432/loredock" \
      LOREDOCK_DB_USER="loredock" \
      LOREDOCK_DB_PASSWORD="loredock_local_only" \
      LOREDOCK_STORAGE_ROOT="${smoke_tmp}/objects" \
      LOREDOCK_BACKEND_PORT="8080" \
      LOREDOCK_ADMIN_USERNAME="smoke-admin" \
      LOREDOCK_ADMIN_DISPLAY_NAME="Smoke Admin" \
      LOREDOCK_ADMIN_PASSWORD_HASH="${smoke_password_hash}" \
      LOREDOCK_MEMBER_USERNAME="smoke-member" \
      LOREDOCK_MEMBER_DISPLAY_NAME="Smoke Member" \
      LOREDOCK_MEMBER_PASSWORD_HASH="${smoke_password_hash}" \
      "${java_home}/bin/java" -jar backend/target/loredock-backend-0.1.0-SNAPSHOT.jar
  ) >"${smoke_tmp}/backend.log" 2>&1 &
  backend_pid="$!"
}

start_frontend() {
  (
    cd "${repo_root}/frontend"
    exec env PATH="${node_bin}:${PATH}" ./node_modules/.bin/vite --host 127.0.0.1 --port 15173
  ) >"${smoke_tmp}/frontend.log" 2>&1 &
  frontend_pid="$!"
}

run_core_contract_baseline() {
  echo "执行核心 HTTP 契约基线：认证、项目/分支、知识、代码、问答、引用/拒答与知识缺口"
  env JAVA_HOME="${java_home}" PATH="${java_home}/bin:${PATH}" \
    "${repo_root}/backend/mvnw" -q -f "${repo_root}/backend/pom.xml" \
    -Dtest="${core_contract_tests}" test
  echo "核心 HTTP 契约基线通过：8 个代表性契约测试套件"
}

cleanup
export LOREDOCK_DB_PORT=55432
export LOREDOCK_DB_PASSWORD=loredock_local_only
"${compose[@]}" up --detach --wait database
mkdir -p "${smoke_tmp}/objects"

run_core_contract_baseline
env JAVA_HOME="${java_home}" PATH="${java_home}/bin:${PATH}" \
  "${repo_root}/backend/mvnw" -q -f "${repo_root}/backend/pom.xml" -DskipTests package
env PATH="${node_bin}:${PATH}" npm --prefix "${repo_root}/frontend" ci --silent

start_backend
start_frontend
wait_http "http://localhost:8080/actuator/health/liveness" '"status":"UP"'
wait_http "http://localhost:8080/actuator/health/readiness" '"status":"UP"'
wait_http "http://localhost:15173" 'LoreDock'
wait_http "http://localhost:15173/api/v1/system/status" '"status":"UP"'
echo "后端存活状态：$(curl --fail --silent http://localhost:8080/actuator/health/liveness)"
echo "后端就绪状态：$(curl --fail --silent http://localhost:8080/actuator/health/readiness)"
echo "前端代理状态：$(curl --fail --silent http://localhost:15173/api/v1/system/status)"

# 空库必须由本地后端的 Flyway 建表并启用 vector。
"${compose[@]}" exec -T database psql -U loredock -d loredock -v ON_ERROR_STOP=1 \
  -c "select extversion from pg_extension where extname = 'vector';" \
  -c "select version, success from flyway_schema_history order by installed_rank;"

marker_key="11111111-1111-1111-1111-111111111111"
"${compose[@]}" exec -T database psql -U loredock -d loredock -v ON_ERROR_STOP=1 <<SQL
insert into stored_object(
  id, object_key, status, original_filename, content_type, size_bytes, sha256,
  created_at, updated_at, created_by, updated_by
) values (
  111, '${marker_key}', 'AVAILABLE', 'smoke.txt', 'text/plain', 12,
  'c29e7f348f118c8b2d35e9a46c3c5595d9a021e29513cbb267df4a107a9908f7',
  now(), now(), 'SYSTEM', 'SYSTEM'
);
insert into background_job(
  id, job_type, status, progress, created_at, updated_at, created_by, updated_by
) values (
  222, 'SMOKE', 'PENDING', 0,
  now(), now(), 'SYSTEM', 'SYSTEM'
);
SQL
mkdir -p "${smoke_tmp}/objects/11"
printf smoke-marker >"${smoke_tmp}/objects/11/${marker_key}"

# 重启数据库和宿主机前后端，验证数据库卷与本地对象目录都保留。
cleanup_processes
"${compose[@]}" stop database
"${compose[@]}" start --wait database
start_backend
start_frontend
wait_http "http://localhost:8080/actuator/health/readiness" '"status":"UP"'
wait_http "http://localhost:15173" 'LoreDock'
"${compose[@]}" exec -T database psql -U loredock -d loredock -v ON_ERROR_STOP=1 \
  -c "select count(*) from stored_object where object_key = '${marker_key}';" \
  -c "select count(*) from background_job where id = 222;"
grep --quiet smoke-marker "${smoke_tmp}/objects/11/${marker_key}"

# 数据库失联时 Java 进程仍存活，但 readiness 必须失败。
"${compose[@]}" stop database
wait_http "http://localhost:8080/actuator/health/liveness" '"status":"UP"'
for _ in $(seq 1 30); do
  if ! curl --fail --silent "http://localhost:8080/actuator/health/readiness" >/dev/null; then
    echo "LoreDock 本地栈冒烟验证通过"
    exit 0
  fi
  sleep 1
done

echo "数据库停止后 readiness 仍错误地报告成功" >&2
exit 1
