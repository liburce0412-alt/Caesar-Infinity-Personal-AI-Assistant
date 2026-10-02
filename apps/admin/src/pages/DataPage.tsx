import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type ChangeEvent, useState } from 'react'
import { Symbol } from '../components/BrandMark'
import { api, backend, currentAdmin } from '../lib/backend'
import { Modal } from '../components/Modal'

import { type Kind, type AdminRecord, type ActionSpec, actionsFor, executeOperation, toRecord, downloadCsv, statusLabel } from '../lib/adminRecords'
import { useDebouncedValue } from '../lib/useDebouncedValue'

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
  const debouncedSearch=useDebouncedValue(search)
  const who=useQuery({queryKey:['admin-me'],queryFn:currentAdmin}),role=who.data?.role||''
  const allowed=!['users','announcements','releases','audit'].includes(kind)||['admin','super_admin'].includes(role)
  const query=useQuery({queryKey:['admin-data',kind,pageIndex,debouncedSearch,status,ascending],enabled:allowed&&search===debouncedSearch,queryFn:async({signal})=>{
    if(!backend){const rows=samples[kind].filter(r=>r.cells.join(' ').toLowerCase().includes(search.toLowerCase()));return {rows,total:rows.length}}
    const result=await api.rpc('admin_records',{kind,page:pageIndex,search:debouncedSearch,status,ascending},signal)
    return {rows:result.rows.map((r:Record<string,unknown>)=>toRecord(kind,r)) as AdminRecord[],total:result.total as number}
  }})
  const rows=query.data?.rows||[],total=query.data?.total||0
  const operation=useMutation({mutationFn:executeOperation,onSuccess:async()=>{setPending(null);setCreateOpen(false);setDetail(null);setSelected(new Set());setNotice('操作已完成。');await cache.invalidateQueries({queryKey:['admin-data']});await cache.invalidateQueries({queryKey:['admin-overview']})}})
  const draft=useMutation({mutationFn:(values:Record<string,string>)=>api.rpc('admin_save_draft',{kind,id:edit!.id,values}),onSuccess:async()=>{setEdit(null);setDetail(null);setNotice('草稿已保存。');await cache.invalidateQueries({queryKey:['admin-data',kind]})}})
  const changePage=(next:number)=>{setPageIndex(next);setSelected(new Set())}
  const refresh=()=>{if(who.error)void who.refetch();else if(allowed)void query.refetch()}
  const statuses=kind==='users'?['active','blocked']:kind==='content'||kind==='listings'?['pending','approved','rejected','removed']:kind==='orders'?['pending_payment','paid','meeting','disputed','completed','cancelled']:kind==='reports'?['pending','resolved','dismissed']:kind==='announcements'?['draft','published','withdrawn']:kind==='releases'?['draft','published','archived']:['success','retry']
  return <>
    <div className="page-header"><div><div className="eyebrow">{page.eyebrow}</div><h1>{page.title}</h1><p className="muted">{page.description}</p></div><button className="pill-button primary" disabled={!backend||!allowed} onClick={()=>{operation.reset();if(kind==='announcements'||kind==='releases')setCreateOpen(true);else downloadCsv(`${page.title}-当前页`,rows,page.columns)}}><Symbol>{kind==='announcements'||kind==='releases'?'add':'download'}</Symbol>{kind==='announcements'||kind==='releases'?page.action:'导出当前页'}</button></div>
    {!backend&&<p className="muted">当前为示例预览，写入操作不可用。</p>}
    {who.data&&!allowed&&<p role="alert">此页面仅限管理员访问。</p>}
    <div className="filters"><label className="search-field glass"><Symbol>search</Symbol><input aria-label="搜索当前结果" placeholder="搜索全部记录" value={search} onChange={e=>{setSearch(e.target.value);changePage(0)}}/></label><select className="pill-button" aria-label="筛选状态" value={status} onChange={e=>{setStatus(e.target.value);changePage(0)}}><option value="">全部状态</option>{statuses.map(s=><option key={s} value={s}>{kind==='users'?(s==='active'?'正常':'受限'):statusLabel(s)}</option>)}</select><button className="pill-button" onClick={()=>{setAscending(v=>!v);changePage(0)}}>{ascending?'较早优先':'最近优先'}</button><button className="pill-button" onClick={refresh} disabled={who.isFetching||query.isFetching||(!allowed&&!who.error)}>刷新</button></div>
    {(query.error||who.error)&&<div className="error-bar" role="alert">读取失败：{(query.error||who.error)?.message}。<button className="pill-button" onClick={refresh} disabled={who.isFetching||query.isFetching||(!allowed&&!who.error)}>重试</button></div>}
    {notice&&<p className="success-notice" role="status">{notice}</p>}
    <div className="table-panel glass strong" aria-busy={query.isFetching}><div className="table-summary"><strong>{search||status?'筛选结果':'全部记录'} <span className="badge">{query.data?total:'—'}</span></strong><span className="muted">{query.isFetching?'正在同步…':'点击记录查看详情'}</span></div><div className="table-row header"><input className="check" type="checkbox" aria-label="选择当前页全部记录" disabled={!rows.length} checked={rows.length>0&&rows.every(row=>selected.has(row.id))} onChange={e=>setSelected(e.target.checked?new Set(rows.map(row=>row.id)):new Set())}/><span>{page.columns[0]}</span><span>{page.columns[1]}</span><span>{page.columns[2]}</span><span>{page.columns[3]}</span><span/></div>
      {query.isLoading&&<div className="loading-state" role="status" aria-label="正在读取数据"><div className="skeleton"/><div className="skeleton"/><div className="skeleton"/></div>}
      {!query.isLoading&&allowed&&!rows.length&&!query.error&&<div className="empty-state"><Symbol>{search||status?'search':'forum'}</Symbol><strong>{search||status?'没有找到匹配的记录':'这里还没有记录'}</strong><p>{search||status?'试试其他关键词，或清除筛选条件。':'新内容产生后会显示在这里，你可以随时刷新查看。'}</p>{(search||status)&&<button className="pill-button" onClick={()=>{setSearch('');setStatus('');changePage(0)}}>清除筛选</button>}</div>}
      {rows.map(row=><div className="table-row" key={row.id}><input className="check" type="checkbox" aria-label={`选择 ${row.cells[0]}`} checked={selected.has(row.id)} onChange={()=>setSelected(current=>{const next=new Set(current);if(next.has(row.id))next.delete(row.id);else next.add(row.id);return next})}/><button className="record-title" onClick={()=>{operation.reset();setDetail(row)}}>{row.cells[0]}</button><span className="muted" data-label={page.columns[1]}>{row.cells[1]}</span><Status value={row.cells[2]}/><span className="muted" data-label={page.columns[3]}>{row.cells[3]}</span><button className="icon-button" aria-label={`查看 ${row.cells[0]}`} onClick={()=>{operation.reset();setDetail(row)}}><Symbol>chevron_right</Symbol></button></div>)}
    </div>
    <div className="pagination"><span>共 {total} 条 · 第 {pageIndex+1} 页 · 每页 25 条</span><button className="pill-button" disabled={pageIndex===0||query.isFetching} onClick={()=>changePage(pageIndex-1)}>上一页</button><button className="pill-button" disabled={(pageIndex+1)*25>=total||query.isFetching} onClick={()=>changePage(pageIndex+1)}>下一页</button></div>
    {selected.size>0&&<div className="bulk-bar glass strong"><strong>已选 {selected.size} 项</strong><button className="pill-button" onClick={()=>downloadCsv(`${page.title}-所选`,rows.filter(r=>selected.has(r.id)),page.columns)}>导出所选</button><button className="pill-button" onClick={()=>setSelected(new Set())}>取消选择</button></div>}
    {detail&&<Modal title={detail.cells[0]} onClose={()=>setDetail(null)}><RecordDetails record={detail} columns={page.columns}/><div className="action-list">{actionsFor(kind,detail,role).map(action=><button className="pill-button" key={action.key} disabled={!backend} onClick={()=>{operation.reset();setDetail(null);setPending({record:detail,action})}}>{action.label}</button>)}{(kind==='announcements'||kind==='releases')&&detail.raw.status==='draft'&&allowed&&<button className="pill-button" onClick={()=>{draft.reset();setEdit(detail);setDetail(null)}}>编辑草稿</button>}{kind==='users'&&role==='super_admin'&&detail.id!==who.data?.id&&<label className="field"><span>账户角色</span><select aria-label="账户角色" defaultValue={String(detail.raw.role)} onChange={e=>{setDetail(null);setPending({record:detail,action:{key:'role:'+e.target.value,label:'修改角色',detail:'角色变更将使该账号现有会话失效，需要重新登录。'}})}}><option value="student">普通用户</option><option value="moderator">审核员</option><option value="admin">管理员</option>{detail.raw.role==='super_admin'&&<option value="super_admin" disabled>超级管理员</option>}</select></label>}</div></Modal>}
    {pending&&<Modal title={pending.action.label} busy={operation.isPending} onClose={()=>setPending(null)}><ConfirmForm detail={pending.action.detail} requiresNote={pending.action.requiresNote} busy={operation.isPending} error={operation.error?.message} onSubmit={note=>operation.mutate({type:'action',kind,record:pending.record,action:pending.action,note})}/></Modal>}
    {createOpen&&(kind==='announcements'||kind==='releases')&&<DraftDialog kind={kind} busy={operation.isPending} error={operation.error?.message} onClose={()=>setCreateOpen(false)} onSubmit={values=>operation.mutate({type:'create',kind,values})}/>}
    {edit&&(kind==='announcements'||kind==='releases')&&<DraftDialog kind={kind} record={edit} busy={draft.isPending} error={draft.error?.message} onClose={()=>setEdit(null)} onSubmit={values=>draft.mutate(values)}/>}
  </>
}

