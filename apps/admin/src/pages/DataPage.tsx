import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type ChangeEvent, useState } from 'react'
import { Symbol } from '../components/BrandMark'
import { api, backend, currentAdmin } from '../lib/backend'
import { Modal } from '../components/Modal'

type Kind = 'users'|'content'|'listings'|'orders'|'reports'|'announcements'|'releases'|'audit'
type AdminRecord = { id:string; cells:string[]; raw:Record<string,unknown> }
type ActionSpec = { key:string; label:string; detail:string; requiresNote?:boolean }
type Operation =
  | { type:'action'; kind:Kind; record:AdminRecord; action:ActionSpec; note:string }
  | { type:'create'; kind:'announcements'|'releases'; values:Record<string,string> }

const config:Record<Kind,{eyebrow:string;title:string;description:string;action:string;columns:string[]}>={
  users:{eyebrow:'IDENTITY / ACCESS',title:'用户',description:'管理账户状态与角色，查看邀请码注册来源。',action:'导出用户',columns:['用户','角色','状态','注册时间']},
  content:{eyebrow:'COMMUNITY / CONTENT',title:'帖子与评论',description:'按风险和时间处理内容，不让审核离开上下文。',action:'导出内容',columns:['内容','作者','审核','发布时间']},
  listings:{eyebrow:'WISHES',title:'心愿',description:'查看心愿内容与审核状态，保留每一次期待。',action:'导出列表',columns:['心愿','记录者','审核','预算']},
  orders:{eyebrow:'MARKET / ORDERS',title:'订单',description:'从创建到完成的节点轨迹，以及需要人工介入的争议。',action:'导出订单',columns:['订单','买卖双方','进度','金额']},
  reports:{eyebrow:'TRUST / SAFETY',title:'举报审核',description:'查看举报上下文，记录处理说明，跟进每一次社区反馈。',action:'导出举报',columns:['举报对象','举报人','状态','提交时间']},
  announcements:{eyebrow:'OPERATIONS / NOTICE',title:'公告',description:'创建和编辑公告草稿，发布或撤回面向全体用户的公告。',action:'新建公告',columns:['标题','受众','状态','发布时间']},
  releases:{eyebrow:'DELIVERY / RELEASE',title:'版本发布',description:'管理 Android 版本草稿、下载地址和校验和，发布或归档版本。',action:'新建版本',columns:['版本','构建','状态','发布时间']},
  audit:{eyebrow:'SECURITY / AUDIT',title:'审计日志',description:'按操作者、资源与动作检索服务端审计事件。',action:'导出日志',columns:['操作者','动作','结果','发生时间']},
}

const sampleCells:Record<Kind,string[][]>={
  users:[['林屿 / lin@example.edu','student','正常','2 分钟前'],['陈默 / chen@example.edu','moderator','正常','18 分钟前'],['匿名账户 A19','student','受限','1 小时前']],
  content:[['图书馆三楼学习搭子','匿名同学','待处理','12 分钟前'],['二手群外链评论','林屿','已通过','36 分钟前'],['课程资料分享','陈默','已通过','1 小时前']],
  listings:[['去看一次海','林屿','已通过','未设价格'],['等一场日出','陈默','待处理','未设价格'],['一次周末露营','南苑同学','已通过','¥55']],
  orders:[['CA-0822-031','林屿 / 北区学生','待交付','¥168'],['CA-0821-116','陈默 / 数学系同学','已完成','¥28'],['CA-0820-098','匿名 A19 / 南苑 5 栋','争议中','¥55']],
  reports:[['帖子 #P-3821','用户 L17','待处理','8 分钟前'],['商品 #G-992','匿名用户','待处理','21 分钟前'],['评论 #C-811','用户 R04','已完成','1 小时前']],
  announcements:[['新学期数据同步说明','全部用户','已发布','今天 08:00'],['图书馆服务调整','全部用户','定时','明天 07:30'],['市场交易提醒','全部用户','草稿','—']],
  releases:[['1.2.0','120 / a8f2','草稿','—'],['1.1.4','114 / e921','已发布','8月12日'],['1.1.3','113 / b20d','已归档','7月28日']],
  audit:[['moderator@campus.ai','APPROVE_REPORT','成功','2 分钟前'],['admin@campus.ai','PUBLISH_NOTICE','成功','18 分钟前'],['system','SYNC_ORDER','重试','26 分钟前']],
}
const samples = Object.fromEntries(Object.entries(sampleCells).map(([kind, rows])=>[kind,rows.map((cells,index)=>({id:`sample-${kind}-${index}`,cells,raw:{}}))])) as Record<Kind,AdminRecord[]>

