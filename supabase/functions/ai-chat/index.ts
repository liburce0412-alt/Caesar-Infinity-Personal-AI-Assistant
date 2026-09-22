import { isChatRequest } from './request.ts'
import { modelForMode, thinkingForMode } from './protocol.ts'

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

const encoder = new TextEncoder()
const sse = (event: 'meta'|'status'|'delta'|'done'|'error', data: unknown) => encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: cors })
  if (request.method !== 'POST') return jsonError(405, 'method_not_allowed', '仅支持 POST。')
  const declaredSize = Number(request.headers.get('content-length') ?? 0)
  if (Number.isFinite(declaredSize) && declaredSize > 262_144) return jsonError(413, 'request_too_large', '请求内容过大，请缩短对话后重试。')

  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY')
  const deepseekKey = Deno.env.get('DEEPSEEK_API_KEY')
  const deepseekBase = Deno.env.get('DEEPSEEK_API_BASE') ?? 'https://api.deepseek.com'
  const authorization = request.headers.get('authorization')
  if (!supabaseUrl || !anonKey || !deepseekKey) return jsonError(503, 'server_not_configured', 'AI 服务尚未完成安全配置，请稍后重试。')
  if (!authorization?.startsWith('Bearer ')) return jsonError(401, 'authentication_required', '登录已失效，请重新登录。')

  const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, { headers: { apikey: anonKey, Authorization: authorization } })
  if (!userResponse.ok) return jsonError(401, 'authentication_required', '登录已失效，请重新登录。')
  await userResponse.body?.cancel()

  let body: unknown
  try {
    const rawBody = await readBodyLimited(request, 262_144)
    body = JSON.parse(rawBody)
  } catch (error) {
    if (error instanceof PayloadTooLarge) return jsonError(413, 'request_too_large', '请求内容过大，请缩短对话后重试。')
    return jsonError(400, 'invalid_json', '请求内容无法读取，请重试。')
  }
  if (!isChatRequest(body)) return jsonError(400, 'invalid_request', '消息、模式或上下文不符合要求。')

  const limit = body.mode === 'deep' ? 20 : 100
  const quota = await fetch(`${supabaseUrl}/rest/v1/rpc/claim_ai_request`, {
    method:'POST', headers:{ apikey:anonKey, Authorization:authorization, 'Content-Type':'application/json' }, body:JSON.stringify({max_requests:limit}),
  })
  if (!quota.ok) {
    const detail = await quota.text()
    if (detail.includes('daily_ai_quota_exhausted')) return jsonError(429, 'quota_exhausted', `今天的 ${body.mode === 'deep' ? '深度' : '快速'}额度已用完，明天会自动恢复。`)
    return jsonError(503, 'quota_check_failed', '暂时无法确认可用额度，请稍后重试。')
  }

  const model = modelForMode(body.mode)
  const contextMessage = body.context ? { role:'system' as const, content:`CampusAI 用户上下文（仅用于本次回答）：${JSON.stringify(body.context)}` } : null
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), body.mode === 'deep' ? 120_000 : 45_000)
  let upstream: Response
  try {
    upstream = await fetch(`${deepseekBase.replace(/\/$/,'')}/chat/completions`, {
      method:'POST', signal:controller.signal,
      headers:{Authorization:`Bearer ${deepseekKey}`,'Content-Type':'application/json'},
      body:JSON.stringify({
        model, stream:true, stream_options:{include_usage:true}, temperature:body.mode === 'fast' ? .45 : .3,
        thinking:thinkingForMode(body.mode),
        messages:[...(contextMessage?[contextMessage]:[]),...body.messages],
      }),
    })
  } catch (error) {
    clearTimeout(timeout)
    return jsonError(error instanceof DOMException && error.name === 'AbortError' ? 504 : 502, 'provider_unavailable', 'AI 服务响应超时或暂不可用，请稍后重试。')
  }
  if (!upstream.ok || !upstream.body) {
    clearTimeout(timeout)
    await upstream.body?.cancel()
    console.error('DeepSeek upstream error', upstream.status)
    return jsonError(502, 'provider_error', 'AI 服务暂时没有返回有效结果，请稍后重试。')
  }

  const started = Date.now()
  const stream = new ReadableStream({
    async start(output) {
      output.enqueue(sse('meta',{model,mode:body.mode,requestId:crypto.randomUUID()}))
      output.enqueue(sse('status',{stage:body.mode === 'deep' ? 'planning' : 'responding',elapsedMs:0}))
      const reader = upstream.body!.getReader(); const decoder = new TextDecoder(); let buffer=''; let inputTokens=0; let outputTokens=0
      try {
        while(true){
          const {done,value}=await reader.read(); if(done) break; buffer+=decoder.decode(value,{stream:true})
          const lines=buffer.split('\n'); buffer=lines.pop() ?? ''
          for(const line of lines){
            if(!line.startsWith('data:')) continue; const raw=line.slice(5).trim(); if(!raw || raw==='[DONE]') continue
            try {
              const packet=JSON.parse(raw); const delta=packet.choices?.[0]?.delta?.content
              if(typeof delta==='string' && delta) output.enqueue(sse('delta',{text:delta}))
              if(packet.usage){inputTokens=packet.usage.prompt_tokens ?? inputTokens;outputTokens=packet.usage.completion_tokens ?? outputTokens}
            } catch { /* Ignore provider keepalive fragments. */ }
          }
        }
        const elapsedMs=Date.now()-started
        await fetch(`${supabaseUrl}/rest/v1/rpc/record_ai_usage`,{method:'POST',headers:{apikey:anonKey,Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify({add_input_tokens:inputTokens,add_output_tokens:outputTokens,add_cost_micros:0})})
        output.enqueue(sse('done',{elapsedMs,usage:{inputTokens,outputTokens}})); output.close()
      } catch(error){console.error('AI stream error',error);output.enqueue(sse('error',{code:'stream_interrupted',message:'连接中断，已保留收到的内容，可以重试。'}));output.close()}
      finally{clearTimeout(timeout);reader.releaseLock()}
    },
    cancel(){controller.abort();clearTimeout(timeout)},
  })
  return new Response(stream,{headers:{...cors,'Content-Type':'text/event-stream; charset=utf-8','Cache-Control':'no-cache, no-transform','X-Accel-Buffering':'no'}})
})

function jsonError(status:number, code:string, message:string){return new Response(JSON.stringify({error:{code,message}}),{status,headers:{...cors,'Content-Type':'application/json'}})}

class PayloadTooLarge extends Error {}

async function readBodyLimited(request: Request, maxBytes: number) {
  const reader = request.body?.getReader()
  if (!reader) return ''
  const decoder = new TextDecoder()
  let bytes = 0
  let text = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > maxBytes) throw new PayloadTooLarge()
      text += decoder.decode(value, { stream:true })
    }
    return text + decoder.decode()
  } finally {
    reader.releaseLock()
  }
}
