#!/usr/bin/env sh
# nginx 容器自定义入口。
# 两种模式（LOREDOCK_TLS_MODE）：
#   internal（默认）：本 nginx 自己终结 TLS（443），配合 certbot 侧车签发 Let's Encrypt；需要 SSL_DOMAIN。
#   external        ：80/443 已被宿主机/面板反代占用（如 1Panel），本 nginx 只提供 HTTP，上游终结 TLS。
# 只替换 ${SSL_DOMAIN}，避免 envsubst 把 $uri/$host 等运行时变量清空；周期 reload 轮换证书（internal 模式）。
set -eu

MODE="${LOREDOCK_TLS_MODE:-internal}"

case "$MODE" in
  internal)
    : "${SSL_DOMAIN:?internal 模式需要 SSL_DOMAIN（见 .env.production）}"
    CERT_DIR="/etc/letsencrypt/live/${SSL_DOMAIN}"
    mkdir -p "$CERT_DIR" /var/www/certbot
    # 占位自签证书：缺证书时 nginx 因 ssl_certificate 报 [emerg] 直接退出，80 端口没人听会导致 ACME 永远完成不了。
    if [ ! -s "$CERT_DIR/fullchain.pem" ]; then
      openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
        -keyout "$CERT_DIR/privkey.pem" \
        -out "$CERT_DIR/fullchain.pem" \
        -subj "/CN=${SSL_DOMAIN}" >/dev/null 2>&1
    fi
    envsubst '${SSL_DOMAIN}' \
      < /etc/nginx/conf.d.template/loredock.conf \
      > /etc/nginx/conf.d/loredock.conf
    # 6 小时周期 reload，让 certbot 新签/续期的证书生效（无需挂 docker.sock）
    ( while :; do sleep 21600; nginx -s reload 2>/dev/null || true; done ) &
    ;;
  external)
    cp /etc/nginx/conf.d.template/loredock-http.conf /etc/nginx/conf.d/loredock.conf
    ;;
  *)
    echo "未知 LOREDOCK_TLS_MODE=$MODE（可选 internal|external）" >&2
    exit 1
    ;;
esac

# 交给官方入口：其会按 /docker-entrypoint.d/ 脚本初始化并启动 nginx
exec /docker-entrypoint.sh "$@"