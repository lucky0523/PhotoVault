#!/usr/bin/env bash
#
# generate-self-signed-cert.sh — 为 PhotoVault 生成自签名 TLS 证书
#
# 用法：
#   ./scripts/generate-self-signed-cert.sh [域名或IP]
#
# 示例：
#   ./scripts/generate-self-signed-cert.sh                    # 默认 localhost
#   ./scripts/generate-self-signed-cert.sh 192.168.1.100
#   ./scripts/generate-self-signed-cert.sh nas.local
#
# 生成的证书和密钥文件位于 ./certs/ 目录下。

set -euo pipefail

DOMAIN="${1:-localhost}"
CERT_DIR="./certs"
CERT_FILE="${CERT_DIR}/photovault.crt"
KEY_FILE="${CERT_DIR}/photovault.key"
DAYS=365

echo "=== PhotoVault 自签名证书生成工具 ==="
echo ""
echo "域名/IP: ${DOMAIN}"
echo "有效期: ${DAYS} 天"
echo "输出目录: ${CERT_DIR}"
echo ""

# 创建证书输出目录
mkdir -p "${CERT_DIR}"

# 构建 Subject Alternative Names (SAN) 配置
# 包含常见局域网 IP 段和用户指定的域名/IP
SAN_CONFIG=$(cat <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
x509_extensions = v3_ext

[dn]
C = CN
ST = Local
L = LAN
O = PhotoVault
OU = Self-Signed
CN = ${DOMAIN}

[v3_ext]
authorityKeyIdentifier = keyid,issuer
basicConstraints = CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = ${DOMAIN}
DNS.2 = localhost
DNS.3 = photovault.local
IP.1 = 127.0.0.1
IP.2 = ::1
IP.3 = 192.168.1.1
IP.4 = 192.168.0.1
IP.5 = 10.0.0.1
EOF
)

# 如果用户输入的是 IP 地址，额外加入 SAN
if [[ "${DOMAIN}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    SAN_CONFIG="${SAN_CONFIG}
IP.6 = ${DOMAIN}"
fi

# 将 SAN 配置写入临时文件
TMPFILE=$(mktemp)
echo "${SAN_CONFIG}" > "${TMPFILE}"

# 生成证书和私钥
openssl req -x509 -nodes -days "${DAYS}" \
    -newkey rsa:2048 \
    -keyout "${KEY_FILE}" \
    -out "${CERT_FILE}" \
    -config "${TMPFILE}" \
    2>/dev/null

# 清理临时文件
rm -f "${TMPFILE}"

echo "✓ 证书已生成："
echo "  证书: ${CERT_FILE}"
echo "  私钥: ${KEY_FILE}"
echo ""
echo "=== 证书信息 ==="
openssl x509 -in "${CERT_FILE}" -noout -subject -dates -ext subjectAltName 2>/dev/null || true
echo ""
echo "=== 使用方法 ==="
echo ""
echo "1. 使用 Caddy 自签名模式 (推荐)："
echo "   将 Caddyfile 替换为 Caddyfile.selfsigned 的内容："
echo "   cp Caddyfile.selfsigned Caddyfile"
echo ""
echo "2. 手动指定证书文件："
echo "   在 Caddyfile 中使用以下配置："
echo ""
echo "   ${DOMAIN} {"
echo "       tls /etc/caddy/certs/photovault.crt /etc/caddy/certs/photovault.key"
echo "       reverse_proxy photovault:8000"
echo "   }"
echo ""
echo "3. 在 docker-compose.yml 的 caddy 服务中挂载证书目录："
echo "   volumes:"
echo "     - ./certs:/etc/caddy/certs:ro"
echo ""
echo "4. 客户端信任证书（可选）："
echo "   - Android: 将 ${CERT_FILE} 安装到设备的受信任证书中"
echo "   - 浏览器: 导入 ${CERT_FILE} 到受信任的根证书列表"
echo ""
