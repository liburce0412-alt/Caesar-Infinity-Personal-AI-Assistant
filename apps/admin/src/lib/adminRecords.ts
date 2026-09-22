import { api, backend } from './backend'

export type Kind = 'users'|'content'|'listings'|'orders'|'reports'|'announcements'|'releases'|'audit'
export type AdminRecord = { id:string; cells:string[]; raw:Record<string,unknown> }
export type ActionSpec = { key:string; label:string; detail:string; requiresNote?:boolean }
export type Operation =
  | { type:'action'; kind:Kind; record:AdminRecord; action:ActionSpec; note:string }
  | { type:'create'; kind:'announcements'|'releases'; values:Record<string,string> }

export function actionsFor(kind:Kind,record:AdminRecord,role:string="super_admin"):ActionSpec[]{
  if(!["admin","super_admin"].includes(role)&&["users","announcements","releases","audit"].includes(kind))return []
  if(kind==="orders"&&!["admin","super_admin"].includes(role))return []
  const status=String(kind==='content'||kind==='listings'?record.raw.moderation_status??'':record.raw.status??'')
  if(kind==='users')return [{key:record.raw.is_blocked?'unblock':'block',label:record.raw.is_blocked?'解除限制':'限制账户',detail:record.raw.is_blocked?'恢复该用户的校园服务访问。':'立即阻止该用户继续访问受保护业务。'}]
  if(kind==='content'||kind==='listings'){
    if(status==='pending')return [{key:'approve',label:'通过审核',detail:'内容将对符合条件的用户可见。'},{key:'reject',label:'拒绝内容',detail:'内容保留记录，但不会公开展示。'}]
    if(status==='approved')return [{key:'remove',label:'移除内容',detail:'内容将停止公开展示，历史和审计记录仍保留。'}]
  }
  if(kind==='orders'&&status==='disputed')return [{key:'complete_order',label:'裁定完成',detail:'订单将完成，商品标记为已售。'},{key:'cancel_order',label:'裁定取消',detail:'订单将取消，商品恢复为在售。'}]
  if(kind==='reports'&&status==='pending')return [{key:'resolve_report',label:'确认并解决',detail:'记录处理说明并关闭举报。',requiresNote:true},{key:'dismiss_report',label:'驳回举报',detail:'说明驳回原因并关闭举报。',requiresNote:true}]
  if(kind==='announcements')return status==='published'?[{key:'withdraw_announcement',label:'撤回公告',detail:'公告将不再对用户展示。'}]:[{key:'publish_announcement',label:'发布公告',detail:'公告将立即面向设定受众展示。'}]
  if(kind==='releases')return status==='published'?[{key:'archive_release',label:'归档版本',detail:'版本保留记录，但不再作为当前发布展示。'}]:status==='draft'?[{key:'publish_release',label:'发布版本',detail:'版本将进入客户端发布列表。'}]:[]
  return []
}

export async function executeOperation(operation:Operation){
  if(!backend)throw new Error('管理台尚未连接 Supabase。')
  if(operation.type==='create'){
    operation.kind==='announcements'
      ?await api.rpc('admin_create_announcement',{announcement_title:operation.values.title,announcement_body:operation.values.body})
      :await api.rpc('admin_create_release',{release_version_code:Number(operation.values.version_code),release_version_name:operation.values.version_name,release_notes:operation.values.notes,release_apk_url:operation.values.apk_url,release_checksum_sha256:operation.values.checksum})
    return
  }
  const {kind,record,action,note}=operation
  let result:unknown=null
  if(kind==='users')result=action.key.startsWith('role:')?await api.rpc('admin_set_user_role',{target_user:record.id,next_role:action.key.slice(5)}):await api.rpc('admin_set_user_blocked',{target_user:record.id,blocked:action.key==='block'})
  else if(kind==='content'||kind==='listings')result=await api.rpc('moderate_content',{target_type:kind==='listings'?'listing':String(record.raw.content_type??'post'),target_id:record.id,next_status:action.key==='approve'?'approved':action.key==='reject'?'rejected':'removed'})
  else if(kind==='orders')result=await api.rpc('transition_order',{target_order:record.id,expected_version:Number(record.raw.version),next_status:action.key==='complete_order'?'completed':'cancelled'})
  else if(kind==='reports')result=await api.rpc('resolve_report',{target_report:record.id,next_status:action.key==='resolve_report'?'resolved':'dismissed',resolution_text:note})
  else if(kind==='announcements')result=await api.rpc('admin_set_announcement_status',{target_announcement:record.id,next_status:action.key==='publish_announcement'?'published':'withdrawn'})
  else if(kind==='releases')result=await api.rpc('admin_set_release_status',{target_release:record.id,next_status:action.key==='publish_release'?'published':'archived'})
  return result
}

