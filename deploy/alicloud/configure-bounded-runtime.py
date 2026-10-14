#!/usr/bin/env python3
"""Apply the reviewed startup/healthcheck settings to the existing private Compose file.

Run on the server with PyYAML installed. Does not restart services or print secrets.
"""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import yaml

root = Path('/opt/campusai/supabase')
source = root / 'docker-compose.yml'
candidate = root / '.docker-compose-runtime-candidate.yml'
backup = root / 'docker-compose.before-runtime-20261014.yml'
core = ('db', 'rest', 'auth', 'imgproxy', 'api-gw', 'storage', 'realtime')

def resolved(path):
    result = subprocess.run(
        ['docker', 'compose', '-f', str(path), 'config', '--format', 'json'],
        cwd=root, check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)

before = resolved(source)
assert set(before['services']) == set(core), 'Unexpected active services'
config = yaml.safe_load(source.read_text())
for name in core:
    service = config['services'][name]
    assert service['cgroup_parent'] == 'campusai.slice'
    assert service.get('restart') in ('unless-stopped', 'on-failure:5')
    # systemd owns cold startup order; Docker only retries runtime failures.
    service['restart'] = 'on-failure:5'
    health = service['healthcheck']
    assert health.get('test') and not health.get('disable', False)
    health['interval'] = '30s'
    health['start_interval'] = '5s'
    health['start_period'] = '90s'

candidate_created = False
try:
    with candidate.open('x') as handle:
        candidate_created = True
        os.chmod(candidate, 0o600)
        yaml.safe_dump(config, handle, sort_keys=False, allow_unicode=True)
    after = resolved(candidate)
    expected = copy.deepcopy(before)
    for name in core:
        expected['services'][name]['restart'] = 'on-failure:5'
        expected['services'][name]['healthcheck'].update(
            interval='30s', start_interval='5s', start_period='1m30s',
        )
    assert after == expected, 'Unexpected resolved configuration change'
    if not backup.exists():
        shutil.copy2(source, backup)
        os.chmod(backup, 0o600)
    shutil.copymode(source, candidate)
    os.replace(candidate, source)
    print(json.dumps({'configured': True, 'services': list(core),
                      'restart': 'on-failure:5', 'healthIntervalSeconds': 30}))
finally:
    if candidate_created:
        candidate.unlink(missing_ok=True)
