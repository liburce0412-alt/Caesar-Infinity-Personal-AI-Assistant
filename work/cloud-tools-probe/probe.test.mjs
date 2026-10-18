import test from 'node:test'
import assert from 'node:assert/strict'
import { ENDPOINT, LIMITS, MODELS, initialRequest, main, mockFetch, runProbe } from './probe.mjs'

const KEY = 'synthetic-unit-test-not-a-key'
const call = { index: 0, id: 'synthetic-private-call-id', type: 'function',
  function: { name: 'time_list_records', arguments: '{"limit":1}' } }
const packet = (delta = {}, finish_reason = null) => ({ choices: [{ index: 0, delta, finish_reason }] })
const tool = value => packet({ tool_calls: [value ?? structuredClone(call)] }, 'tool_calls')
const sse = (packets, chunkSize = 0) => {
  const bytes = new TextEncoder().encode(packets.map(value =>
    `data: ${typeof value === 'string' ? value : JSON.stringify(value)}\r\n\r\n`).join(''))
  const body = chunkSize ? new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += chunkSize) controller.enqueue(bytes.slice(i, i + chunkSize))
    controller.close()
  } }) : bytes
  return new Response(body, { headers: { 'content-type': 'text/event-stream' } })
}
const forbiddenEnv = new Proxy({}, { get() { throw new Error('Environment must not be read') } })
const forbiddenFetch = () => { throw new Error('Network must not be called') }

test('default/dry-run and invalid options do not inspect environment or fetch', async () => {
  for (const args of [[], ['--dry-run'], ['--unknown'], ['--live'], ['--live', '--extra'],
    ['--live', '--model=glm-4.7-flash'], ['--live', '--model=glm-4-plus']]) {
    const output = []
    await main(args, { env: forbiddenEnv, fetchImpl: forbiddenFetch, write: line => output.push(JSON.parse(line)) })
    assert.equal(output.length, 1)
    assert.equal(output[0].requestsAttempted, 0)
  }
})

test('mock never reads environment or invokes the injected network transport', async () => {
  const output = []
  assert.equal(await main(['--mock'], { env: forbiddenEnv, fetchImpl: forbiddenFetch,
    write: line => output.push(JSON.parse(line)) }), 0)
  assert.equal(output[0].mode, 'mock')
  assert.equal(output[0].networkRequestsAttempted, 0)
  assert.equal(output[0].requestsAttempted, 4)
})

test('missing real key fails before transport without reading a file or an alternate key', async () => {
  const names = [], output = []
  const env = new Proxy({}, { get(_target, name) { names.push(name); return undefined } })
  assert.equal(await main(['--live', '--model=glm-4-flash-250414'],
    { env, fetchImpl: forbiddenFetch, write: value => output.push(JSON.parse(value)) }), 1)
  assert.deepEqual(names, ['ZHIPU_API_KEY'])
  assert.equal(output[0].errorCode, 'missing_or_invalid_key')
  assert.equal(output[0].requestsAttempted, 0)
})

test('only fixed free model requests and one synthetic result per model can leave the runner', async () => {
  const seen = [], canned = mockFetch()
  const result = await runProbe({ apiKey: KEY, fetchImpl: async (url, init) => {
    assert.equal(url, ENDPOINT)
    assert.equal(init.redirect, 'error')
    assert.equal(init.headers.authorization, `Bearer ${KEY}`)
    const body = JSON.parse(init.body)
    seen.push(body)
    assert.equal(body.max_tokens, 256)
    assert.deepEqual(body.thinking, { type: 'disabled' })
    assert.deepEqual(body.tools.map(item => item.function.name), ['time_list_records'])
    assert.equal(body.parallel_tool_calls, false)
    assert.equal(body.tool_choice, 'auto')
    if (body.messages.at(-1).role === 'tool') {
      const previous = body.messages.at(-2).tool_calls[0]
      assert.equal(body.messages.at(-1).content, '[]')
      assert.equal(body.messages.at(-1).tool_call_id, previous.id)
      assert.equal(previous.function.name, 'time_list_records')
      assert.deepEqual(JSON.parse(previous.function.arguments), { limit: 1 })
    }
    return canned(url, init)
  } })
  assert.equal(result.ok, true)
  assert.equal(result.requestsAttempted, LIMITS.requests)
  assert.deepEqual(seen.map(body => body.model), [MODELS[0], MODELS[0], MODELS[1], MODELS[1]])
  assert.equal(result.results.every(item => item.syntheticToolExecutions === 1), true)
  assert.equal(result.results.every(item => item.tool.argumentFragments === 2), true)
  assert.throws(() => initialRequest('glm-4-plus'), /model_not_allowed/)
})

