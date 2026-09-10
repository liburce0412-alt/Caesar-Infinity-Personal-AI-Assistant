"""Import exported objects through Storage, verify bytes, and preserve ownership."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
from urllib.parse import quote

root = Path('/opt/campusai/migration/media')
env = dict(line.split('=', 1) for line in Path('/opt/campusai/supabase/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
headers = {'apikey': env['SERVICE_ROLE_KEY'], 'Authorization': 'Bearer ' + env['SERVICE_ROLE_KEY']}
results = []
for item in json.loads((root / 'manifest.json').read_text()):
    data = (root / item['file']).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item['sha256']
    url = 'http://127.0.0.1:8000/storage/v1/object/' + quote(item['bucket'], safe='') + '/' + quote(item['name'], safe='/')
    request = urllib.request.Request(url, data=data, method='POST', headers={**headers, 'Content-Type': item['contentType'], 'x-upsert': 'true'})
    with urllib.request.urlopen(request, timeout=60) as response:
        assert response.status in (200, 201)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        assert hashlib.sha256(response.read()).hexdigest() == item['sha256']
    def literal(value):
        return 'NULL' if value is None else "'" + str(value).replace("'", "''") + "'"
    query = 'update storage.objects set owner=' + literal(item['owner']) + ',owner_id=' + literal(item['owner_id']) + ' where bucket_id=' + literal(item['bucket']) + ' and name=' + literal(item['name']) + ';'
    result = subprocess.run(['docker','exec','-i','supabase-db','psql','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True)
    if result.returncode or 'UPDATE 1' not in result.stdout:
        raise RuntimeError('Could not restore object ownership')
    results.append({'file':item['file'],'sha256':item['sha256'],'verified':True})
(root.parent / 'media-verification.json').write_text(json.dumps(results,indent=2))
print(f'Uploaded and byte-verified {len(results)} objects; original ownership restored.')
