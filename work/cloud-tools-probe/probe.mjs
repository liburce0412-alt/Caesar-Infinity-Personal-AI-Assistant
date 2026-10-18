import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { projectedTools, ToolCallAccumulator } from '../../apps/cloud-free/tools.mjs'

export const ENDPOINT = 'https://open.bigmodel.cn/api/paas/v4/chat/completions'
export const MODELS = Object.freeze(['glm-4.7-flash', 'glm-4-flash-250414'])
export const LIMITS = Object.freeze({ requests: 4, timeoutMs: 25_000, maxTokens: 256, responseBytes: 65_536 })
const TOOL = 'time.list_records'
const EXPECTED_ANSWER = '合成数据中没有时间记录。'
const SYSTEM = '这是隔离的合成工具协议测试，不连接真实账号、记录或网页。先调用唯一只读工具 time_list_records，参数 limit 为 1。收到工具结果后不再调用工具；若结果是空数组，只回答“合成数据中没有时间记录。”。不要解释测试步骤，不得编造数据。'
const USER = '请读取这次合成测试中的一条时间记录；必须先等工具返回，再报告结果。'
const DIAGNOSTIC_DELTA_KEYS = ['role', 'content', 'reasoning_content', 'tool_calls']
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
class ProbeError extends Error {
  constructor(code, httpStatus) { super(code); this.code = code; this.httpStatus = httpStatus }
}
const invalidTool = () => new ProbeError('invalid_tool_call')

export function initialRequest(model) {
  if (!MODELS.includes(model)) throw new ProbeError('model_not_allowed')
  return {
    model, stream: true, max_tokens: LIMITS.maxTokens, thinking: { type: 'disabled' },
    tools: projectedTools({ toolProtocol: 1, tools: [TOOL] }, invalidTool),
    tool_choice: 'auto', parallel_tool_calls: false,
    messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: USER }],
  }
}

function publicError(error) {
  // Never serialize upstream messages, URLs from exceptions, headers, IDs or bodies.
  return error instanceof ProbeError
    ? { errorCode: error.code, ...(error.httpStatus ? { httpStatus: error.httpStatus } : {}),
      ...(error.streamDiagnostics ? { streamDiagnostics: error.streamDiagnostics } : {}) }
    : { errorCode: 'unexpected_error' }
}

async function httpFailure(response) {
  const reader = response.body?.getReader()
  let code = '', size = 0, raw = ''
  try {
    if (reader) {
      const decoder = new TextDecoder()
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        size += value.byteLength
        if (size > 8192) break
        raw += decoder.decode(value, { stream: true })
      }
      if (size <= 8192) {
        raw += decoder.decode()
        const candidate = String(JSON.parse(raw).error?.code ?? '')
        if (/^\d{4}$/.test(candidate)) code = candidate
      }
    }
  } catch { /* Only the bounded numeric provider code may leave this function. */ }
  finally { if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock() } }
  throw new ProbeError(code ? `provider_${code}` : 'http_error', response.status)
}