test('UTF-8 and SSE split at every byte still aggregate parameters and final Chinese text', async () => {
  const result = await runProbe({ apiKey: KEY, fetchImpl: async (_url, init) => {
    const answer = JSON.parse(init.body).messages.at(-1).role === 'tool'
    return sse(answer ? [packet({ content: '合成数据中没有时间记录。' }, 'stop'), '[DONE]'] : [tool(), '[DONE]'], 1)
  } })
  assert.equal(result.ok, true)
  assert.equal(result.requestsAttempted, 4)
})

const invalidCases = [
  ['unknown tool', [tool({ ...call, function: { name: 'web_search', arguments: '{}' } }), '[DONE]'], 'invalid_tool_call'],
  ['parallel tool', [packet({ tool_calls: [call, { ...call, index: 1, id: 'other' }] }, 'tool_calls'), '[DONE]'], 'invalid_tool_call'],
  ['malformed argument', [tool({ ...call, function: { ...call.function, arguments: '{"limit":' } }), '[DONE]'], 'invalid_tool_call'],
  ['schema violation', [tool({ ...call, function: { ...call.function, arguments: '{"limit":51}' } }), '[DONE]'], 'invalid_tool_call'],
  ['changed fixture limit', [tool({ ...call, function: { ...call.function, arguments: '{"limit":2}' } }), '[DONE]'], 'unexpected_arguments'],
  ['missing id', [tool({ ...call, id: undefined }), '[DONE]'], 'invalid_tool_call'],
  ['no DONE', [tool()], 'missing_done'],
  ['wrong finish', [packet({ tool_calls: [call] }, 'stop'), '[DONE]'], 'missing_tool_finish'],
  ['truncated token limit', [packet({}, 'length'), '[DONE]'], 'finish_length'],
  ['data after finish', [tool(), packet({ content: 'untrusted text' }), '[DONE]'], 'data_after_finish'],
  ['conflicting finish', [tool(), packet({}, 'stop'), '[DONE]'], 'conflicting_finish'],
  ['same finish with new content', [tool(), packet({ content: 'new text' }, 'tool_calls'), '[DONE]'], 'data_after_finish'],
  ['same finish with new arguments', [tool(), packet({ tool_calls: [{ index: 0, function: { arguments: '1}' } }] }, 'tool_calls'), '[DONE]'], 'data_after_finish'],
  ['same finish with new reasoning', [tool(), packet({ reasoning_content: 'new reasoning' }, 'tool_calls'), '[DONE]'], 'data_after_finish'],
  ['repeated finish without DONE', [tool(), packet({}, 'tool_calls')], 'missing_done'],
  ['unexpected model', [{ ...tool(), model: 'glm-4-plus' }, '[DONE]'], 'unexpected_model'],
]
for (const [name, packets, code] of invalidCases) {
  test(`${name} stops before executing synthetic tool or requesting an answer`, async () => {
    const result = await runProbe({ apiKey: KEY, fetchImpl: async () => sse(packets) })
    assert.equal(result.ok, false)
    assert.equal(result.requestsAttempted, 2)
    for (const row of result.results) {
      assert.equal(row.errorCode, code)
      assert.equal(row.requestsAttempted, 1)
      assert.equal(row.syntheticToolExecutions, 0)
    }
  })
}

test('identical terminal reasons without new payload are accepted once for both stages', async () => {
  const result = await runProbe({ apiKey: KEY, only250414: true, fetchImpl: async (_url, init) =>
    sse(JSON.parse(init.body).messages.at(-1).role === 'tool'
      ? [packet({ content: '合成数据中没有时间记录。' }, 'stop'), packet({ content: '' }, 'stop'), '[DONE]']
      : [tool(), packet({ content: '', tool_calls: [] }, 'tool_calls'), '[DONE]']) })
  assert.equal(result.ok, true)
  assert.equal(result.requestsAttempted, 2)
  assert.equal(result.results.length, 1)
  assert.equal(result.results[0].syntheticToolExecutions, 1)
  assert.equal(result.results[0].tool.repeatedFinishCount, 1)
  assert.equal(result.results[0].answer.repeatedFinishCount, 1)
})

