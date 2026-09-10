// Verify the HTTPS origin from the user's computer, including before DNS cutover.
import fs from 'node:fs'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'

const destination = process.argv[2]
if (destination && !['47.243.49.228'].includes(destination)) throw Error('Unreviewed migration destination')
const base = 'artifacts/alicloud-migration-20260908'
const env = Object.fromEntries(fs.readFileSync('apps/admin/.env.local','utf8').trim().split(/\r?\n/).map(line=>{const i=line.indexOf('=');return [line.slice(0,i),line.slice(i+1)]}))
const host = 'campusai.campus3ai.xyz'
const checks = {}
function request(path, authenticated=false, payload) {
  const args=['--noproxy','*','--silent','--show-error','--max-time','20','--write-out','%{http_code}']
  if(destination) args.push('--resolve',`${host}:443:${destination}`)
  if(authenticated) args.push('--header','apikey: '+env.VITE_SUPABASE_ANON_KEY,'--header','Authorization: Bearer '+env.VITE_SUPABASE_ANON_KEY)
  if(payload!==undefined) args.push('--header','Content-Type: application/json','--data',JSON.stringify(payload))
  args.push('https://'+host+path)
  const result=spawnSync('curl.exe',args,{maxBuffer:20*1024*1024})
  if(result.status!==0) throw Error('HTTPS request failed with curl exit '+result.status)
  return {status:Number(result.stdout.subarray(-3).toString()),body:result.stdout.subarray(0,-3)}
}
function check(name,passed) { checks[name]=Boolean(passed);if(!passed)throw Error(name+' failed') }
try {
  const login=request('/login')
  check('admin_document',login.status===200 && login.body.toString().includes('id="root"'))
  for(const asset of login.body.toString().matchAll(/(?:src|href)="(\/assets\/[^\"]+)"/g)) check('asset_'+asset[1],request(asset[1]).status===200)
  check('auth_health',request('/auth/v1/health',true).status===200)
  check('anonymous_admin_denied',[401,403].includes(request('/rest/v1/rpc/admin_me',true,{}).status))
  for(const path of ['/pg/meta','/api/platform/profile']) check('studio_private_'+path,request(path).status===404)
  for(const item of JSON.parse(fs.readFileSync(base+'/private/media/manifest.json','utf8'))) {
    const response=request('/storage/v1/object/public/'+encodeURIComponent(item.bucket)+'/'+item.name.split('/').map(encodeURIComponent).join('/'))
    check('media_'+item.file,response.status===200 && crypto.createHash('sha256').update(response.body).digest('hex')===item.sha256)
  }
  console.log('Passed '+Object.keys(checks).length+' external HTTPS, asset, authorization and stored-object checks.')
} finally {
  fs.writeFileSync(base+`/hongkong-public-${destination?'origin':'dns'}.json`,JSON.stringify(checks,null,2))
}
