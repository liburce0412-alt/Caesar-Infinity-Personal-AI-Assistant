"""Prepare a fresh, loopback-only Supabase deployment from the pinned upstream bundle."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import time
import yaml

root = Path('/opt/campusai/supabase')
os.chdir(root)
if (root / '.env').exists():
    raise SystemExit('Refusing to replace an existing deployment environment')
values = {}
for line in (root / '.env.example').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        values[key] = value.strip('"')
for key, size in [('POSTGRES_PASSWORD', 32), ('JWT_SECRET', 32), ('DASHBOARD_PASSWORD', 24),
                  ('SECRET_KEY_BASE', 48), ('REALTIME_DB_ENC_KEY', 8), ('VAULT_ENC_KEY', 16),
                  ('PG_META_CRYPTO_KEY', 32), ('LOGFLARE_PUBLIC_ACCESS_TOKEN', 32),
                  ('LOGFLARE_PRIVATE_ACCESS_TOKEN', 32), ('S3_PROTOCOL_ACCESS_KEY_ID', 16),
                  ('S3_PROTOCOL_ACCESS_KEY_SECRET', 32)]:
    values[key] = secrets.token_hex(size)
def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')
def token(role):
    now = int(time.time())
    body = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode()) + '.' + b64(json.dumps(
        {'role': role, 'iss': 'supabase', 'iat': now, 'exp': now + 10 * 365 * 86400}).encode())
    return body + '.' + b64(hmac.new(values['JWT_SECRET'].encode(), body.encode(), hashlib.sha256).digest())
values.update(ANON_KEY=token('anon'), SERVICE_ROLE_KEY=token('service_role'),
              SUPABASE_PUBLIC_URL='https://campusai.campus3ai.xyz',
              API_EXTERNAL_URL='https://campusai.campus3ai.xyz/auth/v1',
              SITE_URL='https://campusai.campus3ai.xyz', DISABLE_SIGNUP='true',
              ENABLE_EMAIL_AUTOCONFIRM='true', ENABLE_PHONE_SIGNUP='false',
              ENABLE_ANONYMOUS_USERS='false', OPENAI_API_KEY='',
              SMTP_HOST='', SMTP_USER='', SMTP_PASS='', SMTP_ADMIN_EMAIL='',
              STUDIO_DEFAULT_ORGANIZATION='CampusAI', STUDIO_DEFAULT_PROJECT='CampusAI Hangzhou',
              POOLER_TENANT_ID='campusai', COMPOSE_FILE='docker-compose.yml')
fd = os.open(root / '.env', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'w') as file:
    file.write('\n'.join(f'{k}={v}' for k, v in values.items()) + '\n')
compose = yaml.safe_load((root / 'docker-compose.yml').read_text())
compose['name'] = 'campusai'
# No source Edge Functions are deployed; direct Postgres access uses SSH.
for unused in ['functions', 'supavisor']:
    compose['services'].pop(unused, None)
compose['services']['api-gw']['ports'] = ['127.0.0.1:8000:8000']
for service in compose['services'].values():
    service['logging'] = {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}}
    service.get('depends_on', {}).pop('functions', None)
    service.get('depends_on', {}).pop('supavisor', None)
(root / 'docker-compose.yml').write_text(yaml.safe_dump(compose, sort_keys=False))
print('Prepared pinned Supabase services with private database/API bindings; signup disabled during import.')