test('official assistant role in an otherwise empty repeated terminal delta is metadata', async () => {
  const result = await runProbe({ apiKey: KEY, only250414: true, fetchImpl: mockFetch() })
  assert.equal(result.ok, true)
  assert.equal(result.requestsAttempted, 2)
  const row = result.results[0]
  assert.equal(row.syntheticToolExecutions, 1)
  for (const phase of [row.tool, row.answer]) {
    assert.equal(phase.repeatedFinishCount, 1)
    assert.deepEqual(phase.postFinishDeltaKeys, ['role', 'content'])
  }
})

test('any explicit role other than assistant is rejected before or after the terminal', async () => {
  for (const role of ['user', 'system', 'tool', '', 1, {}, []]) {
    for (const packets of [
      [packet({ role, tool_calls: [call] }, 'tool_calls'), '[DONE]'],
      [tool(), packet({ role, content: '' }, 'tool_calls'), '[DONE]'],
    ]) {
      const result = await runProbe({ apiKey: KEY, only250414: true, fetchImpl: async () => sse(packets) })
      assert.equal(result.requestsAttempted, 1)
      assert.equal(result.results[0].errorCode, 'invalid_role')
      assert.equal(result.results[0].syntheticToolExecutions, 0)
    }
  }
})

test('assistant metadata does not permit new known payload or unknown valued fields', async () => {
  const secretField = 'private-provider-field', secretValue = 'private-provider-value'
  for (const payload of [{ content: secretValue }, { reasoning_content: secretValue },
    { tool_calls: [{ index: 0, function: { arguments: secretValue } }] },
    { [secretField]: secretValue }, { [secretField]: false }, { [secretField]: 0 }, { [secretField]: {} }]) {
    const result = await runProbe({ apiKey: KEY, only250414: true, fetchImpl: async () =>
      sse([tool(), packet({ role: 'assistant', ...payload }, 'tool_calls'), '[DONE]']) })
    const row = result.results[0]
    assert.equal(row.errorCode, 'data_after_finish')
    assert.equal(row.syntheticToolExecutions, 0)
    assert.ok(row.streamDiagnostics.postFinishDeltaKeys.includes('role'))
    assert.ok(row.streamDiagnostics.postFinishDeltaKeys.every(key =>
      ['role', 'content', 'reasoning_content', 'tool_calls'].includes(key)))
    assert.equal(JSON.stringify(result).includes(secretField), false)
    assert.equal(JSON.stringify(result).includes(secretValue), false)
  }
})

test('failure diagnostics distinguish terminal conflicts and new parameters without raw payload', async () => {
  const sensitive = 'never-log-this-payload'
  const result = await runProbe({ apiKey: KEY, only250414: true, fetchImpl: async () =>
    sse([tool(), packet({ tool_calls: [{ index: 0, function: { arguments: sensitive } }] }, 'stop'), '[DONE]']) })
  const diagnostics = result.results[0].streamDiagnostics
  assert.deepEqual(diagnostics.finishReasonCounts, { stop: 1, tool_calls: 1, length: 0, other: 0 })
  assert.equal(diagnostics.postFinishPayloadCount, 1)
  assert.equal(diagnostics.postFinishToolDeltaCount, 1)
  assert.equal(diagnostics.postFinishArgumentFragmentCount, 1)
  assert.equal(diagnostics.doneMarkerCount, 0)
  assert.equal(JSON.stringify(result).includes(sensitive), false)
  assert.equal(JSON.stringify(result).includes(call.id), false)
})

test('fixed single-model CLI selection spends at most the remaining two requests on 250414', async () => {
  const output = [], models = [], canned = mockFetch()
  assert.equal(await main(['--live', '--model=glm-4-flash-250414'], {
    env: { ZHIPU_API_KEY: KEY }, fetchImpl: async (url, init) => {
      models.push(JSON.parse(init.body).model)
      return canned(url, init)
    }, write: value => output.push(JSON.parse(value)),
  }), 0)
  assert.deepEqual(models, ['glm-4-flash-250414', 'glm-4-flash-250414'])
  assert.equal(output[0].requestsAttempted, 2)
  assert.equal(output[0].results.length, 1)
})