export function DataPage({kind}:{kind:Kind}) {
  const page=config[kind],cache=useQueryClient()
  const [selected,setSelected]=useState<Set<string>>(new Set()),[search,setSearch]=useState(''),[status,setStatus]=useState(''),[ascending,setAscending]=useState(false),[pageIndex,setPageIndex]=useState(0)
  const [detail,setDetail]=useState<AdminRecord|null>(null),[pending,setPending]=useState<{record:AdminRecord;action:ActionSpec}|null>(null),[createOpen,setCreateOpen]=useState(false),[notice,setNotice]=useState(''),[edit,setEdit]=useState<AdminRecord|null>(null)
  const who=useQuery({queryKey:['admin-me'],queryFn:currentAdmin}),role=who.data?.role||''
  const allowed=!['users','announcements','releases','audit'].includes(kind)||['admin','super_admin'].includes(role)
  const query=useQuery({queryKey:['admin-data',kind,pageIndex,search,status,ascending],enabled:allowed,queryFn:async()=>{
    if(!backend){const rows=samples[kind].filter(r=>r.cells.join(' ').toLowerCase().includes(search.toLowerCase()));return {rows,total:rows.length}}
    const result=await api.get('/admin/records?'+new URLSearchParams({kind,page:String(pageIndex),search,status,ascending:String(ascending)}))
    return {rows:result.rows.map((r:Record<string,unknown>)=>toRecord(kind,r)) as AdminRecord[],total:result.total as number}
  }})
  const rows=query.data?.rows||[],total=query.data?.total||0
  const operation=useMutation({mutationFn:executeOperation,onSuccess:async()=>{setPending(null);setCreateOpen(false);setDetail(null);setSelected(new Set());setNotice('操作已完成。');await cache.invalidateQueries({queryKey:['admin-data']});await cache.invalidateQueries({queryKey:['admin-overview']})}})
  const draft=useMutation({mutationFn:(values:Record<string,string>)=>api.rpc('admin_save_draft',{kind,id:edit!.id,values}),onSuccess:async()=>{setEdit(null);setDetail(null);setNotice('草稿已保存。');await cache.invalidateQueries({queryKey:['admin-data',kind]})}})
  const changePage=(next:number)=>{setPageIndex(next);setSelected(new Set())}
  const statuses=kind==='users'?['active','blocked']:kind==='content'||kind==='listings'?['pending','approved','rejected','removed']:kind==='orders'?['pending_payment','paid','meeting','disputed','completed','cancelled']:kind==='reports'?['pending','resolved','dismissed']:kind==='announcements'?['draft','published','withdrawn']:kind==='releases'?['draft','published','archived']:['success','retry']
  return <>
    <div className="page-header"><div><div className="eyebrow">{page.eyebrow}</div><h1>{page.title}</h1><p className="muted">{page.description}</p></div><button className="pill-button primary" disabled={!backend||!allowed} onClick={()=>{operation.reset();if(kind==='announcements'||kind==='releases')setCreateOpen(true);else downloadCsv(`${page.title}-当前页`,rows,page.columns)}}><Symbol>{kind==='announcements'||kind==='releases'?'add':'download'}</Symbol>{kind==='announcements'||kind==='releases'?page.action:'导出当前页'}</button></div>
    {!backend&&<p className="muted">当前为示例预览，写入操作不可用。</p>}
    {who.data&&!allowed&&<p role="alert">此页面仅限管理员访问。</p>}
    <div className="filters"><label className="search-field glass"><Symbol>search</Symbol><input aria-label="搜索当前结果" placeholder="搜索全部记录" value={search} onChange={e=>{setSearch(e.target.value);changePage(0)}}/></label><select className="pill-button" aria-label="筛选状态" value={status} onChange={e=>{setStatus(e.target.value);changePage(0)}}><option value="">全部状态</option>{statuses.map(s=><option key={s} value={s}>{kind==='users'?(s==='active'?'正常':'受限'):statusLabel(s)}</option>)}</select><button className="pill-button" onClick={()=>{setAscending(v=>!v);changePage(0)}}>{ascending?'较早优先':'最近优先'}</button><button className="pill-button" onClick={()=>void query.refetch()} disabled={query.isFetching}>刷新</button></div>
    {(query.error||who.error)&&<div className="error-bar" role="alert">读取失败：{(query.error||who.error)?.message}。<button className="pill-button" onClick={()=>void query.refetch()}>重试</button></div>}
    {notice&&<p className="success-notice" role="status">{notice}</p>}
    <div className="table-panel glass strong" aria-busy={query.isFetching}><div className="table-summary"><strong>{search||status?'筛选结果':'全部记录'} <span className="badge">{query.data?total:'—'}</span></strong><span className="muted">{query.isFetching?'正在同步…':'点击记录查看详情'}</span></div><div className="table-row header"><input className="check" type="checkbox" aria-label="选择当前页全部记录" disabled={!rows.length} checked={rows.length>0&&rows.every(row=>selected.has(row.id))} onChange={e=>setSelected(e.target.checked?new Set(rows.map(row=>row.id)):new Set())}/><span>{page.columns[0]}</span><span>{page.columns[1]}</span><span>{page.columns[2]}</span><span>{page.columns[3]}</span><span/></div>
      {query.isLoading&&<div className="loading-state" role="status" aria-label="正在读取数据"><div className="skeleton"/><div className="skeleton"/><div className="skeleton"/></div>}
      {!query.isLoading&&allowed&&!rows.length&&!query.error&&<div className="empty-state"><Symbol>{search||status?'search':'forum'}</Symbol><strong>{search||status?'没有找到匹配的记录':'这里还没有记录'}</strong><p>{search||status?'试试其他关键词，或清除筛选条件。':'新内容产生后会显示在这里，你可以随时刷新查看。'}</p>{(search||status)&&<button className="pill-button" onClick={()=>{setSearch('');setStatus('');changePage(0)}}>清除筛选</button>}</div>}
      {rows.map(row=><div className="table-row" key={row.id}><input className="check" type="checkbox" aria-label={`选择 ${row.cells[0]}`} checked={selected.has(row.id)} onChange={()=>setSelected(current=>{const next=new Set(current);if(next.has(row.id))next.delete(row.id);else next.add(row.id);return next})}/><button className="record-title" onClick={()=>{operation.reset();setDetail(row)}}>{row.cells[0]}</button><span className="muted" data-label={page.columns[1]}>{row.cells[1]}</span><Status value={row.cells[2]}/><span className="muted" data-label={page.columns[3]}>{row.cells[3]}</span><button className="icon-button" aria-label={`查看 ${row.cells[0]}`} onClick={()=>{operation.reset();setDetail(row)}}><Symbol>chevron_right</Symbol></button></div>)}
    </div>
    <div className="pagination"><span>共 {total} 条 · 第 {pageIndex+1} 页 · 每页 25 条</span><button className="pill-button" disabled={pageIndex===0||query.isFetching} onClick={()=>changePage(pageIndex-1)}>上一页</button><button className="pill-button" disabled={(pageIndex+1)*25>=total||query.isFetching} onClick={()=>changePage(pageIndex+1)}>下一页</button></div>
    {selected.size>0&&<div className="bulk-bar glass strong"><strong>已选 {selected.size} 项</strong><button className="pill-button" onClick={()=>downloadCsv(`${page.title}-所选`,rows.filter(r=>selected.has(r.id)),page.columns)}>导出所选</button><button className="pill-button" onClick={()=>setSelected(new Set())}>取消选择</button></div>}
    {detail&&<Modal title={detail.cells[0]} onClose={()=>setDetail(null)}><RecordDetails record={detail}/><div className="action-list">{actionsFor(kind,detail,role).map(action=><button className="pill-button" key={action.key} disabled={!backend} onClick={()=>{operation.reset();setDetail(null);setPending({record:detail,action})}}>{action.label}</button>)}{(kind==='announcements'||kind==='releases')&&detail.raw.status==='draft'&&allowed&&<button className="pill-button" onClick={()=>{draft.reset();setEdit(detail);setDetail(null)}}>编辑草稿</button>}{kind==='users'&&role==='super_admin'&&detail.id!==who.data?.id&&<label className="field"><span>账户角色</span><select aria-label="账户角色" defaultValue={String(detail.raw.role)} onChange={e=>{setDetail(null);setPending({record:detail,action:{key:'role:'+e.target.value,label:'修改角色',detail:'角色变更将使该账号现有会话失效，需要重新登录。'}})}}><option value="student">普通用户</option><option value="moderator">审核员</option><option value="admin">管理员</option>{detail.raw.role==='super_admin'&&<option value="super_admin" disabled>超级管理员</option>}</select></label>}</div></Modal>}
    {pending&&<Modal title={pending.action.label} busy={operation.isPending} onClose={()=>setPending(null)}><ConfirmForm detail={pending.action.detail} requiresNote={pending.action.requiresNote} busy={operation.isPending} error={operation.error?.message} onSubmit={note=>operation.mutate({type:'action',kind,record:pending.record,action:pending.action,note})}/></Modal>}
    {createOpen&&(kind==='announcements'||kind==='releases')&&<DraftDialog kind={kind} busy={operation.isPending} error={operation.error?.message} onClose={()=>setCreateOpen(false)} onSubmit={values=>operation.mutate({type:'create',kind,values})}/>}
    {edit&&(kind==='announcements'||kind==='releases')&&<DraftDialog kind={kind} record={edit} busy={draft.isPending} error={draft.error?.message} onClose={()=>setEdit(null)} onSubmit={values=>draft.mutate(values)}/>}
  </>
}

