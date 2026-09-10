#!/bin/bash
set -euo pipefail
cd /opt/campusai/supabase
# This SSH session forwards an existing local proxy to server loopback only.
# The daemon override exists only while fetching official upstream images.
mkdir -p /run/systemd/system/docker.service.d
test ! -e /run/systemd/system/docker.service.d/campusai-download-proxy.conf
finish() {
  rm -f /run/systemd/system/docker.service.d/campusai-download-proxy.conf
  systemctl daemon-reload
  systemctl restart docker
}
trap finish EXIT
cat > /run/systemd/system/docker.service.d/campusai-download-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:18080" "HTTPS_PROXY=http://127.0.0.1:18080" "NO_PROXY=localhost,127.0.0.1"
EOF
systemctl daemon-reload
systemctl restart docker
docker compose pull --quiet
docker compose images
