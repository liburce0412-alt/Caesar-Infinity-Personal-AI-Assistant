import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { BrandMark, Symbol } from '../components/BrandMark'
import { SpectraCanvas } from '../components/SpectraCanvas'
import { hasAdminRole, isBackendConfigured, backend } from '../lib/backend'

const schema=z.object({email:z.email('请输入有效邮箱'),password:z.string().min(8,'密码至少 8 位')})
type Form=z.infer<typeof schema>

export function LoginPage(){
  const navigate=useNavigate();const [serverError,setServerError]=useState(''),[showPassword,setShowPassword]=useState(false)
  const {register,handleSubmit,formState:{errors,isSubmitting}}=useForm<Form>({resolver:zodResolver(schema)})
  const submit=handleSubmit(async values=>{
    setServerError('')
    if(!backend){setServerError('管理台尚未配置 后端服务，请联系管理员。');return}
    try {
    const {data,error}=await backend.auth.signInWithPassword(values)
    if(error){setServerError(`登录失败：${error.message}。请检查账号或联系超级管理员。`);return}
    if(!data.user || !(await hasAdminRole(data.user.id))){
      await backend.auth.signOut()
      setServerError('这个账号没有管理台权限。请联系超级管理员分配角色。')
      return
    }
    await navigate({to:'/'})
    } catch {setServerError('暂时无法连接管理台，请稍后重试。')}
  })
  return <><SpectraCanvas environment="ocean" motion quality="auto"/><div className="login-wrap"><div className="login-layout">
    <section className="login-story"><div className="brand"><BrandMark/><div>CampusAI<small>管理工作台</small></div></div><div className="login-story-copy"><span className="eyebrow">A LITTLE CARE. A BETTER CAMPUS.</span><h1>连接校园，<br/>照看每一份期待。</h1><p>从第一位新伙伴到每一次社区互动，<br/>让日常管理更从容。</p></div><div className="login-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit orbit-three"/><div className="orbit-core"><BrandMark/></div><div className="orbit-node node-one"><Symbol>forum</Symbol></div><div className="orbit-node node-two"><Symbol>group</Symbol></div><div className="orbit-node node-three"><Symbol>storefront</Symbol></div></div><div className="login-story-footer">CAMPUSAI <span>人与校园，始终相连</span></div></section>
    <div className="login-form-wrap"><form className="login-card" onSubmit={submit}>
    <div className="login-form-heading"><span className="login-lock"><Symbol>lock</Symbol></span><div className="eyebrow">WELCOME BACK</div><h2>欢迎回来</h2><p className="muted">登录你的账号，进入校园管理工作台。</p></div>
    <div className="field"><label htmlFor="email">邮箱</label><input id="email" type="email" placeholder="请输入管理账号邮箱" autoComplete="username" aria-invalid={Boolean(errors.email)} aria-describedby={errors.email?'email-error':undefined} {...register('email')}/>{errors.email&&<span id="email-error" className="error-text">{errors.email.message}</span>}</div>
    <div className="field"><label htmlFor="password">密码</label><div className="password-field"><input id="password" type={showPassword?'text':'password'} placeholder="请输入密码" autoComplete="current-password" aria-invalid={Boolean(errors.password)} aria-describedby={errors.password?'password-error':undefined} {...register('password')}/><button type="button" className="icon-button" aria-label={showPassword?'隐藏密码':'显示密码'} aria-pressed={showPassword} onClick={()=>setShowPassword(v=>!v)}><Symbol>visibility</Symbol></button></div>{errors.password&&<span id="password-error" className="error-text">{errors.password.message}</span>}</div>
    {serverError&&<p className="error-text" role="alert">{serverError}</p>}
    <button className="pill-button primary login-submit" disabled={isSubmitting}>{isSubmitting?'正在验证…':'登录管理台'}<Symbol>arrow_forward</Symbol></button>
    <p className="login-help"><Symbol>policy</Symbol>仅向已获授权的管理账号开放</p>
    {!isBackendConfigured&&<p className="muted" style={{fontSize:12}}>此入口仅向拥有后台权限的账号开放。</p>}
  </form><div className="login-form-footer">CampusAI 管理工作台 · 安全访问</div></div></div></div></>
}
