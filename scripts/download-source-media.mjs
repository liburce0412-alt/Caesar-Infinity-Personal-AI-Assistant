import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
const base='artifacts/alicloud-migration-20260908/private'
const source=JSON.parse(fs.readFileSync(base+'/source-data.json','utf8'))
fs.mkdirSync(base+'/media',{recursive:true})
const manifest=[]
for(const [index,object] of source['storage.objects'].entries()) {
  const bucket=source['storage.buckets'].find(b=>b.id===object.bucket_id)
  if(!bucket?.public)throw Error('Private source media requires an authenticated export; stopping.')
  const url='https://mcpjecboqddqelgikvvc.supabase.co/storage/v1/object/public/'+encodeURIComponent(bucket.id)+'/'+object.name.split('/').map(encodeURIComponent).join('/')
  const file=path.resolve(base+'/media/'+index+'.bin')
  execFileSync('curl.exe',['--silent','--show-error','--fail','--max-time','45',url,'--output',file],{stdio:['ignore','ignore','pipe']})
  const bytes=fs.readFileSync(file)
  manifest.push({file:index+'.bin',bucket:bucket.id,name:object.name,owner:object.owner,owner_id:object.owner_id,contentType:object.metadata?.mimetype||'application/octet-stream',size:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex')})
}
fs.writeFileSync(base+'/media/manifest.json',JSON.stringify(manifest,null,2))
console.log('Downloaded and hashed '+manifest.length+' source media files.')
