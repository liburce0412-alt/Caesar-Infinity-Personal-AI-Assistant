import { useEffect, useRef, type ReactNode } from 'react'
export function Modal({title,onClose,children,busy=false}:{title:string;onClose:()=>void;children:ReactNode;busy?:boolean}){
 const ref=useRef<HTMLDialogElement>(null)
 useEffect(()=>{const d=ref.current!;d.showModal();return()=>d.close()},[])
 return <dialog ref={ref} className="admin-dialog glass strong" aria-label={title} onCancel={e=>{e.preventDefault();if(!busy)onClose()}}>
  <div className="panel-header"><h2>{title}</h2><button className="icon-button" aria-label="关闭" disabled={busy} onClick={onClose}>×</button></div>{children}
 </dialog>
}
