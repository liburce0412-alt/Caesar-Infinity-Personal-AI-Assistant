#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/opt/campusai
stamp=$(date -u +%Y%m%dT%H%M%SZ)
destination="$root/backups/$stamp"
mkdir -p "$destination"
docker exec supabase-db pg_dump -U postgres -d postgres -Fc > "$destination/postgres.dump"
docker exec supabase-db pg_dumpall -U postgres --roles-only > "$destination/roles.sql"
tar --xattrs --xattrs-include='*' --acls -czf "$destination/config-and-media.tar.gz" -C "$root/supabase" .env docker-compose.yml volumes/api volumes/storage
if [ -f /etc/systemd/system/campusai.slice ]; then
    tar -czf "$destination/runtime-units.tar.gz" -C / \
        etc/systemd/system/campusai.slice \
        etc/systemd/system/campusai-backend.service \
        opt/campusai/deploy/start-bounded.sh
fi
if [ -f /etc/systemd/system/caesar-cloud-free.service ]; then
    tar -czf "$destination/cloud-free.tar.gz" -C / \
        opt/campusai/cloud-free \
        etc/campusai/cloud-free.env \
        etc/systemd/system/caesar-cloud-free.service \
        etc/nginx/snippets/caesar-cloud-free.conf
fi
volume_path=$(docker volume inspect campusai_db-config --format '{{ .Mountpoint }}')
tar --xattrs --xattrs-include='*' --acls -czf "$destination/db-config.tar.gz" -C "$volume_path" .
tar -czf "$destination/admin.tar.gz" -C /var/www campusai-admin
if [ -d /etc/letsencrypt/live/campusai.campus3ai.xyz ]; then
    tar -czf "$destination/certificates.tar.gz" -C /etc letsencrypt
fi
cp /etc/nginx/sites-available/campusai "$destination/nginx.conf"
(cd "$destination" && sha256sum postgres.dump roles.sql *.tar.gz nginx.conf > SHA256SUMS)
chmod -R go-rwx "$destination"
printf '%s\n' "$destination"
