import { supabase } from './supabase'

// Keep the admin UI on the same Supabase Auth session as REST/RPC requests.
export const backend = supabase
export const isBackendConfigured = Boolean(backend)
async function rpc(name: string, args: Record<string, unknown> = {}): Promise<any> {
  if (!backend) throw new Error('管理台尚未配置后端连接。')
  const { data, error } = await backend.rpc(name, args)
  if (error) throw error
  return data
}
export const api = {
  rpc,
  async get(path: string): Promise<any> {
    const url = new URL(path, 'https://admin.local')
    if (url.pathname === '/admin/me') return rpc('admin_me')
    if (url.pathname === '/admin/overview') return rpc('admin_overview')
    if (url.pathname === '/admin/records') return rpc('admin_records', {
      kind: url.searchParams.get('kind'), search: url.searchParams.get('search') || '',
      status: url.searchParams.get('status') || '', page: Number(url.searchParams.get('page') || 0),
      ascending: url.searchParams.get('ascending') === 'true',
    })
    throw new Error('未知的管理请求。')
  },
}
export async function hasAdminRole(userId: string) {
  if (!backend) return false
  try { const user = await rpc('admin_me'); return user.id === userId && !user.is_blocked && ['moderator','admin','super_admin'].includes(user.role) }
  catch { return false }
}
export async function currentAdmin() {
  return backend ? rpc('admin_me') : { id: 'preview', role: 'super_admin', email: '本地预览' }
}