async function readStream(response, model, stage, diagnostics) {
  if (!response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream')) {
    throw new ProbeError('unexpected_content_type')
  }
  if (!response.body) throw new ProbeError('missing_body')
  const reader = response.body.getReader(), decoder = new TextDecoder()
  const calls = new ToolCallAccumulator([TOOL], invalidTool)
  Object.assign(diagnostics, { packetCount: 0, finishReasonCounts: { stop: 0, tool_calls: 0, length: 0, other: 0 },
    repeatedFinishCount: 0, postFinishPayloadCount: 0, postFinishToolDeltaCount: 0,
    postFinishArgumentFragmentCount: 0, postFinishDeltaKeys: [],
    toolDeltaCount: 0, argumentFragmentCount: 0, doneMarkerCount: 0 })
  let buffer = '', text = '', finish = null, done = false, bytes = 0
  let packets = 0, argumentFragments = 0, modelEchoObserved = false
  function line(value) {
    if (done || !value.startsWith('data:')) return
    const raw = value.slice(5).trim()
    if (!raw) return
    if (raw === '[DONE]') { diagnostics.doneMarkerCount++; done = true; return }
    let packet
    try { packet = JSON.parse(raw) } catch { throw new ProbeError('invalid_json') }
    if (!object(packet)) throw new ProbeError('invalid_packet')
    packets++
    diagnostics.packetCount++
    if (packet.error) {
      const code = String(packet.error.code ?? '')
      throw new ProbeError(/^\d{4}$/.test(code) ? `provider_${code}` : 'stream_error')
    }
    if (packet.model != null) {
      if (packet.model !== model) throw new ProbeError('unexpected_model')
      modelEchoObserved = true
    }
    if (!Array.isArray(packet.choices) || packet.choices.length > 1) throw new ProbeError('invalid_choices')
    if (!packet.choices.length) return // Optional usage-only event.
    const choice = packet.choices[0], delta = choice?.delta
    if (!object(choice) || (choice.index != null && choice.index !== 0) || !object(delta)) {
      throw new ProbeError('invalid_choice')
    }
    if (choice.finish_reason != null) {
      const reason = ['stop', 'tool_calls', 'length'].includes(choice.finish_reason) ? choice.finish_reason : 'other'
      diagnostics.finishReasonCounts[reason]++
    }
    if (finish != null) {
      diagnostics.postFinishDeltaKeys = DIAGNOSTIC_DELTA_KEYS.filter(key =>
        diagnostics.postFinishDeltaKeys.includes(key) || Object.hasOwn(delta, key))
    }
    if (delta.role != null && delta.role !== 'assistant') throw new ProbeError('invalid_role')
    // Only the fixed assistant role is metadata, never arbitrary delta fields.
    const hasPayload = Object.entries(delta).some(([key, value]) =>
      key !== 'role' && value != null && value !== '' && (!Array.isArray(value) || value.length > 0))
    if (finish != null && hasPayload) {
      diagnostics.postFinishPayloadCount++
      if (Array.isArray(delta.tool_calls) && delta.tool_calls.length) {
        diagnostics.postFinishToolDeltaCount++
        diagnostics.postFinishArgumentFragmentCount += delta.tool_calls.filter(call =>
          typeof call.function?.arguments === 'string' && call.function.arguments.length > 0).length
      }
      throw new ProbeError('data_after_finish')
    }
    if (delta.tool_calls != null) {
      if (!Array.isArray(delta.tool_calls)) throw invalidTool()
      if (delta.tool_calls.length) {
        diagnostics.toolDeltaCount++
        if (stage !== 'tool') throw new ProbeError('unexpected_tool_call')
        calls.append(delta.tool_calls)
        argumentFragments += delta.tool_calls.filter(call => typeof call.function?.arguments === 'string').length
        diagnostics.argumentFragmentCount = argumentFragments
      }
    }
    if (delta.content != null) {
      if (typeof delta.content !== 'string') throw new ProbeError('invalid_content')
      text += delta.content
      if (text.length > 4096) throw new ProbeError('reply_too_large')
    }
    if (choice.finish_reason != null) {
      if (!['stop', 'tool_calls'].includes(choice.finish_reason)) {
        throw new ProbeError(choice.finish_reason === 'length' ? 'finish_length' : 'invalid_finish')
      }
      if (finish != null && finish !== choice.finish_reason) throw new ProbeError('conflicting_finish')
      if (finish != null) diagnostics.repeatedFinishCount++
      finish = choice.finish_reason
    }
  }
  try {
    while (!done) {
      const part = await reader.read()
      if (part.done) { buffer += decoder.decode(); if (buffer) line(buffer); break }
      bytes += part.value.byteLength
      if (bytes > LIMITS.responseBytes) throw new ProbeError('response_too_large')
      buffer += decoder.decode(part.value, { stream: true })
      let newline
      while ((newline = buffer.indexOf('\n')) >= 0 && !done) {
        line(buffer.slice(0, newline).trimEnd())
        buffer = buffer.slice(newline + 1)
      }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
  if (!done) throw new ProbeError('missing_done')
  const call = calls.complete()
  if (stage === 'tool') {
    if (!call || finish !== 'tool_calls') throw new ProbeError('missing_tool_finish')
    const args = JSON.parse(call.arguments)
    if (args.limit !== 1) throw new ProbeError('unexpected_arguments')
  } else {
    if (call || finish !== 'stop') throw new ProbeError('missing_answer_finish')
    // A narrow fixture oracle, not a general semantic/model quality evaluation.
    if (text.replace(/[\s。.!！]/gu, '') !== EXPECTED_ANSWER.replace(/。/g, '')) {
      throw new ProbeError('answer_mismatch')
    }
  }
  return { call, text, summary: { finishReason: finish, repeatedFinishCount: diagnostics.repeatedFinishCount,
    postFinishDeltaKeys: diagnostics.postFinishDeltaKeys,
    packets, argumentFragments,
    argumentChars: call?.arguments.length ?? 0, toolCalls: call ? 1 : 0,
    answerChars: stage === 'answer' ? text.length : 0, modelEchoObserved, ok: true } }
}

export async function runProbe({ apiKey, fetchImpl = fetch, timeoutMs = LIMITS.timeoutMs, only250414 = false } = {}) {
  if (typeof apiKey !== 'string' || !apiKey.trim() || apiKey.length > 4096 || /[\r\n]/.test(apiKey)) {
    throw new ProbeError('missing_or_invalid_key')
  }
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > LIMITS.timeoutMs) {
    throw new ProbeError('invalid_timeout')
  }
  if (typeof only250414 !== 'boolean') throw new ProbeError('invalid_model_selection')
  const models = only250414 ? [MODELS[1]] : MODELS
  const requestLimit = only250414 ? 2 : LIMITS.requests
  let requestsAttempted = 0
  const results = []
  for (const model of models) {
    const result = { model, protocol: 'bigmodel-sse-function-calling', requestsAttempted: 0,
      syntheticToolExecutions: 0, ok: false }
    let stage = 'tool'
    const request = async body => {
      if (requestsAttempted >= requestLimit || result.requestsAttempted >= 2) throw new ProbeError('request_limit')
      requestsAttempted++; result.requestsAttempted++
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), timeoutMs)
      const diagnostics = {}
      try {
        const response = await fetchImpl(ENDPOINT, {
          method: 'POST', redirect: 'error', signal: controller.signal,
          headers: { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json', accept: 'text/event-stream' },
          body: JSON.stringify(body),
        })
        if (!response.ok) await httpFailure(response)
        return await readStream(response, model, stage, diagnostics)
      } catch (error) {
        const safeError = controller.signal.aborted ? new ProbeError('timeout')
          : error instanceof ProbeError ? error : new ProbeError('network_error')
        if (diagnostics.packetCount != null) safeError.streamDiagnostics = diagnostics
        throw safeError
      } finally { clearTimeout(timer); controller.abort() }
    }
    try {
      const body = initialRequest(model)
      const first = await request(body)
      result.tool = first.summary
      // This is the whole executor: fixed synthetic data, no App/DB/user access.
      const syntheticResult = '[]'
      result.syntheticToolExecutions++
      const call = { id: first.call.id, type: 'function', function: {
        name: first.call.wireName, arguments: first.call.arguments,
      } }
      stage = 'answer'
      const answer = await request({ ...body, messages: [...body.messages,
        { role: 'assistant', content: first.text, tool_calls: [call] },
        { role: 'tool', tool_call_id: call.id, name: call.function.name, content: syntheticResult },
      ] })
      result.answer = answer.summary
      result.ok = true
    } catch (error) { result.failedStage = stage; Object.assign(result, publicError(error)) }
    results.push(result)
  }
  return { requestsAttempted, ok: results.every(result => result.ok), results }
}

