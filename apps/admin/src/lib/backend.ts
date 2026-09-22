import { z } from 'zod'
import { supabase } from './supabase'

export const backend = supabase
export const isBackendConfigured = Boolean(backend)
export const inviteSchema = z.object({ id:z.string(),code_hint:z.string(),note:z.string(),expires_at:z.string(),status:z.string(),used_email:z.string().nullish(),used_at:z.string().nullish() })
const meSchema = z.object({id:z.string(),role:z.string(),is_blocked:z.boolean(),email:z.string().optional()})
const schemas = {
  admin_me: meSchema,
  admin_records: z.object({rows:z.array(z.record(z.string(),z.unknown())),total:z.number().int().nonnegative()}),
  admin_create_invitations: z.array(z.object({code:z.string(),expires_at:z.string(),note:z.string()})),
  admin_revoke_invitation: z.unknown(), admin_save_draft:z.unknown(),
  admin_create_announcement:z.unknown(),admin_create_release:z.unknown(),
  admin_set_user_role:z.unknown(),admin_set_user_blocked:z.unknown(),
  moderate_content:z.unknown(),transition_order:z.unknown(),resolve_report:z.unknown(),
  admin_set_announcement_status:z.unknown(),admin_set_release_status:z.unknown(),
}
type RpcArgs = {
  admin_me: Record<string,never>
  admin_records: {kind:string;search:string;status:string;page:number;ascending:boolean}
  admin_create_invitations: {count:number;days:number;note:string}
  admin_revoke_invitation: {target_invitation:string}
  admin_save_draft: {kind:string;id:string;values:Record<string,string>}
  admin_create_announcement: {announcement_title:string;announcement_body:string}
  admin_create_release: {release_version_code:number;release_version_name:string;release_notes:string;release_apk_url:string;release_checksum_sha256:string}
  admin_set_user_role: {target_user:string;next_role:string}
  admin_set_user_blocked: {target_user:string;blocked:boolean}
  moderate_content: {target_type:string;target_id:string;next_status:string}
  transition_order: {target_order:string;expected_version:number;next_status:string}
  resolve_report: {target_report:string;next_status:string;resolution_text:string}
  admin_set_announcement_status: {target_announcement:string;next_status:string}
  admin_set_release_status: {target_release:string;next_status:string}
}
async function rpc<N extends keyof RpcArgs>(name:N,args:RpcArgs[N],signal?:AbortSignal):Promise<z.output<(typeof schemas)[N]>> {
  if (!backend) throw new Error('管理台尚未配置后端连接。')
  const request = backend.rpc(name,args)
  const {data,error} = await (signal ? request.abortSignal(signal) : request)
  if (error) throw error
  return schemas[name].parse(data) as z.output<(typeof schemas)[N]>
}
export const api = { rpc }
export async function hasAdminRole(userId:string) {
  if (!backend) return false
  try { const user = await rpc('admin_me',{}); return user.id === userId && !user.is_blocked && ['moderator','admin','super_admin'].includes(user.role) }
  catch { return false }
}
export async function currentAdmin() {
  return backend ? rpc('admin_me',{}) : {id:'preview',role:'super_admin',email:'本地预览',is_blocked:false}
}
