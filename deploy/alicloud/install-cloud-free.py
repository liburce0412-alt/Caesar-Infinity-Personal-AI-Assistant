#!/usr/bin/env python3
"""Install the reviewed relay files from a JSON bundle on the existing Caesar host.

Run as root over SSH. No provider key is accepted in the bundle or printed.
Existing secrets remain in /etc/campusai/cloud-free.env; create an unconfigured
service when no ZHIPU_API_KEY has been provisioned yet.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()


def save(path, text, mode=0o644):
    target = Path(path)
    temp = target.with_name(target.name + '.cloud-free-tmp')
    temp.write_text(text, encoding='utf-8')
    temp.chmod(mode)
    temp.replace(target)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('Run as root')
    bundle = json.load(sys.stdin)
    expected = {'server.mjs', 'relay.mjs', 'package.json', 'caesar-cloud-free.service', 'caesar-cloud-free.nginx.conf'}
    if set(bundle) != expected or any(not isinstance(v, str) for v in bundle.values()):
        raise ValueError('Unexpected deployment files')
    node = shutil.which('node')
    if node != '/usr/local/bin/node':
        raise RuntimeError('Review the service Node path for this host first')
    nginx = Path('/etc/nginx/sites-available/campusai')
    original = nginx.read_text()
    marker = '    # Caesar cloud free streaming relay\n    include /etc/nginx/snippets/caesar-cloud-free.conf;\n'
    anchor = '    # Caesar verified APK mirror\n'
    if marker not in original and original.count(anchor) != 1:
        raise RuntimeError('Review current Caesar nginx config before installation')
    Path('/opt/campusai/cloud-free').mkdir(mode=0o755, parents=True, exist_ok=True)
    Path('/etc/campusai').mkdir(mode=0o700, parents=True, exist_ok=True)
    Path('/etc/nginx/snippets').mkdir(mode=0o755, parents=True, exist_ok=True)
    for name in ('server.mjs', 'relay.mjs', 'package.json'):
        save('/opt/campusai/cloud-free/' + name, bundle[name])
    run(node, '--check', '/opt/campusai/cloud-free/server.mjs')
    run(node, '--check', '/opt/campusai/cloud-free/relay.mjs')
    env = Path('/etc/campusai/cloud-free.env')
    if not env.exists():
        source = Path('/opt/campusai/supabase/.env').read_text()
        match = re.search(r'^ANON_KEY=(.+)$', source, re.M)
        if not match:
            raise RuntimeError('Existing public anon key missing')
        anon = match.group(1).strip().strip('\"\'')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', anon):
            raise RuntimeError('Unexpected anon key format')
        save(env, 'CAESAR_AUTH_URL=http://127.0.0.1:8000\nSUPABASE_ANON_KEY=' + anon +
             '\nCAESAR_CLOUD_CONCURRENCY=1\nZHIPU_API_KEY=\n', 0o600)
    save('/etc/systemd/system/caesar-cloud-free.service', bundle['caesar-cloud-free.service'])
    save('/etc/nginx/snippets/caesar-cloud-free.conf', bundle['caesar-cloud-free.nginx.conf'])
    if marker not in original:
        recovery = Path('/etc/campusai/campusai-nginx.before-cloud-free')
        if not recovery.exists():
            save(recovery, original, 0o600)
        save(nginx, original.replace(anchor, marker + anchor))
    try:
        run('nginx', '-t')
    except subprocess.CalledProcessError:
        save(nginx, original)
        raise RuntimeError('nginx validation failed; original config restored') from None
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', 'caesar-cloud-free.service')
    run('systemctl', 'restart', 'caesar-cloud-free.service')
    run('systemctl', 'reload', 'nginx')
    print(json.dumps({'installed': True, 'service': run('systemctl', 'is-active', 'caesar-cloud-free.service'),
                      'provider_key_configured': bool(re.search(r'^ZHIPU_API_KEY=\S+', env.read_text(), re.M))}))


if __name__ == '__main__':
    main()
