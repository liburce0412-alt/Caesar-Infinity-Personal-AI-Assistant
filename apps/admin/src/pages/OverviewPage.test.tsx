import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { OverviewPage } from './OverviewPage'

const state = vi.hoisted(() => ({ data: undefined as undefined | { users:number;records:number;pending:number;orders:number }, error: null as Error|null }))
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data:state.data, error:state.error, isFetching:false, refetch:vi.fn() }) }))
vi.mock('@tanstack/react-router', () => ({ Link: ({ children }: { children:React.ReactNode }) => React.createElement('a', null, children) }))
vi.mock('../lib/supabase', () => ({ supabase: {} }))

describe('configured overview data integrity', () => {
  beforeEach(() => { state.data=undefined;state.error=null })
  it('never fills missing live data with fabricated counts or a static live chart', () => {
    const html=renderToStaticMarkup(<OverviewPage />)
    expect(html).not.toContain('1,248')
    expect(html).not.toContain('+8.4%')
    expect(html).not.toContain('<polyline')
    expect(html).toContain('暂无数据')
  })
  it('shows fetched counts and makes refresh failures visible', () => {
    state.data={users:2,records:4,pending:1,orders:0};state.error=new Error('offline')
    const html=renderToStaticMarkup(<OverviewPage />)
    expect(html).toContain('role="alert"')
    expect(html).toContain('上次同步结果')
    expect(html).toContain('用户总数')
    expect(html).not.toContain('96.8%')
  })
})
