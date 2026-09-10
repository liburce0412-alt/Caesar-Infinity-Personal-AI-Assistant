"""Create/remove a temporary account for verifying the deployed admin UI."""
import base64, hashlib, hmac, json, os, secrets, subprocess, sys, time, urllib.request
from pathlib import Path
root=Path('/opt/campusai/migration')
file=root/'ui-fixture.json'
env=dict(line.split('=',1) for line in Path('/opt/campusai/supabase/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
def sql(query):
    p=subprocess.run(['docker','exec','-i','supabase-db','psql','-U','postgres','-d','postgres','-XAt','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True)
    if p.returncode: raise RuntimeError('Fixture SQL failed')
    return p.stdout.strip()
def cleanup(data):
    user=data['user_id'];note=data['note']
    assert all(c in '0123456789abcdef-' for c in user) and note.startswith('migration-ui-') and note.replace('-','').isalnum()
    sql("begin; delete from private.invitations where note='"+note+"'; delete from auth.users where id='"+user+"' and email='"+note+"@example.invalid'; commit;")
if '--remove' in sys.argv:
    cleanup(json.loads(file.read_text()));file.unlink();print('Removed temporary UI account and invitation.');sys.exit(0)
if file.exists(): raise RuntimeError('A fixture already exists; remove it first')
admin=sql("select id from public.profiles where role='super_admin' and not is_blocked limit 1")
assert admin
def b64(x): return base64.urlsafe_b64encode(x).decode().rstrip('=')
body=b64(b'{"alg":"HS256","typ":"JWT"}')+'.'+b64(json.dumps({'sub':admin,'role':'authenticated','aud':'authenticated','exp':int(time.time())+600}).encode())
token=body+'.'+b64(hmac.new(env['JWT_SECRET'].encode(),body.encode(),hashlib.sha256).digest())
def api(path,payload,jwt=None):
    request=urllib.request.Request('http://127.0.0.1:8000/'+path,data=json.dumps(payload).encode(),headers={'apikey':env['ANON_KEY'],'Authorization':'Bearer '+(jwt or env['ANON_KEY']),'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=30) as response: return json.loads(response.read() or 'null')
note='migration-ui-'+secrets.token_hex(8)
password=secrets.token_urlsafe(24)
invitation=api('rest/v1/rpc/admin_create_invitations',{'count':1,'days':1,'note':note},token)[0]
account=api('auth/v1/signup',{'email':note+'@example.invalid','password':password,'data':{'invite_code':invitation['code']}})['user']
fixture={'email':account['email'],'user_id':account['id'],'password':password,'note':note}
try:
    api('rest/v1/rpc/admin_set_user_role',{'target_user':account['id'],'next_role':'admin'},token)
    with os.fdopen(os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as output: json.dump(fixture,output)
except Exception:
    cleanup(fixture);raise
print('Created a temporary UI verification account; credentials saved privately.')
