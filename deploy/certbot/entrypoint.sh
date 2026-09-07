#!/usr/bin/env sh
# certbot 侧车入口：以签发者是否包含 "Let's Encrypt" 判断是否首次签发，
# 避免把 nginx 入口生成的占位自签证书当成已签发而跳过；随后进入 12h 续期循环。
# 运行前提：frontend（nginx）已起来且 80 端口 ACME challenge 可达。
set -eu

: "${SSL_DOMAIN:?需要 SSL_DOMAIN}"
: "${ACME_EMAIL:?需要 ACME_EMAIL}"

CERT_FILE="/etc/letsencrypt/live/${SSL_DOMAIN}/fullchain.pem"

if ! openssl x509 -in "$CERT_FILE" -noout -issuer 2>/dev/null | grep -qi "Let's Encrypt"; then
  # 占位证书签发者不是 Let's Encrypt（或文件不存在）→ 首次真实签发。
  # 建议上线前先用 --staging 试跑一次，确认 webroot challenge 可达再切正式，避免触发正式限流。
  certbot certonly --webroot -w /var/www/certbot --non-interactive --agree-tos \
    --email "$ACME_EMAIL" -d "$SSL_DOMAIN"
fi

trap exit TERM
while :; do
  # renew 失败不退出，下一轮重试；证书轮换由 nginx 的 6h reload 兜底生效。
  certbot renew --webroot -w /var/www/certbot || true
  sleep 43200 &
  wait $!
done