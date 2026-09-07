#!/usr/bin/env bash
# 生成 deploy/.env.production：以示例为底，并自动把本地 .env 中已配置的
# 凭据/模型/邮箱等白名单键复用过去（值中 $ 按 compose 插值规则转义为 $$）。
# 用法：bash deploy/base/make-env.sh   （幂等；之后再按需改 SSL_DOMAIN/TLS_MODE/端口）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

[ -f deploy/.env.production ] || cp deploy/.env.production.example deploy/.env.production

python3 - <<'PY'
import pathlib

def read_env(p):
    d = {}
    try:
        for l in pathlib.Path(p).read_text().splitlines():
            s = l.strip()
            if s and not s.startswith('#') and '=' in s:
                k, _, v = s.partition('=')
                d[k.strip()] = v.strip().strip("'").strip('"')
    except FileNotFoundError:
        pass
    return d

local = read_env('.env')
p = pathlib.Path('deploy/.env.production')
lines = p.read_text().splitlines()
have = {l.split('=', 1)[0].strip() for l in lines if '=' in l}

# 白名单：仅复用本地确实配置过的键，避免覆盖用户手工改的占位
keys = [
    # 身份与 MCP
    'LOREDOCK_ADMIN_USERNAME', 'LOREDOCK_ADMIN_DISPLAY_NAME', 'LOREDOCK_ADMIN_PASSWORD_HASH',
    'LOREDOCK_MCP_ENABLED', 'LOREDOCK_MCP_READ_TOKEN', 'LOREDOCK_MCP_WRITE_TOKEN',
    # Agent 模型（务必从本地带过去，否则上线后知识整理报 AGENT_MODEL_UNAVAILABLE）
    'LOREDOCK_AGENT_ENABLED', 'LOREDOCK_AGENT_CHAT_PROVIDER', 'LOREDOCK_AGENT_MODEL_BASE_URL',
    'LOREDOCK_AGENT_MODEL_NAME', 'LOREDOCK_AGENT_MODEL_API_KEY',
    'LOREDOCK_EVAL_JUDGE_MODEL', 'LOREDOCK_AGENT_TOTAL_TIMEOUT',
    # 基础设施校验值
    'LOREDOCK_KNOWLEDGE_EMBEDDING_MODEL_SHA256',
]

out = []
for l in lines:
    k = l.split('=', 1)[0].strip()
    if k in keys and k in local:
        out.append(f"{k}={local[k].replace('$', '$$')}")
    else:
        out.append(l)
for k in keys:
    if k in local and k not in have:
        out.append(f"{k}={local[k].replace('$', '$$')}")

p.write_text('\n'.join(out) + '\n')
for k in keys:
    if k in local:
        v = next((l.split('=', 1)[1] for l in out if l.startswith(k + '=')), '')
        masked = '<已隐藏 len=%d>' % len(v) if any(s in k for s in ('API_KEY', 'TOKEN', 'PASSWORD', 'HASH', 'SECRET')) else v
        print(f"  复用 {k} = {masked}")
print("deploy/.env.production 已生成/更新（白名单键来自本地 .env，$ 已转义）")
PY