test('second tool request never triggers a third request or second local execution', async () => {
  const result = await runProbe({ apiKey: KEY, fetchImpl: async () => sse([tool(), '[DONE]']) })
  assert.equal(result.requestsAttempted, 4)
  for (const row of result.results) {
    assert.equal(row.errorCode, 'unexpected_tool_call')
    assert.equal(row.syntheticToolExecutions, 1)
    assert.equal(row.failedStage, 'answer')
  }
})

test('a completed natural-language answer must acknowledge the synthetic empty data', async () => {
  const result = await runProbe({ apiKey: KEY, fetchImpl: async (_url, init) =>
    sse(JSON.parse(init.body).messages.at(-1).role === 'tool'
      ? [packet({ content: '发现三条真实私人记录。' }, 'stop'), '[DONE]'] : [tool(), '[DONE]']) })
  assert.equal(result.requestsAttempted, 4)
  assert.equal(result.results.every(row => row.errorCode === 'answer_mismatch'), true)
  assert.equal(JSON.stringify(result).includes('私人记录'), false)
})

test('HTTP failure reports only numeric provider code and status without retry', async () => {
  const result = await runProbe({ apiKey: KEY, fetchImpl: async () => Response.json({ error: {
    code: '1305', message: `private account raw credential ${KEY}`, request_id: 'private-id',
  } }, { status: 429 }) })
  assert.equal(result.requestsAttempted, 2)
  assert.equal(result.results.every(row => row.errorCode === 'provider_1305' && row.httpStatus === 429), true)
  assert.equal(JSON.stringify(result).includes(KEY), false)
  assert.equal(JSON.stringify(result).includes('private'), false)
})

test('unknown provider errors and transport errors cannot leak their message as a code', async () => {
  for (const fetchImpl of [async () => Response.json({ error: { code: KEY, message: KEY } }, { status: 500 }),
    async () => { throw new Error(KEY) }]) {
    const result = await runProbe({ apiKey: KEY, fetchImpl })
    assert.equal(result.requestsAttempted, 2)
    assert.equal(JSON.stringify(result).includes(KEY), false)
  }
})

test('timeout aborts each transport without retries', async () => {
  let aborts = 0
  const result = await runProbe({ apiKey: KEY, timeoutMs: 5, fetchImpl: async (_url, init) =>
    new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => {
      aborts++; reject(new Error(KEY))
    }, { once: true })) })
  assert.equal(aborts, 2)
  assert.equal(result.requestsAttempted, 2)
  assert.equal(result.results.every(row => row.errorCode === 'timeout'), true)
})

test('timeout also bounds a stalled SSE body', async () => {
  const result = await runProbe({ apiKey: KEY, timeoutMs: 5, fetchImpl: async (_url, init) => {
    const body = new ReadableStream({ start(controller) {
      init.signal.addEventListener('abort', () => controller.error(new Error(KEY)), { once: true })
    } })
    return new Response(body, { headers: { 'content-type': 'text/event-stream' } })
  } })
  assert.equal(result.requestsAttempted, 2)
  assert.equal(result.results.every(row => row.errorCode === 'timeout'), true)
})

test('oversized stream is bounded and cannot be a successful probe', async () => {
  const result = await runProbe({ apiKey: KEY, fetchImpl: async () =>
    new Response('x'.repeat(LIMITS.responseBytes + 1), { headers: { 'content-type': 'text/event-stream' } }) })
  assert.equal(result.requestsAttempted, 2)
  assert.equal(result.results.every(row => row.errorCode === 'response_too_large'), true)
})

test('successful report omits IDs, arguments, body text and credentials', async () => {
  const output = []
  assert.equal(await main(['--live', '--model=glm-4-flash-250414'], { env: { ZHIPU_API_KEY: KEY }, fetchImpl: mockFetch(),
    write: value => output.push(value) }), 0)
  const text = output.join('')
  for (const forbidden of [KEY, 'synthetic-call', '{"limit":', '合成数据中', 'Bearer', 'messages', 'tool_call_id']) {
    assert.equal(text.includes(forbidden), false)
  }
  assert.equal(JSON.parse(text).networkRequestsAttempted, 2)
})