const event = packet => `data: ${typeof packet === 'string' ? packet : JSON.stringify(packet)}\n\n`
export function mockFetch() {
  return async (_url, init) => {
    const body = JSON.parse(init.body), answer = body.messages.at(-1).role === 'tool'
    const packets = answer ? [
      { model: body.model, choices: [{ index: 0, delta: { content: EXPECTED_ANSWER }, finish_reason: 'stop' }] },
    ] : [
      { model: body.model, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'synthetic-call', type: 'function', function: { name: 'time_list_records', arguments: '{"limit":' } }] } }] },
      { model: body.model, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: '1}' } }] }, finish_reason: 'tool_calls' }] },
    ]
    const terminal = { model: body.model, choices: [{ index: 0,
      delta: { role: 'assistant', content: '' }, finish_reason: answer ? 'stop' : 'tool_calls' }] }
    return new Response([...packets, terminal, '[DONE]'].map(event).join(''), { headers: { 'content-type': 'text/event-stream' } })
  }
}

export async function main(args, { env = process.env, fetchImpl = fetch, write = console.log } = {}) {
  const selectionFlag = '--model=glm-4-flash-250414'
  const only250414 = args.includes(selectionFlag)
  const rest = args.filter(arg => arg !== selectionFlag)
  const mode = rest.length === 0 ? '--dry-run' : rest.length === 1 ? rest[0] : ''
  if (args.filter(arg => arg === selectionFlag).length > 1 || !['--dry-run', '--mock', '--live'].includes(mode)) {
    write(JSON.stringify({ ok: false, requestsAttempted: 0, errorCode: 'invalid_arguments' })); return 1
  }
  if (mode === '--live' && !only250414) {
    write(JSON.stringify({ ok: false, requestsAttempted: 0, errorCode: 'live_requires_250414_only' })); return 1
  }
  if (mode === '--dry-run') {
    // Deliberately do not inspect environment values, files or network in this branch.
    write(JSON.stringify({ mode: 'dry-run', requestsAttempted: 0, endpoint: ENDPOINT,
      models: only250414 ? [MODELS[1]] : MODELS, keyVariable: 'ZHIPU_API_KEY', protocol: 'bigmodel-sse-function-calling',
      tool: TOOL, wireTool: 'time_list_records', syntheticResultCount: 0,
      limits: { ...LIMITS, requests: only250414 ? 2 : LIMITS.requests } }, null, 2))
    return 0
  }
  try {
    const report = await runProbe({ apiKey: mode === '--live' ? env.ZHIPU_API_KEY : 'synthetic-not-a-credential',
      fetchImpl: mode === '--live' ? fetchImpl : mockFetch(), only250414 })
    write(JSON.stringify({ mode: mode.slice(2), networkRequestsAttempted: mode === '--live' ? report.requestsAttempted : 0,
      ...report }, null, 2))
    return report.ok ? 0 : 1
  } catch (error) {
    write(JSON.stringify({ mode: mode.slice(2), requestsAttempted: 0, ok: false, ...publicError(error) })); return 1
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  process.exitCode = await main(process.argv.slice(2))
}