function RecordDetails({record}:{record:AdminRecord}){
 const labels:Record<string,string>={id:'记录 ID',body:'正文',description:'描述',reason:'举报原因',details:'补充说明',content_digest:'举报时内容摘要',resolution:'处理说明',email:'邮箱',role:'角色',registration_source:'注册来源',invite_hint:'邀请码尾号',invite_note:'邀请备注',last_sign_in_at:'最近登录',created_at:'创建时间',updated_at:'更新时间',status:'状态',moderation_status:'审核状态',title:'标题',notes:'版本说明',apk_url:'安装包地址',checksum_sha256:'SHA-256',resource_type:'资源类型',resource_id:'资源 ID',metadata:'操作详情',target_id:'举报对象',target_type:'对象类型',is_public:'是否公开',is_blocked:'是否受限',buyer_id:'买家 ID',seller_id:'记录者 / 卖家 ID',author_id:'作者 ID',reporter_id:'举报人 ID',actor_id:'操作者 ID',completion_note:'完成感想',target_date:'目标日期',completed_at:'完成时间',version:'数据版本',price_cents:'金额（分）',parent_id:'所属内容 ID'}
 return <dl className="record-details">{Object.entries(record.raw).filter(([k,v])=>k in labels&&v!==null&&v!=='').map(([k,v])=><div key={k}><dt>{labels[k]}</dt><dd>{typeof v==='object'?JSON.stringify(v,null,2):typeof v==='boolean'?(v?'是':'否'):String(v)}</dd></div>)}</dl>
}
function ConfirmForm({detail,requiresNote,busy,error,onSubmit}:{detail:string;requiresNote?:boolean;busy:boolean;error?:string;onSubmit:(note:string)=>void}){const [note,setNote]=useState('');return <form onSubmit={e=>{e.preventDefault();onSubmit(note.trim())}}><p>{detail}</p>{requiresNote&&<label className="field"><span>处理说明</span><textarea required maxLength={1000} value={note} onChange={e=>setNote(e.target.value)} rows={4}/></label>}{error&&<p role="alert" className="error-text">{error}</p>}<button className="pill-button primary" disabled={busy||Boolean(requiresNote&&!note.trim())}>{busy?'正在提交…':'确认操作'}</button></form>}
function DraftDialog({kind,record,busy,error,onClose,onSubmit}:{kind:'announcements'|'releases';record?:AdminRecord;busy:boolean;error?:string;onClose:()=>void;onSubmit:(values:Record<string,string>)=>void}){
 const [values,setValues]=useState<Record<string,string>>(()=>Object.fromEntries(Object.entries(record?.raw||{}).map(([k,v])=>[k==='checksum_sha256'?'checksum':k,String(v??'')]))),field=(name:string)=>({value:values[name]||'',onChange:(e:ChangeEvent<HTMLInputElement|HTMLTextAreaElement>)=>setValues(v=>({...v,[name]:e.target.value}))})
 return <Modal title={`${record?'编辑':'新建'}${kind==='announcements'?'公告':'版本'}`} busy={busy} onClose={onClose}><form onSubmit={e=>{e.preventDefault();onSubmit(values)}}>{kind==='announcements'?<><label className="field"><span>标题</span><input {...field('title')} maxLength={160} required/></label><label className="field"><span>正文</span><textarea {...field('body')} rows={7} maxLength={10000} required/></label></>:<><label className="field"><span>版本名称</span><input {...field('version_name')} maxLength={40} required/></label><label className="field"><span>版本代码</span><input {...field('version_code')} type="number" min={1} max={2147483647} required/></label><label className="field"><span>版本说明</span><textarea {...field('notes')} rows={5} maxLength={10000} required/></label><label className="field"><span>APK HTTPS 地址</span><input {...field('apk_url')} type="url" pattern="https://.*" required/></label><label className="field"><span>SHA-256</span><input {...field('checksum')} pattern="[0-9a-fA-F]{64}" required/></label></>}{error&&<p role="alert" className="error-text">{error}</p>}<button className="pill-button primary" disabled={busy}>{busy?'正在保存…':'保存草稿'}</button></form></Modal>
}

