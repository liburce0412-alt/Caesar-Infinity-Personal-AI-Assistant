import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Symbol } from '../components/BrandMark'
import { supabase } from '../lib/supabase'

type Overview={users:number;records:number;pending:number;orders:number;trend?:{date:string;minutes:number}[]}
const fallback:Overview={users:1248,records:8361,pending:17,orders:286,trend:[18,32,26,54,42,68,58].map((minutes,i)=>({date:'2026-09-0'+(i+1),minutes}))}
export function OverviewPage(){
  const {data,isFetching,error,refetch,dataUpdatedAt}=useQuery({
    queryKey:['admin-overview'],
    queryFn:async():Promise<Overview>=>{
      if(!supabase)return fallback
      const {data,error}=await supabase.rpc('admin_overview')
      if(error)throw error
      return data as Overview
    },
  })
  const trend=data?.trend||[],peak=Math.max(1,...trend.map(d=>Number(d.minutes)))
  const points=trend.map((d,i)=>`${20+i*760/Math.max(1,trend.length-1)},${220-180*Number(d.minutes)/peak}`).join(' ')
  return <>
    <div className="page-header"><div><div className="eyebrow">OVERVIEW / 总览</div><h1>校园运行脉搏</h1><p className="muted">从这里，照看校园里的每一次连接。</p></div><button className="pill-button" onClick={()=>void refetch()} disabled={isFetching}><Symbol>refresh</Symbol>{isFetching?'同步中…':'刷新数据'}</button></div>
    {!supabase&&<div className="preview-notice">示例预览 · 以下为演示数据</div>}
    {error&&<div className="error-bar" role="alert">同步失败，请重试。{data?'下方保留上次同步结果。':''}</div>}
    <section className="overview-welcome"><div><span className="eyebrow">YOUR CAMPUS, CONNECTED</span><h2>让好的体验，从每一个细节开始。</h2><p>邀请新伙伴，回应社区声音，把值得关注的事放在眼前。</p><Link to="/invites" className="pill-button primary"><Symbol>key</Symbol>邀请新用户<Symbol>arrow_forward</Symbol></Link></div><div className="welcome-emblem" aria-hidden="true"><span/><span/><Symbol>group</Symbol></div></section>
    <section className="metric-strip" aria-label="关键指标">
      <Metric icon="group" label="用户总数" value={data?.users.toLocaleString()??'—'} meta="已注册账号" to="/users"/>
      <Metric icon="clock" label="时间记录" value={data?.records.toLocaleString()??'—'} meta="累计记录条数" to="/"/>
      <Metric icon="fact_check" label="待审核" value={data?.pending.toString()??'—'} meta="待处理举报" to="/reports" warn/>
      <Metric icon="receipt_long" label="累计订单" value={data?.orders.toLocaleString()??'—'} meta="全部订单" to="/orders"/>
    </section>
    <div className="dashboard-grid">
      <section className="panel glass strong trend-panel"><div className="panel-header"><div><div className="eyebrow">FOCUS / 7 DAYS</div><h2>有效投入趋势</h2><p className="muted panel-description">过去七天的每日投入时长</p></div><span className="badge">{isFetching?'同步中':trend.length?'分钟 / 每日':'暂无数据'}</span></div>
        {trend.length?<><div className="chart"><div className="chart-grid" aria-hidden="true"><span>{Math.ceil(peak)} 分</span><span>{Math.ceil(peak/2)} 分</span><span>0</span></div><svg className="chart-line" viewBox="0 0 800 250" preserveAspectRatio="none" role="img" aria-label={`过去七天投入分钟：${trend.map(d=>Number(d.minutes)).join('、')}`}>
          <defs><linearGradient id="trend-fill" x1="0" y1="0" x2="0" y2="1"><stop stopColor="#168c82" stopOpacity=".19"/><stop offset="1" stopColor="#168c82" stopOpacity="0"/></linearGradient></defs>
          <polygon points={`20,240 ${points} ${20+(trend.length-1)*760/Math.max(1,trend.length-1)},240`} fill="url(#trend-fill)"/><polyline points={points}/>
          {trend.map((d,i)=><circle key={d.date} cx={20+i*760/Math.max(1,trend.length-1)} cy={220-180*Number(d.minutes)/peak} r="4"><title>{d.date}：{d.minutes} 分钟</title></circle>)}
        </svg></div><div className="chart-labels">{trend.map(d=><span key={d.date}>{d.date.slice(5)}<strong>{Number(d.minutes)} 分</strong></span>)}</div></>:<div className="empty-state"><Symbol>clock</Symbol><strong>等待第一份投入记录</strong><p>同步到每日聚合数据后，趋势会显示在这里。</p></div>}
        <div className="chart-footnote">{dataUpdatedAt?`最近同步 ${new Date(dataUpdatedAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}`:'尚未同步'}<span>按日汇总</span></div>
      </section>
      <section className="panel glass strong"><div className="panel-header"><div><div className="eyebrow">NEXT ACTION</div><h2>工作待办</h2></div><Symbol>fact_check</Symbol></div>
        <Link to="/reports" className="priority-link"><div className="queue-icon"><Symbol>flag</Symbol></div><div><strong>举报审核</strong><p>{data?data.pending>0?`有 ${data.pending} 条举报等待处理`:'暂无待处理举报':'正在读取待处理数量'}</p></div><Symbol>chevron_right</Symbol></Link>
        <div className="queue">{[['/content','forum','帖子与评论','查看社区内容'],['/listings','storefront','心愿管理','了解用户的期待'],['/announcements','campaign','公告管理','把新消息带给大家'],['/releases','rocket_launch','版本发布','管理应用版本']] .map(([to,icon,title,description])=><Link key={to} to={to} className="queue-row"><div className="queue-icon"><Symbol>{icon}</Symbol></div><div><strong>{title}</strong><small>{description}</small></div><Symbol>chevron_right</Symbol></Link>)}</div>
      </section>
    </div>
  </>
}
function Metric({icon,label,value,meta,to,warn=false}:{icon:string;label:string;value:string;meta:string;to:string;warn?:boolean}){
  return <Link to={to} className={`metric glass strong ${warn?'metric-warning':''}`}><div className="metric-top"><span>{label}</span><span className="metric-icon"><Symbol>{icon}</Symbol></span></div><div className="metric-value">{value}</div><div className="metric-delta">{meta}<Symbol>arrow_forward</Symbol></div></Link>
}
