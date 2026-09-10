"""Exercise the new private deployment. Never prints credentials or account data."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

root=Path('/opt/campusai')
env=dict(line.split('=',1) for line in (root/'supabase/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
base=os.environ.get('CAMPUSAI_TEST_BASE_URL','http://127.0.0.1:8000')
assert base in ('http://127.0.0.1:8000','https://campusai.campus3ai.xyz')
def sql(query):
    p=subprocess.run(['docker','exec','-i','supabase-db','psql','-U','postgres','-d','postgres','-X','-A','-t','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True)
    if p.returncode: raise RuntimeError('Smoke SQL failed')
    return p.stdout.strip()
def request(path,payload=None,token=None,method=None):
    headers={'apikey':env['ANON_KEY'],'Content-Type':'application/json','Authorization':'Bearer '+(token or env['ANON_KEY'])}
    req=urllib.request.Request(base+'/'+path,data=json.dumps(payload).encode() if payload is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as res: return res.status,json.loads(res.read() or 'null')
    except urllib.error.HTTPError as e:
        body=e.read()
        try: result=json.loads(body)
        except ValueError: result={}
        return e.code,result
def b64(data): return base64.urlsafe_b64encode(data).decode().rstrip('=')
admin=sql("select id from public.profiles where role='super_admin' and not is_blocked order by created_at limit 1")
assert admin,'Existing super admin required'
claims={'sub':admin,'role':'authenticated','aud':'authenticated','iat':int(time.time()),'exp':int(time.time())+600}
body=b64(json.dumps({'alg':'HS256','typ':'JWT'}).encode())+'.'+b64(json.dumps(claims).encode())
admin_token=body+'.'+b64(hmac.new(env['JWT_SECRET'].encode(),body.encode(),hashlib.sha256).digest())
prefix='migration-'+secrets.token_hex(8)
password=secrets.token_urlsafe(24)
results={}
def check(name,condition):
    results[name]=bool(condition)
    if not condition: raise AssertionError(name)
try:
    status,me=request('rest/v1/rpc/admin_me',{},admin_token)
    check('admin_identity',status==200 and me.get('id')==admin)
    for kind in ['users','invites','content','listings','orders','reports','announcements','releases','audit']:
        status,data=request('rest/v1/rpc/admin_records',{'kind':kind},admin_token)
        check('admin_page_'+kind,status==200 and isinstance(data.get('rows'),list) and isinstance(data.get('total'),int))
    status,data=request('rest/v1/rpc/admin_overview',{},admin_token)
    check('overview_7_days',status==200 and len(data.get('trend',[]))==7)
    status,invites=request('rest/v1/rpc/admin_create_invitations',{'count':3,'days':1,'note':prefix},admin_token)
    check('invitation_creation',status==200 and len(invites)==3)
    codes=[item['code'] for item in invites]
    status,_=request('auth/v1/signup',{'email':prefix+'-invalid@example.invalid','password':password,'data':{'invite_code':'INVALID'}})
    check('invalid_invitation_rejected',status>=400)
    check('invalid_signup_atomic',sql("select count(*) from auth.users where email='"+prefix+"-invalid@example.invalid'")=='0')
    def signup(index):
        return request('auth/v1/signup',{'email':prefix+'-'+str(index)+'@example.invalid','password':password,'data':{'invite_code':codes[0]}})
    with ThreadPoolExecutor(max_workers=2) as pool:
        attempts=list(pool.map(signup,[0,1]))
    successes=[data for status,data in attempts if status==200 and data.get('access_token')]
    check('single_use_under_concurrency',len(successes)==1)
    session=successes[0]
    check('invite_not_persisted_in_user_metadata',sql("select not(raw_user_meta_data ? 'invite_code') from auth.users where id='"+session['user']['id']+"'")=='t')
    status,_=request('auth/v1/token?grant_type=password',{'email':session['user']['email'],'password':password})
    check('password_login',status==200)
    status,refreshed=request('auth/v1/token?grant_type=refresh_token',{'refresh_token':session['refresh_token']})
    check('session_refresh',status==200 and bool(refreshed.get('access_token')))
    for rpc,payload in [('admin_me',{}),('admin_records',{'kind':'users'}),('admin_create_invitations',{'count':1,'days':1}),('admin_overview',{})]:
        status,_=request('rest/v1/rpc/'+rpc,payload,refreshed['access_token'])
        check('student_denied_'+rpc,status==403)
    check('student_profile_endpoint',request('rest/v1/profiles?select=id',token=refreshed['access_token'])[0]==200)
    ids=json.loads(sql("select json_agg(id order by created_at,id) from private.invitations where note='"+prefix+"'"))
    unused=json.loads(sql("select json_agg(id) from private.invitations where note='"+prefix+"' and used_at is null"))
    status,_=request('rest/v1/rpc/admin_revoke_invitation',{'target_invitation':unused[0]},admin_token)
    check('invitation_revoked',status in (200,204))
    sql("update private.invitations set expires_at=now()-interval '1 second' where id='"+unused[1]+"'")
    for index,code in enumerate(codes[1:]):
        status,_=request('auth/v1/signup',{'email':prefix+'-closed'+str(index)+'@example.invalid','password':password,'data':{'invite_code':code}})
        check('unavailable_invitation_'+str(index),status>=400)
    check('no_failed_registration_accounts',sql("select count(*) from auth.users where email like '"+prefix+"%@example.invalid'")=='1')
    print('Passed '+str(len(results))+' API and invitation checks.')
finally:
    # Remove only synthetic users and invitation records created by this run.
    sql("begin; delete from private.invitations where note='"+prefix+"'; delete from auth.users where email like '"+prefix+"%@example.invalid'; commit;")
    (root/('migration/smoke-public-verification.json' if base.startswith('https:') else 'migration/smoke-verification.json')).write_text(json.dumps(results,indent=2))
