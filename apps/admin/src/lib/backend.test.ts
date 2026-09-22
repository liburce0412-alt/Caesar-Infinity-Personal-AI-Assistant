import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, hasAdminRole } from './backend'
import { actionsFor, executeOperation, toRecord } from './adminRecords'

const mock = vi.hoisted(() => ({ rpc:vi.fn(), signal:vi.fn() }))
vi.mock('./supabase', () => ({ supabase:{rpc:mock.rpc} }))
function response(data:unknown,error:unknown=null) {
  const promise=Promise.resolve({data,error})
  return Object.assign(promise,{abortSignal:(signal:AbortSignal)=>{mock.signal(signal);return promise}})
}
beforeEach(()=>{vi.clearAllMocks()})
describe('admin request boundaries',()=>{
  it('forwards pagination and cancellation without dropping filters',async()=>{
    mock.rpc.mockReturnValue(response({rows:[{id:'r1'}],total:26}))
    const signal=new AbortController().signal
    const args={kind:'content',search:'数学',status:'pending',page:1,ascending:false}
    expect((await api.rpc('admin_records',args,signal)).total).toBe(26)
    expect(mock.rpc).toHaveBeenCalledWith('admin_records',args)
    expect(mock.signal).toHaveBeenCalledWith(signal)
  })
  it('rejects a malformed records response instead of rendering unstable rows',async()=>{
    mock.rpc.mockReturnValue(response({rows:null,total:'26'}))
    await expect(api.rpc('admin_records',{kind:'users',search:'',status:'',page:0,ascending:false})).rejects.toThrow()
    expect(()=>toRecord('users',{})).toThrow('ID')
  })
  it('fails closed after a role change, account mismatch or block',async()=>{
    for(const user of [{id:'a',role:'student',is_blocked:false},{id:'b',role:'admin',is_blocked:false},{id:'a',role:'admin',is_blocked:true}]){
      mock.rpc.mockReturnValue(response(user))
      expect(await hasAdminRole('a')).toBe(false)
    }
    mock.rpc.mockReturnValue(response({id:'a',role:'admin',is_blocked:false}))
    expect(await hasAdminRole('a')).toBe(true)
  })
  it('invitation and draft errors propagate to mutation UI',async()=>{
    mock.rpc.mockReturnValue(response(null,new Error('denied')))
    await expect(api.rpc('admin_create_invitations',{count:2,days:30,note:'test'})).rejects.toThrow('denied')
    await expect(api.rpc('admin_save_draft',{kind:'announcements',id:'a',values:{title:'draft'}})).rejects.toThrow('denied')
  })
  it('extracting operations preserves optimistic order version checks',async()=>{
    mock.rpc.mockReturnValue(response(null))
    await executeOperation({type:'action',kind:'orders',record:{id:'o1',cells:[],raw:{version:7}},action:{key:'complete_order',label:'',detail:''},note:''})
    expect(mock.rpc).toHaveBeenCalledWith('transition_order',{target_order:'o1',expected_version:7,next_status:'completed'})
    expect(actionsFor('users',{id:'u1',cells:[],raw:{}},'moderator')).toEqual([])
  })
})
