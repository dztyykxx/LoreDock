#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java_home="${LOREDOCK_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
node_bin="${LOREDOCK_NODE_BIN:-/opt/homebrew/opt/node@24/bin}"

if [[ -f "${repo_root}/.env" ]]; then
  set -a
  # 本机 secret 仅注入子进程，不打印、不复制到测试报告。
  source "${repo_root}/.env"
  set +a
fi

if [[ -z "${LOREDOCK_AGENT_MODEL_API_KEY:-}" || "${LOREDOCK_AGENT_MODEL_API_KEY}" == "replace_with_deployment_secret" ]]; then
  echo "缺少 LOREDOCK_AGENT_MODEL_API_KEY，无法执行真实模型 Smoke。" >&2
  exit 2
fi

export JAVA_HOME="${java_home}"
export PATH="${java_home}/bin:${node_bin}:${PATH}"
export LOREDOCK_AGENT_ENABLED=true
export LOREDOCK_AGENT_CHAT_PROVIDER=openai

echo "执行真实 PostgreSQL + 真实模型项目问答 Smoke（会产生两次问答的模型调用）"
"${repo_root}/backend/mvnw" -q -f "${repo_root}/backend/pom.xml" \
  -Pintegration \
  -Dloredock.real-model-smoke=true \
  -Dtest=AgentPropertiesTest \
  -Dit.test=ProjectQaRealModelSmokeIT \
  verify

echo "验证模型故障与证据不足的后端终态语义"
"${repo_root}/backend/mvnw" -q -f "${repo_root}/backend/pom.xml" \
  -Dtest=AgentServiceImplTest,ProjectQaRunTaskExecutorTest,WebQaHttpMapperTest \
  test

echo "验证拒答、运行上限和模型不可用的页面文案"
npm --prefix "${repo_root}/frontend" test -- src/components/qaComponents.test.ts

echo "LoreDock 真实模型 Smoke 通过：回答/拒答、步骤数、引用、故障语义和页面文案均已验证。"
