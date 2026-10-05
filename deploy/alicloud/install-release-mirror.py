#!/usr/bin/env python3
"""Install the static release mirror on the existing Hangzhou host (run as root)."""
from pathlib import Path
import shutil
import subprocess

source = Path(__file__).resolve().parent
target = Path("/opt/campusai/deploy")
target.mkdir(parents=True, exist_ok=True)
runtime = Path("/usr/local/lib/caesar-updater")
runtime.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source / "sync-app-releases.py", runtime / "sync-app-releases.py")
for name in ("caesar-release-mirror.service", "caesar-release-mirror.timer"):
    shutil.copyfile(source / name, Path("/etc/systemd/system") / name)
root = Path("/var/www/campusai-updates")
root.mkdir(parents=True, exist_ok=True)
shutil.chown(root, "www-data", "www-data")
config = Path("/etc/nginx/sites-enabled/campusai").resolve()
original = config.read_text()
marker = "    # Caesar verified APK mirror"
if marker not in original:
    block = '''    # Caesar verified APK mirror
    location = /updates/latest.json {
        alias /var/www/campusai-updates/latest.json;
        default_type application/json;
        add_header Cache-Control "no-store" always;
    }
    location ~ ^/updates/releases/(v[0-9]+\\.[0-9]+\\.[0-9]+)/(caesar-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk)$ {
        alias /var/www/campusai-updates/releases/$1/$2;
        default_type application/vnd.android.package-archive;
        add_header Cache-Control "public, max-age=604800, immutable";
    }
    location /updates/ { return 404; }
'''
    anchor = "    # Studio, metadata and database administration remain behind SSH."
    if original.count(anchor) != 1:
        raise RuntimeError("Unexpected nginx layout; no configuration changed")
    backup = target / "nginx-before-app-updates.conf"
    if not backup.exists():
        backup.write_text(original)
    config.write_text(original.replace(anchor, block + anchor))
    check = subprocess.run(["nginx", "-t"])
    if check.returncode:
        config.write_text(original)
        raise RuntimeError("nginx validation failed; configuration restored")
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
subprocess.run(["systemctl", "daemon-reload"], check=True)
subprocess.run(["systemctl", "enable", "--now", "caesar-release-mirror.timer"], check=True)
print("Mirror installed; existing backend and other virtual hosts unchanged")