export function toRecord(kind:Kind,item:Record<string,unknown>):AdminRecord{
  const date=formatDate(kind==='users'?item.created_at:item.publish_at??item.published_at??item.created_at)
  const short=(value:unknown)=>String(value??'—').slice(0,12)
  if(typeof item.id !== 'string' || !item.id) throw new Error('记录缺少有效 ID。')
  const id=item.id
  if(kind==='users')return {id,raw:item,cells:[`${item.display_name??'未命名'} / ${item.email??item.handle??'—'}`,({student:'普通用户',moderator:'审核员',admin:'管理员',super_admin:'超级管理员'} as Record<string,string>)[String(item.role)]??String(item.role??'普通用户'),item.is_blocked?'受限':'正常',date]}
  if(kind==='content')return {id,raw:item,cells:[`${item.content_type==='listing_comment'?'心愿评论':item.content_type==='comment'?'评论':'帖子'} · ${String(item.body??'').slice(0,38)}`,short(item.author_id),statusLabel(item.moderation_status),date]}
  if(kind==='listings')return {id,raw:item,cells:[String(item.title??'未命名'),short(item.seller_id),statusLabel(item.moderation_status),item.price_cents == null ? '未设价格' : money(item.price_cents)]}
  if(kind==='orders')return {id,raw:item,cells:[short(item.id),`${short(item.buyer_id)} / ${short(item.seller_id)}`,statusLabel(item.status),money(item.price_cents)]}
  if(kind==='reports')return {id,raw:item,cells:[`${item.target_type??'内容'} / ${short(item.target_id)}`,short(item.reporter_id),statusLabel(item.status),date]}
  if(kind==='announcements')return {id,raw:item,cells:[String(item.title??'未命名'),'全部用户',statusLabel(item.status),date]}
  if(kind==='releases')return {id,raw:item,cells:[String(item.version_name??'—'),String(item.version_code??'—'),statusLabel(item.status),date]}
  return {id,raw:item,cells:[short(item.actor_id),String(item.action??'—'),statusLabel(item.result),date]}
}

export function downloadCsv(title:string,rows:AdminRecord[],headers:string[]=[]){const content=[...(headers.length?[headers]:[]),...rows.map(row=>row.cells)].map(cells=>cells.map(raw=>{const value=/^[=+@\-\t\r]/.test(raw)?"'"+raw:raw;return `"${value.replaceAll('"','""')}"`}).join(',')).join('\n');const url=URL.createObjectURL(new Blob([`\uFEFF${content}`],{type:'text/csv;charset=utf-8'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=`${title}.csv`;anchor.click();URL.revokeObjectURL(url)}
export function money(value:unknown){const cents=Number(value);return Number.isFinite(cents)?`¥${(cents/100).toFixed(2)}`:'—'}
export function formatDate(value:unknown){if(!value)return '—';const date=new Date(String(value));return Number.isNaN(date.valueOf())?'—':date.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}
export function statusLabel(value:unknown){const labels:Record<string,string>={pending:'待处理',approved:'已通过',rejected:'已拒绝',removed:'已移除',resolved:'已完成',dismissed:'已驳回',active:'在售',reserved:'已预订',sold:'已售',withdrawn:'已下架',pending_payment:'待付款',paid:'已付款',meeting:'待交付',completed:'已完成',cancelled:'已取消',disputed:'争议中',draft:'草稿',scheduled:'定时',published:'已发布',archived:'已归档',success:'成功',retry:'重试'};return labels[String(value)]??String(value??'—')}