function Status({value}:{value:string}) { const kind=/高|受限|争议|重试|移除|拒绝/.test(value)?'error':/中|待|定时|草稿/.test(value)?'warn':/正常|成功|完成|发布|在售|通过/.test(value)?'ok':'';return <span className={`badge ${kind}`}>{value}</span> }

function actionsFor(kind:Kind,record:AdminRecord,role:string="super_admin"):ActionSpec[]{
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

async function executeOperation(operation:Operation){
  if(!backend)throw new Error('管理台尚未连接 Supabase。')
  if(operation.type==='create'){
    const {error}=operation.kind==='announcements'
      ?await backend.rpc('admin_create_announcement',{announcement_title:operation.values.title,announcement_body:operation.values.body})
      :await backend.rpc('admin_create_release',{release_version_code:Number(operation.values.version_code),release_version_name:operation.values.version_name,release_notes:operation.values.notes,release_apk_url:operation.values.apk_url,release_checksum_sha256:operation.values.checksum})
    if(error)throw error
    return
  }
  const {kind,record,action,note}=operation
  let result:{error:unknown}|null=null
  if(kind==='users')result=action.key.startsWith('role:')?await backend.rpc('admin_set_user_role',{target_user:record.id,next_role:action.key.slice(5)}):await backend.rpc('admin_set_user_blocked',{target_user:record.id,blocked:action.key==='block'})
  else if(kind==='content'||kind==='listings')result=await backend.rpc('moderate_content',{target_type:kind==='listings'?'listing':String(record.raw.content_type??'post'),target_id:record.id,next_status:action.key==='approve'?'approved':action.key==='reject'?'rejected':'removed'})
  else if(kind==='orders')result=await backend.rpc('transition_order',{target_order:record.id,expected_version:Number(record.raw.version),next_status:action.key==='complete_order'?'completed':'cancelled'})
  else if(kind==='reports')result=await backend.rpc('resolve_report',{target_report:record.id,next_status:action.key==='resolve_report'?'resolved':'dismissed',resolution_text:note})
  else if(kind==='announcements')result=await backend.rpc('admin_set_announcement_status',{target_announcement:record.id,next_status:action.key==='publish_announcement'?'published':'withdrawn'})
  else if(kind==='releases')result=await backend.rpc('admin_set_release_status',{target_release:record.id,next_status:action.key==='publish_release'?'published':'archived'})
  if(result?.error)throw result.error
}

function toRecord(kind:Kind,item:Record<string,unknown>):AdminRecord{
  const date=formatDate(kind==='users'?item.created_at:item.publish_at??item.published_at??item.created_at)
  const short=(value:unknown)=>String(value??'—').slice(0,12)
  const id=String(item.id??`${kind}-${Math.random()}`)
  if(kind==='users')return {id,raw:item,cells:[`${item.display_name??'未命名'} / ${item.email??item.handle??'—'}`,({student:'普通用户',moderator:'审核员',admin:'管理员',super_admin:'超级管理员'} as Record<string,string>)[String(item.role)]??String(item.role??'普通用户'),item.is_blocked?'受限':'正常',date]}
  if(kind==='content')return {id,raw:item,cells:[`${item.content_type==='listing_comment'?'心愿评论':item.content_type==='comment'?'评论':'帖子'} · ${String(item.body??'').slice(0,38)}`,short(item.author_id),statusLabel(item.moderation_status),date]}
  if(kind==='listings')return {id,raw:item,cells:[String(item.title??'未命名'),short(item.seller_id),statusLabel(item.moderation_status),item.price_cents == null ? '未设价格' : money(item.price_cents)]}
  if(kind==='orders')return {id,raw:item,cells:[short(item.id),`${short(item.buyer_id)} / ${short(item.seller_id)}`,statusLabel(item.status),money(item.price_cents)]}
  if(kind==='reports')return {id,raw:item,cells:[`${item.target_type??'内容'} / ${short(item.target_id)}`,short(item.reporter_id),statusLabel(item.status),date]}
  if(kind==='announcements')return {id,raw:item,cells:[String(item.title??'未命名'),'全部用户',statusLabel(item.status),date]}
  if(kind==='releases')return {id,raw:item,cells:[String(item.version_name??'—'),String(item.version_code??'—'),statusLabel(item.status),date]}
  return {id,raw:item,cells:[short(item.actor_id),String(item.action??'—'),statusLabel(item.result),date]}
}

function downloadCsv(title:string,rows:AdminRecord[],headers:string[]=[]){const content=[...(headers.length?[headers]:[]),...rows.map(row=>row.cells)].map(cells=>cells.map(raw=>{const value=/^[=+@\-\t\r]/.test(raw)?"'"+raw:raw;return `"${value.replaceAll('"','""')}"`}).join(',')).join('\n');const url=URL.createObjectURL(new Blob([`\uFEFF${content}`],{type:'text/csv;charset=utf-8'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=`${title}.csv`;anchor.click();URL.revokeObjectURL(url)}
function money(value:unknown){const cents=Number(value);return Number.isFinite(cents)?`¥${(cents/100).toFixed(2)}`:'—'}
function formatDate(value:unknown){if(!value)return '—';const date=new Date(String(value));return Number.isNaN(date.valueOf())?'—':date.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}
function statusLabel(value:unknown){const labels:Record<string,string>={pending:'待处理',approved:'已通过',rejected:'已拒绝',removed:'已移除',resolved:'已完成',dismissed:'已驳回',active:'在售',reserved:'已预订',sold:'已售',withdrawn:'已下架',pending_payment:'待付款',paid:'已付款',meeting:'待交付',completed:'已完成',cancelled:'已取消',disputed:'争议中',draft:'草稿',scheduled:'定时',published:'已发布',archived:'已归档',success:'成功',retry:'重试'};return labels[String(value)]??String(value??'—')}