function RecordDetails({record,columns}:{record:AdminRecord;columns:string[]}){
 const labels:Record<string,string>={id:'记录 ID',body:'正文',description:'描述',reason:'举报原因',details:'补充说明',content_digest:'举报时内容摘要',resolution:'处理说明',email:'邮箱',role:'角色',registration_source:'注册来源',invite_hint:'邀请码尾号',invite_note:'邀请备注',last_sign_in_at:'最近登录',created_at:'创建时间',updated_at:'更新时间',status:'状态',moderation_status:'审核状态',title:'标题',notes:'版本说明',apk_url:'安装包地址',checksum_sha256:'SHA-256',resource_type:'资源类型',resource_id:'资源 ID',metadata:'操作详情',target_id:'举报对象',target_type:'对象类型',is_public:'是否公开',is_blocked:'是否受限',buyer_id:'买家 ID',seller_id:'记录者 / 卖家 ID',author_id:'作者 ID',reporter_id:'举报人 ID',actor_id:'操作者 ID',completion_note:'完成感想',target_date:'目标日期',completed_at:'完成时间',version:'数据版本',price_cents:'金额（分）',parent_id:'所属内容 ID'}
 const entries=Object.entries(record.raw).filter(([k,v])=>k in labels&&v!==null&&v!=='')
 return <dl className="record-details">{entries.length?entries.map(([k,v])=><div key={k}><dt>{labels[k]}</dt><dd>{typeof v==='object'?JSON.stringify(v,null,2):typeof v==='boolean'?(v?'是':'否'):String(v)}</dd></div>):record.cells.map((value,index)=><div key={index}><dt>{columns[index]}</dt><dd>{value}</dd></div>)}</dl>
}
function ConfirmForm({detail,requiresNote,busy,error,onSubmit}:{detail:string;requiresNote?:boolean;busy:boolean;error?:string;onSubmit:(note:string)=>void}){const [note,setNote]=useState('');return <form onSubmit={e=>{e.preventDefault();onSubmit(note.trim())}}><p>{detail}</p>{requiresNote&&<label className="field"><span>处理说明</span><textarea required maxLength={1000} value={note} onChange={e=>setNote(e.target.value)} rows={4}/></label>}{error&&<p role="alert" className="error-text">{error}</p>}<button className="pill-button primary" disabled={busy||Boolean(requiresNote&&!note.trim())}>{busy?'正在提交…':'确认操作'}</button></form>}
function DraftDialog({kind,record,busy,error,onClose,onSubmit}:{kind:'announcements'|'releases';record?:AdminRecord;busy:boolean;error?:string;onClose:()=>void;onSubmit:(values:Record<string,string>)=>void}){
 const [values,setValues]=useState<Record<string,string>>(()=>Object.fromEntries(Object.entries(record?.raw||{}).map(([k,v])=>[k==='checksum_sha256'?'checksum':k,String(v??'')]))),field=(name:string)=>({value:values[name]||'',onChange:(e:ChangeEvent<HTMLInputElement|HTMLTextAreaElement>)=>setValues(v=>({...v,[name]:e.target.value}))})
 return <Modal title={`${record?'编辑':'新建'}${kind==='announcements'?'公告':'版本'}`} busy={busy} onClose={onClose}><form onSubmit={e=>{e.preventDefault();onSubmit(values)}}>{kind==='announcements'?<><label className="field"><span>标题</span><input {...field('title')} maxLength={160} required/></label><label className="field"><span>正文</span><textarea {...field('body')} rows={7} maxLength={10000} required/></label></>:<><label className="field"><span>版本名称</span><input {...field('version_name')} maxLength={40} required/></label><label className="field"><span>版本代码</span><input {...field('version_code')} type="number" min={1} max={2147483647} required/></label><label className="field"><span>版本说明</span><textarea {...field('notes')} rows={5} maxLength={10000} required/></label><label className="field"><span>APK HTTPS 地址</span><input {...field('apk_url')} type="url" pattern="https://.*" required/></label><label className="field"><span>SHA-256</span><input {...field('checksum')} pattern="[0-9a-fA-F]{64}" required/></label></>}{error&&<p role="alert" className="error-text">{error}</p>}<button className="pill-button primary" disabled={busy}>{busy?'正在保存…':'保存草稿'}</button></form></Modal>
}

function Status({value}:{value:string}) { const kind=/高|受限|争议|重试|移除|拒绝/.test(value)?'error':/中|待|定时|草稿/.test(value)?'warn':/正常|成功|完成|发布|在售|通过/.test(value)?'ok':'';return <span className={`badge ${kind}`}>{value}</span> }
