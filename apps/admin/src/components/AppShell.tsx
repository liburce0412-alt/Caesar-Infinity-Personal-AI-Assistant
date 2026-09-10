import { Link, Outlet, useNavigate, useRouterState } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { BrandMark, Symbol } from './BrandMark'
import { SpectraCanvas, type SpectraEnvironment } from './SpectraCanvas'
import { backend, currentAdmin } from '../lib/backend'

const groups = [
  {label:'工作空间',items:[['/', 'dashboard', '总览'], ['/invites', 'key', '邀请码'], ['/users', 'group', '用户']]},
  {label:'社区管理',items:[['/content', 'forum', '帖子评论'], ['/listings', 'storefront', '心愿'], ['/orders', 'receipt_long', '订单'], ['/reports', 'fact_check', '举报审核']]},
  {label:'运营与记录',items:[['/announcements', 'campaign', '公告'], ['/releases', 'rocket_launch', '版本发布'], ['/audit', 'policy', '审计日志']]},
] as const
const environments: Record<SpectraEnvironment,string> = {original:'原色',ocean:'海蓝',ultraviolet:'暮紫',ember:'暖橙'}
const roles: Record<string,string> = {super_admin:'超级管理员',admin:'管理员',moderator:'审核员'}

export function AppShell() {
  const [menuOpen,setMenuOpen]=useState(false)
  const drawer=useRef<HTMLDialogElement>(null)
  const [environment,setEnvironment]=useState<SpectraEnvironment>(()=>{
    const saved=localStorage.getItem('spectra-environment')
    return saved && saved in environments ? saved as SpectraEnvironment : 'original'
  })
  const [motion]=useState(()=>localStorage.getItem('spectra-motion')!=='off' && !matchMedia('(prefers-reduced-motion: reduce)').matches)
  const navigate=useNavigate(),queryClient=useQueryClient()
  const who=useQuery({queryKey:['admin-me'],queryFn:currentAdmin})
  const [signOutError,setSignOutError]=useState('')
  const path=useRouterState({select:state=>state.location.pathname})
  const page=groups.flatMap(group=>[...group.items]).find(item=>item[0]===path)?.[2]||'管理台'
  useEffect(()=>setMenuOpen(false),[path])
  useEffect(()=>{localStorage.setItem('spectra-environment',environment)},[environment])
  useEffect(()=>{
    const dialog=drawer.current
    if(menuOpen && !dialog?.open) dialog?.showModal()
    if(!menuOpen && dialog?.open) dialog.close()
  },[menuOpen])
  useEffect(()=>{
    if(!backend)return
    let currentUser:string|undefined
    const {data}=backend.auth.onAuthStateChange((event,session)=>{
      const nextUser=session?.user.id
      if(event==='SIGNED_OUT'||(currentUser!==undefined&&currentUser!==nextUser))queryClient.clear()
      currentUser=nextUser
      if(event==='SIGNED_OUT')void navigate({to:'/login'})
    })
    return ()=>data.subscription.unsubscribe()
  },[navigate,queryClient])
  const signOut=async()=>{
    setSignOutError('')
    const result=await backend?.auth.signOut()
    if(result?.error){setSignOutError('退出失败，请重试。');return}
    await navigate({to:'/login'})
  }
  const navigation=<>
    <div className="brand"><BrandMark/><div>CampusAI<small>管理工作台</small></div></div>
    <nav className="nav" aria-label="管理台导航">{groups.map(group=><div className="nav-group" key={group.label}>
      <div className="nav-caption">{group.label}</div>
      {group.items.map(([to,icon,label])=><Link key={to} to={to} onClick={()=>setMenuOpen(false)} activeOptions={{exact:to==='/'}} className="nav-link" activeProps={{className:'nav-link active'}}><Symbol>{icon}</Symbol><span>{label}</span><span className="nav-indicator"/></Link>)}
    </div>)}</nav>
    <div className="nav-spacer"/>
    <div className="sidebar-footer"><div className="profile-line"><div className="avatar"><Symbol>person</Symbol></div><div className="profile-copy"><strong>{roles[who.data?.role]||'管理账号'}</strong><small title={who.data?.email}>{who.data?.email||'正在读取账号'}</small></div></div>
      <button className="signout-button" onClick={()=>void signOut()}><Symbol>logout</Symbol>退出登录</button>
      {signOutError&&<p role="alert">{signOutError}</p>}
    </div>
  </>
  return <>
    <SpectraCanvas environment={environment} motion={motion} quality="auto"/>
    <a className="skip-link" href="#main-content">跳到主要内容</a>
    <div className="app-shell">
      <aside className="sidebar desktop-sidebar">{navigation}</aside>
      <dialog ref={drawer} className="mobile-drawer" aria-label="管理台导航菜单" onCancel={e=>{e.preventDefault();setMenuOpen(false)}} onClick={e=>{if(e.target===e.currentTarget)setMenuOpen(false)}}>
        <button className="icon-button drawer-close" aria-label="关闭导航" onClick={()=>setMenuOpen(false)}><Symbol>close</Symbol></button>
        {navigation}
      </dialog>
      <main className="main" id="main-content" tabIndex={-1}>
        <header className="topbar"><div className="breadcrumb"><button className="icon-button mobile-menu" aria-label="打开导航" aria-expanded={menuOpen} onClick={()=>setMenuOpen(true)}><Symbol>menu</Symbol></button><span className="hide-mobile">管理工作台</span><span className="hide-mobile breadcrumb-divider">/</span><strong>{page}</strong></div>
          <div className="toolbar"><label className="theme-select"><Symbol>blur_on</Symbol><select aria-label="背景配色" value={environment} onChange={e=>setEnvironment(e.target.value as SpectraEnvironment)}>{Object.entries(environments).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label><Link className="icon-button" aria-label="查看待处理举报" to="/reports"><Symbol>notifications</Symbol></Link></div>
        </header>
        <div className="page-content"><Outlet/></div>
        <footer className="workspace-footer"><span>CampusAI · 让校园生活井然有序</span><span>管理工作台</span></footer>
      </main>
    </div>
  </>
}
